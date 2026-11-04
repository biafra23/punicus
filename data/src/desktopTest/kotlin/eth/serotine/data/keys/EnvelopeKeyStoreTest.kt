package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.MemoryStep
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.PasswordCost
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnvelopeKeyStoreTest {

    private val directory: File = Files.createTempDirectory("serotine-keys").toFile()
    private val hardhat = Mnemonic("test test test test test test test test test test test junk")
    private val abandon = Mnemonic(List(11) { "abandon" }.joinToString(" ") + " about")
    private val password = WalletPassword("correct horse battery")

    private val hardhatAddress = Address.of("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
    private val abandonAddress = Address.of("0x9858EfFD232B4033E47d90003D41EC34EcaEda94")

    private fun TestScope.store(hardware: HardwareSecret? = null) =
        EnvelopeKeyStore(directory.path, hardware, StandardTestDispatcher(testScheduler))

    private fun passwordOnly(address: Address) = KeyImport.Stored(address, KeyProtection.PASSWORD_ONLY)

    private fun envelopeOf(address: Address): File = directory.resolve("${address.hex}.seed")

    private fun sealedIn(file: File): SealedSeed = Json.decodeFromString(SealedSeed.serializer(), file.readText())

    /** A flipped digit in the wrapped DEK of [address]'s envelope: still well-formed, so only the tag notices. */
    private fun damageTheWrappedKeyOf(address: Address) {
        val damaged = sealedIn(envelopeOf(address)).let { sealed ->
            val flipped = (if (sealed.dek[0] == '0') '1' else '0') + sealed.dek.drop(1)
            SealedSeed(
                sealed.version, sealed.address, sealed.hardware, sealed.salt, sealed.opsLimit, sealed.memLimitBytes,
                sealed.dekNonce, flipped, sealed.seedNonce, sealed.seed,
            )
        }
        envelopeOf(address).writeText(Json.encodeToString(SealedSeed.serializer(), damaged))
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun storesTheSeedAndNamesTheFirstAccountOtherWalletsShow() = runTest {
        val store = store()
        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())

        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        assertEquals(KeyStoreDescription.Ready(passwordSet = true, KeyProtection.PASSWORD_ONLY), store.describe())
        assertEquals(
            listOf("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266.seed"),
            directory.list()!!.toList(),
            "one envelope, no temporary left behind",
        )
    }

    @Test
    fun deletingAKeyRemovesItsEnvelopeAndWithTheLastOneThePassword() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.importMnemonic(abandon, password)

        assertEquals(KeyDeletion.Deleted, store.delete(hardhatAddress))
        assertFalse(envelopeOf(hardhatAddress).exists())
        assertEquals(KeyDeletion.NotFound, store.delete(hardhatAddress))
        assertEquals(KeyStoreDescription.Ready(passwordSet = true, KeyProtection.PASSWORD_ONLY), store.describe())

        assertEquals(KeyDeletion.Deleted, store.delete(abandonAddress))
        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())
        // No envelope left to check a password against: the next import sets a new one.
        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, WalletPassword("another password")))
    }

    @Test
    fun aKeyThatCannotBeDeletedIsReportedAndStaysStored() = runTest {
        val store = store()
        // What the file system refuses to delete: a non-empty directory under the envelope's name.
        envelopeOf(hardhatAddress).mkdirs()
        envelopeOf(hardhatAddress).resolve("in-the-way").writeText("x")

        assertEquals(KeyDeletion.Failed, store.delete(hardhatAddress))
        assertTrue(envelopeOf(hardhatAddress).exists())
    }

    @Test
    fun laterImportsMustUseTheSamePassword() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)

        assertEquals(KeyImport.WrongPassword, store.importMnemonic(abandon, WalletPassword("something else")))
        assertEquals(KeyImport.WrongPassword, store.importMnemonic(abandon, WalletPassword("short")))
        assertEquals(1, directory.list()!!.size, "nothing stored on a wrong password")
        assertEquals(passwordOnly(abandonAddress), store.importMnemonic(abandon, password))
    }

    @Test
    fun aTooShortFirstPasswordIsRefused() = runTest {
        val store = store()

        assertEquals(KeyImport.PasswordTooShort, store.importMnemonic(hardhat, WalletPassword("short")))
        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())
    }

    @Test
    fun theStoredEnvelopeOpensWithThePasswordAndHoldsTheSeed() = runTest {
        store().importMnemonic(hardhat, password)
        val file = directory.listFiles()!!.single()
        val sealed = sealedIn(file)

        val opened = Envelope.open(sealed, password.text.encodeToByteArray(), null) as Envelope.Opened.Seed
        assertEquals(Bip39.seed(hardhat.words).toHex(), opened.bytes.toHex())
        // Nothing readable on disk: not the words, not the seed.
        assertFalse("junk" in file.readText())
        assertFalse(Bip39.seed(hardhat.words).toHex() in file.readText())
        // Password-only: the stronger KDF cost of rule 9.
        assertEquals(256L * 1024 * 1024, sealed.memLimitBytes)
    }

    @Test
    fun theLighterCostIsOnlyForAKeyInSecureHardware() = runTest {
        store(FakeHardware(claims = { KeyProtection.HARDWARE })).importMnemonic(hardhat, password)
        val inHardware = sealedIn(envelopeOf(hardhatAddress))
        assertEquals(3L to 64L * 1024 * 1024, inHardware.opsLimit to inHardware.memLimitBytes)

        // An emulator's software key store: a copy of the device's storage yields its key.
        assertEquals(
            KeyImport.Stored(abandonAddress, KeyProtection.SOFTWARE_KEY_STORE),
            store(FakeHardware(claims = { KeyProtection.SOFTWARE_KEY_STORE })).importMnemonic(abandon, password),
        )
        val inSoftware = sealedIn(envelopeOf(abandonAddress))
        assertEquals(4L to 256L * 1024 * 1024, inSoftware.opsLimit to inSoftware.memLimitBytes)
    }

    @Test
    fun aLostHardwareKeyMakesTheKeyStoreUnavailableNotAWrongPassword() = runTest {
        store(FakeHardware()).importMnemonic(hardhat, password)
        // The Keystore key is gone (an OS upgrade, a cleared key store); the envelope stays.
        var provisioned = false
        val lost = store(FakeHardware(provisioned = { provisioned }))

        assertEquals(KeyStoreDescription.Unavailable, lost.describe())
        assertEquals(KeyImport.Unavailable, lost.importMnemonic(abandon, password))
        assertEquals(listOf("${hardhatAddress.hex}.seed"), directory.list()!!.toList(), "nothing sealed beside it")

        provisioned = true
        assertEquals(KeyStoreDescription.Ready(passwordSet = true, KeyProtection.HARDWARE), lost.describe())
    }

    @Test
    fun aNewWalletNeedsNoHardwareKeyYet() = runTest {
        val store = store(FakeHardware(provisioned = { false }))

        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.HARDWARE), store.describe())
    }

    @Test
    fun aMistypedPasswordIsCheckedAgainstAtMostThreeEnvelopes() = runTest {
        var mixes = 0
        val hardware = FakeHardware(mixed = { mixes++ })
        val store = store(hardware)
        val phrases = listOf(
            hardhat,
            abandon,
            Mnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow"),
            Mnemonic("letter advice cage absurd amount doctor acoustic avoid letter advice cage above"),
            Mnemonic("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"),
        )
        phrases.dropLast(1).forEach { assertIs<KeyImport.Stored>(store.importMnemonic(it, password)) }
        mixes = 0

        assertEquals(KeyImport.WrongPassword, store.importMnemonic(phrases.last(), WalletPassword("mistyped password")))
        assertEquals(3, mixes, "one Argon2id run per envelope asked, and no more than three")
    }

    @Test
    fun aComputerWhoseTpmIsNotUsedYetSaysSo() = runTest {
        // Windows and Linux, for now (the owner's ruling of 2026-10-16).
        val store = EnvelopeKeyStore(
            directory.path,
            hardware = null,
            StandardTestDispatcher(testScheduler),
            withoutHardware = KeyProtection.PASSWORD_ONLY_TPM_NOT_USED,
        )

        assertEquals(
            KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY_TPM_NOT_USED),
            store.describe(),
        )
        assertEquals(
            KeyImport.Stored(hardhatAddress, KeyProtection.PASSWORD_ONLY_TPM_NOT_USED),
            store.importMnemonic(hardhat, password),
        )
        val sealed = sealedIn(envelopeOf(hardhatAddress))
        assertEquals(4L to 256L * 1024 * 1024, sealed.opsLimit to sealed.memLimitBytes, "the password-only cost")
        assertFailsWith<IllegalArgumentException>("a hardware kind is no password-only protection") {
            EnvelopeKeyStore(directory.path, hardware = null, withoutHardware = KeyProtection.HARDWARE)
        }
    }

    @Test
    fun aStoredSecretTheHardwareUnwrapsGetsTheFullCostToo() = runTest {
        // The Secure Enclave's kind: in hardware, but one unwrap and the search can leave the device.
        store(FakeHardware(everyGuessNeedsDevice = false)).importMnemonic(hardhat, password)

        val sealed = sealedIn(envelopeOf(hardhatAddress))
        assertEquals(4L to 256L * 1024 * 1024, sealed.opsLimit to sealed.memLimitBytes)
    }

    @Test
    fun filesThatCannotBeReadOrOpenedHereArePassedOver() = runTest {
        directory.resolve("0x" + "aa".repeat(20) + ".seed").writeText("{ not an envelope")
        val foreign = Envelope.seal(
            ByteArray(64),
            "0x" + "bb".repeat(20),
            "correct horse battery".encodeToByteArray(),
            FakeHardware(), // another device's secret: this store has none
            Envelope.Cost(opsLimit = 2, memLimitBytes = Envelope.MIN_MEMORY_BYTES),
        )
        directory.resolve("0x" + "bb".repeat(20) + ".seed")
            .writeText(Json.encodeToString(SealedSeed.serializer(), foreign))
        val store = store()

        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())
        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        // The one envelope this device can open decides the password from now on.
        assertEquals(KeyImport.WrongPassword, store.importMnemonic(abandon, WalletPassword("another password")))
    }

    @Test
    fun anEnvelopeUnderAnotherAccountsNameIsNotTrusted() = runTest {
        val store = store()
        store.importMnemonic(abandon, password)
        // abandon's envelope, renamed to hardhat's: it opens, but it is not hardhat's seed.
        envelopeOf(abandonAddress).copyTo(envelopeOf(hardhatAddress))

        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        assertEquals(hardhatAddress.hex, sealedIn(envelopeOf(hardhatAddress)).address, "sealed again, for hardhat")
    }

    @Test
    fun aDamagedEnvelopeIsSealedAgainOnTheNextImportOfItsWords() = runTest {
        val store = store()
        store.importMnemonic(abandon, password)
        store.importMnemonic(hardhat, password)
        envelopeOf(hardhatAddress).writeText("damaged")

        assertEquals(KeyImport.WrongPassword, store.importMnemonic(hardhat, WalletPassword("another password")))
        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        val opened = Envelope.open(sealedIn(envelopeOf(hardhatAddress)), password.text.encodeToByteArray(), null)
        assertIs<Envelope.Opened.Seed>(opened)
    }

    @Test
    fun anEnvelopeDamagedPastParsingDoesNotLockOutTheRightPassword() = runTest {
        val store = store()
        store.importMnemonic(abandon, password)
        store.importMnemonic(hardhat, password)
        damageTheWrappedKeyOf(hardhatAddress)

        assertEquals(KeyImport.WrongPassword, store.importMnemonic(hardhat, WalletPassword("another password")))
        // Its own envelope refuses, abandon's opens: the password is right, the file is damaged.
        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        val opened = Envelope.open(sealedIn(envelopeOf(hardhatAddress)), password.text.encodeToByteArray(), null)
        assertIs<Envelope.Opened.Seed>(opened, "sealed again")
    }

    @Test
    fun anImportAlreadyDoneWritesNothing() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        val before = envelopeOf(hardhatAddress).readText()

        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        assertEquals(before, envelopeOf(hardhatAddress).readText())
    }

    @Test
    fun aCrashedWritesTemporaryIsSweptAway() = runTest {
        directory.resolve("0x" + "cc".repeat(20) + ".seed.0123456789ab.tmp").writeText("half an envelope")

        store().importMnemonic(hardhat, password)

        assertEquals(listOf("${hardhatAddress.hex}.seed"), directory.list()!!.toList())
    }

    @Test
    fun aCreateOnlyWriteNeverReplacesAFile() {
        val file = directory.resolve("state.json").apply { writeText("first") }

        assertFailsWith<FileAlreadyExistsException> {
            EnvelopeCrypto.writeDurably(file.path, "second".encodeToByteArray(), replace = false)
        }
        assertEquals("first", file.readText())
        assertEquals(listOf("state.json"), directory.list()!!.toList(), "no temporary left behind")
    }

    @Test
    fun theDirectoryAndTheEnvelopesAreThisUsersAlone() = runTest {
        // Owner permissions exist where the file system has them (not on Windows), as in EnvelopeCrypto.
        assumeTrue("no POSIX permissions here", "posix" in FileSystems.getDefault().supportedFileAttributeViews())
        store().importMnemonic(hardhat, password)

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.toPath())))
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(envelopeOf(hardhatAddress).toPath())),
        )
    }

    @Test
    fun aFailingHardwareKeyStoreIsUnavailableNotACrash() = runTest {
        val store = store(FakeHardware(claims = { throw IllegalStateException("the secure element is gone") }))

        assertEquals(KeyStoreDescription.Unavailable, store.describe())
        assertEquals(KeyImport.Unavailable, store.importMnemonic(hardhat, password))
        assertTrue(directory.list()!!.isEmpty(), "nothing written")
    }

    @Test
    fun theSamePasswordComposedDifferentlyIsTheSamePassword() = runTest {
        val store = store()
        store.importMnemonic(hardhat, WalletPassword("pässwort und mehr")) // "ä" as one code point

        assertEquals(
            passwordOnly(abandonAddress),
            store.importMnemonic(abandon, WalletPassword("pässwort und mehr")), // "a" + combining diaeresis
        )
    }

    @Test
    fun wordsThatAreNotAMnemonicAreRefusedAndNothingIsStored() = runTest {
        val store = store()

        assertEquals(
            KeyImport.Invalid(MnemonicProblem.Checksum),
            store.importMnemonic(Mnemonic(List(12) { "test" }.joinToString(" ")), password),
        )
        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())
    }

    // --- Signing.

    private val digest = Digest.of("0x" + "5a".repeat(32))
    private val notThePassword = Unlock.WithPassword(WalletPassword("not the password"))

    /** Whether [signature] is [signed] signed by the key of [address], and has a low s. */
    private fun signs(signature: Signature, address: Address, signed: Digest = digest): Boolean {
        val compact = (signature.r + signature.s).fromHex()
        val publicKey = Secp256k1.ecdsaRecover(compact, signed.hex.fromHex(), signature.recoveryId)
        val hash = org.kotlincrypto.hash.sha3.Keccak256().digest(publicKey.copyOfRange(1, 65))
        val recovered = Address.of("0x" + hash.copyOfRange(12, 32).toHex())
        val halfOrder = java.math.BigInteger("7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0", 16)
        return recovered == address && java.math.BigInteger(signature.s, 16) <= halfOrder
    }

    @Test
    fun aStoredAccountsKeySignsTheDigestAsGivenAndNothingElse() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)

        val signed = store.sign(hardhatAddress, digest, Unlock.WithPassword(password))

        assertIs<Signing.Signed>(signed)
        assertTrue(signs(signed.signature, hardhatAddress), "recovers the account, with a low s")
        // libsecp256k1's nonce is RFC 6979's: the same digest, the same signature.
        assertEquals(signed, store.sign(hardhatAddress, digest, Unlock.WithPassword(password)))
    }

    @Test
    fun aWrongPasswordOrAnAccountWithoutAKeySignsNothing() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)

        assertEquals(Signing.WrongPassword, store.sign(hardhatAddress, digest, notThePassword))
        assertEquals(Signing.NoKey, store.sign(abandonAddress, digest, Unlock.WithPassword(password)))
    }

    @Test
    fun everyRecoveryIdRecoversTheSigner() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)

        // Deterministic signatures: these sixteen digests give both recovery ids, every run.
        val signatures = (1..16).map { n ->
            val each = Digest.of(n.toString(16).padStart(64, '0'))
            each to assertIs<Signing.Signed>(store.sign(hardhatAddress, each, Unlock.WithPassword(password))).signature
        }

        assertEquals(setOf(0, 1), signatures.map { it.second.recoveryId }.toSet())
        signatures.forEach { (each, signature) -> assertTrue(signs(signature, hardhatAddress, each), each.hex) }
    }

    @Test
    fun anEnvelopeWhoseSeedIsAnotherAccountsSignsNothing() = runTest {
        val store = store()
        // Sealed under the abandon account's name, with the hardhat seed inside: it opens, but its
        // key does not derive the account it is named for.
        val passwordBytes = EnvelopeCrypto.passwordBytes(password.text)
        val sealed = Envelope.seal(
            Bip39.seed(hardhat.words),
            abandonAddress.hex,
            passwordBytes,
            hardware = null,
            Envelope.Cost(opsLimit = 2, memLimitBytes = Envelope.MIN_MEMORY_BYTES),
        )
        envelopeOf(abandonAddress).writeText(Json.encodeToString(SealedSeed.serializer(), sealed))
        assertIs<Envelope.Opened.Seed>(Envelope.open(sealed, passwordBytes, null), "only the key check refuses it")

        assertEquals(Signing.Unavailable, store.sign(abandonAddress, digest, Unlock.WithPassword(password)))
    }

    @Test
    fun aLostHardwareKeyIsSaidNotTakenForAWrongPassword() = runTest {
        var provisioned = true
        val store = store(FakeHardware(provisioned = { provisioned }))
        store.importMnemonic(hardhat, password)
        provisioned = false

        assertEquals(Signing.Unavailable, store.sign(hardhatAddress, digest, Unlock.WithPassword(password)))
    }

    @Test
    fun aDamagedEnvelopeSignsNothingAndIsNotTakenForAWrongPassword() = runTest {
        val store = store()
        store.importMnemonic(abandon, password)
        store.importMnemonic(hardhat, password)
        damageTheWrappedKeyOf(hardhatAddress)

        // abandon's envelope opens under the password: the password is right, the file is damaged.
        assertEquals(Signing.Unavailable, store.sign(hardhatAddress, digest, Unlock.WithPassword(password)))
        assertEquals(Signing.WrongPassword, store.sign(hardhatAddress, digest, notThePassword))
        assertIs<Signing.Signed>(store.sign(abandonAddress, digest, Unlock.WithPassword(password)))
    }

    // --- Further accounts of a stored seed.

    // Hardhat's second and third accounts: m/44'/60'/0'/0/1 and /2 of its phrase.
    private val hardhatSecond = Address.of("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
    private val hardhatThird = Address.of("0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC")

    @Test
    fun furtherAccountsAreThoseOtherWalletsShowEachInAnEnvelopeOfItsOwnThatSigns() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)

        assertEquals(
            KeyDerivation.Stored(hardhatSecond, 1, KeyProtection.PASSWORD_ONLY),
            store.deriveAccount(hardhatAddress, password, held = setOf(hardhatAddress)),
        )
        assertEquals(1, sealedIn(envelopeOf(hardhatSecond)).index)
        assertEquals(
            KeyDerivation.Stored(hardhatThird, 2, KeyProtection.PASSWORD_ONLY),
            store.deriveAccount(hardhatSecond, password, held = setOf(hardhatAddress, hardhatSecond)),
            "from any account of the phrase, the lowest index the wallet does not hold",
        )
        assertFalse("index" in envelopeOf(hardhatAddress).readText(), "the first account's envelope is as before")

        val signed = store.sign(hardhatSecond, digest, Unlock.WithPassword(password))
        assertIs<Signing.Signed>(signed)
        assertTrue(signs(signed.signature, hardhatSecond))
        // Each envelope stands alone: the first account's removal leaves the others signing.
        store.delete(hardhatAddress)
        assertIs<Signing.Signed>(store.sign(hardhatThird, digest, Unlock.WithPassword(password)))
    }

    @Test
    fun aStoredEnvelopeWhoseAccountIsNotHeldIsNamedAgainNotSealedAgain() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.deriveAccount(hardhatAddress, password, held = setOf(hardhatAddress))
        val before = envelopeOf(hardhatSecond).readText()

        // Its account never reached the list: the same account comes back, its envelope untouched.
        assertEquals(
            KeyDerivation.Stored(hardhatSecond, 1, KeyProtection.PASSWORD_ONLY),
            store.deriveAccount(hardhatAddress, password, held = setOf(hardhatAddress)),
        )
        assertEquals(before, envelopeOf(hardhatSecond).readText())
    }

    @Test
    fun aDerivationNeedsTheWalletPasswordAndAStoredKey() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)

        assertEquals(
            KeyDerivation.WrongPassword,
            store.deriveAccount(hardhatAddress, WalletPassword("not the password"), setOf(hardhatAddress)),
        )
        assertEquals(KeyDerivation.NoKey, store.deriveAccount(abandonAddress, password, setOf(hardhatAddress)))
        assertEquals(1, directory.list()!!.size, "nothing stored")
    }

    @Test
    fun anIndexEditedOnDiskSignsNothing() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.deriveAccount(hardhatAddress, password, held = setOf(hardhatAddress))
        val sealed = sealedIn(envelopeOf(hardhatSecond))
        val edited = SealedSeed(
            sealed.version, sealed.address, sealed.hardware, sealed.salt, sealed.opsLimit, sealed.memLimitBytes,
            sealed.dekNonce, sealed.dek, sealed.seedNonce, sealed.seed, index = 2,
        )
        envelopeOf(hardhatSecond).writeText(Json.encodeToString(SealedSeed.serializer(), edited))

        assertEquals(Signing.Unavailable, store.sign(hardhatSecond, digest, Unlock.WithPassword(password)))
        assertEquals(KeyDerivation.Unavailable, store.deriveAccount(hardhatSecond, password, setOf(hardhatAddress)))
    }

    @Test
    fun aNewMnemonicIsTwelveValidWordsAndStoresNothing() = runTest {
        val store = store()

        val created = store.newMnemonic()

        assertIs<NewMnemonic.Created>(created)
        assertEquals(12, created.mnemonic.words.size)
        assertEquals(null, Bip39.problemWith(created.mnemonic.words))
        assertEquals("Mnemonic(redacted)", created.mnemonic.toString())
        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())
        assertTrue(created.mnemonic.text != (store.newMnemonic() as NewMnemonic.Created).mnemonic.text)
    }

    /**
     * A fast stand-in for Argon2id (HKDF over the password and the parameters) that cannot have
     * more than [memoryAvailable] bytes, as a device short of free memory: every run is recorded,
     * as the parameters it was asked for, in [asked].
     */
    private class ShortOfMemory(var memoryAvailable: Long) : PasswordStretch {
        val asked = mutableListOf<Pair<Long, Long>>()

        override fun argon2id(
            password: ByteArray,
            salt: ByteArray,
            opsLimit: Long,
            memLimitBytes: Long,
            outLength: Int,
        ): ByteArray {
            asked += opsLimit to memLimitBytes
            if (memLimitBytes > memoryAvailable) throw Argon2idOutOfMemory()
            return Hkdf.sha256(password, salt, "$opsLimit|$memLimitBytes".encodeToByteArray(), outLength)
        }
    }

    private fun TestScope.store(
        stretch: PasswordStretch,
        tier: PasswordOnlyTier = PasswordOnlyTier.FULL,
        hardware: HardwareSecret? = null,
    ) = EnvelopeKeyStore(
        directory.path,
        hardware,
        StandardTestDispatcher(testScheduler),
        KeyProtection.PASSWORD_ONLY,
        biometricKey = null,
        passwordOnlyTier = tier,
        stretch = stretch,
    )

    private val mib = 1024L * 1024

    private fun lessMemory(passwordSet: Boolean) =
        KeyStoreDescription.Ready(passwordSet, KeyProtection.PASSWORD_ONLY, passwordCost = PasswordCost.LESS_MEMORY)

    @Test
    fun aSealShortOfMemoryStepsDownATierAndSaysSo() = runTest {
        // Ruling #36: 256 MiB cannot be had at the moment, 128 MiB can.
        val stretch = ShortOfMemory(memoryAvailable = 128 * mib)
        val store = store(stretch)
        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())

        assertEquals(
            KeyImport.Stored(hardhatAddress, KeyProtection.PASSWORD_ONLY, steppedDown = true),
            store.importMnemonic(hardhat, password),
            "a seal below the device's own tier is told",
        )

        assertEquals(listOf(4L to 256 * mib, 8L to 128 * mib), stretch.asked)
        val sealed = sealedIn(envelopeOf(hardhatAddress))
        assertEquals(8L to 128 * mib, sealed.opsLimit to sealed.memLimitBytes, "the next tier: less memory, more")
        assertEquals(lessMemory(passwordSet = true), store.describe())
        // The lighter envelope opens like any other, at the parameters it was sealed with.
        assertIs<Signing.Signed>(store.sign(hardhatAddress, digest, Unlock.WithPassword(password)))
    }

    @Test
    fun aDeviceWithLittleMemoryStartsAtItsTierAndNeverGoesBelowTheFloor() = runTest {
        val stretch = ShortOfMemory(memoryAvailable = 32 * mib)
        val store = store(stretch, tier = PasswordOnlyTier.REDUCED)
        assertEquals(lessMemory(passwordSet = false), store.describe(), "said before the first seal: the password step")

        assertEquals(KeyImport.NotEnoughMemory(MemoryStep.PROTECT), store.importMnemonic(hardhat, password))

        assertEquals(listOf(8L to 128 * mib, 16L to 64 * mib), stretch.asked, "the device's tier, then the floor's")
        assertEquals(emptyList(), directory.list().orEmpty().filter { it.endsWith(".seed") }, "nothing is added")
        assertEquals(lessMemory(passwordSet = false), store.describe())

        stretch.memoryAvailable = 64 * mib
        assertEquals(
            KeyImport.Stored(hardhatAddress, KeyProtection.PASSWORD_ONLY, steppedDown = true),
            store.importMnemonic(hardhat, password),
        )
        val sealed = sealedIn(envelopeOf(hardhatAddress))
        assertEquals(16L to 64 * mib, sealed.opsLimit to sealed.memLimitBytes)

        // At the device's own tier nothing stepped down; the stored envelope keeps its tier.
        stretch.memoryAvailable = 128 * mib
        assertEquals(passwordOnly(abandonAddress), store.importMnemonic(abandon, password))
        assertEquals(16L to 64 * mib, sealedIn(envelopeOf(hardhatAddress)).let { it.opsLimit to it.memLimitBytes })
    }

    @Test
    fun theHardwarePathKeepsItsCostAndHasNoLighterOne() = runTest {
        val stretch = ShortOfMemory(memoryAvailable = 32 * mib)
        val store = store(stretch, tier = PasswordOnlyTier.LEAST, hardware = FakeHardware())

        assertEquals(KeyImport.NotEnoughMemory(MemoryStep.PROTECT), store.importMnemonic(hardhat, password))
        assertEquals(listOf(3L to 64 * mib), stretch.asked, "64 MiB, three passes: no tier, no step down")

        stretch.memoryAvailable = 64 * mib
        assertEquals(KeyImport.Stored(hardhatAddress, KeyProtection.HARDWARE), store.importMnemonic(hardhat, password))
        assertEquals(
            KeyStoreDescription.Ready(passwordSet = true, KeyProtection.HARDWARE),
            store.describe(),
            "the password-only tiers say nothing about the hardware path",
        )
    }

    @Test
    fun openingShortOfMemoryIsSaidAsSuchNotAsAKeyStoreThatFails() = runTest {
        val stretch = ShortOfMemory(memoryAvailable = 256 * mib)
        val store = store(stretch)
        store.importMnemonic(hardhat, password)

        stretch.memoryAvailable = 128 * mib
        assertEquals(Signing.NotEnoughMemory, store.sign(hardhatAddress, digest, Unlock.WithPassword(password)))
        assertEquals(KeyImport.NotEnoughMemory(MemoryStep.CHECK_PASSWORD), store.importMnemonic(abandon, password))
        assertEquals(listOf("${hardhatAddress.hex}.seed"), directory.list().orEmpty().filter { it.endsWith(".seed") })

        stretch.memoryAvailable = 256 * mib
        assertIs<Signing.Signed>(store.sign(hardhatAddress, digest, Unlock.WithPassword(password)))
    }

    @Test
    fun anAndroidDeviceGetsTheTierItsMemoryAffords() {
        val gb = 1_000_000_000L
        // totalMem as such phones report it: below the nominal size, the kernel's share left out.
        assertEquals(PasswordOnlyTier.FULL, PasswordOnlyTier.forDevice(3_700_000_000L, lowRamDevice = false))
        assertEquals(PasswordOnlyTier.FULL, PasswordOnlyTier.forDevice(12 * gb, lowRamDevice = false))
        assertEquals(PasswordOnlyTier.REDUCED, PasswordOnlyTier.forDevice(3_700_000_000L, lowRamDevice = true))
        assertEquals(PasswordOnlyTier.REDUCED, PasswordOnlyTier.forDevice(2_800_000_000L, lowRamDevice = false))
        assertEquals(PasswordOnlyTier.REDUCED, PasswordOnlyTier.forDevice(1_850_000_000L, lowRamDevice = true))
        assertEquals(PasswordOnlyTier.LEAST, PasswordOnlyTier.forDevice(1_400_000_000L, lowRamDevice = true))
        assertEquals(PasswordOnlyTier.LEAST, PasswordOnlyTier.forDevice(0, lowRamDevice = false))
        assertEquals(
            listOf(PasswordOnlyTier.REDUCED, PasswordOnlyTier.LEAST),
            PasswordOnlyTier.REDUCED.andLighter(),
        )
        // Every tier is within the envelope's floor and ceiling, about the same work.
        PasswordOnlyTier.entries.forEach { assertEquals(1024 * mib, it.cost.opsLimit * it.cost.memLimitBytes) }
    }
}
