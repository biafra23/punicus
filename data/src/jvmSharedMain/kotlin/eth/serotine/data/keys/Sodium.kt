package eth.serotine.data.keys

import com.sun.jna.IntegerType
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * libsodium's `crypto_pwhash`, mapped with native buffers. The bindings' own API takes the
 * password as a String and passes its UTF-16 length as the byte count, while JNA encodes
 * the String to UTF-8: a non-ASCII password would be hashed only up to that many bytes,
 * and its bytes could not be zeroed. So the envelope maps the two functions it needs
 * itself, over the same libsodium binaries the bindings ship: on Android the APK's
 * `libsodium.so`, on the desktop the library for this OS and architecture, copied out of
 * the bindings' jar once per process. The buffers are the caller's, so it can zero them
 * ([EnvelopeCrypto.argon2id]).
 */
internal object Sodium {

    @Suppress("FunctionName") // libsodium's own names
    interface Library : com.sun.jna.Library {
        fun sodium_init(): Int

        fun crypto_pwhash_alg_argon2id13(): Int

        fun crypto_pwhash(
            out: Pointer,
            outlen: Long, // unsigned long long
            passwd: Pointer,
            passwdlen: Long, // unsigned long long
            salt: Pointer,
            opslimit: Long, // unsigned long long
            memlimit: SizeT,
            alg: Int,
        ): Int

        // WalletConnect's primitives (eth.serotine.data.walletconnect.WcCrypto). The lengths are
        // unsigned long long, the length outputs pointers to one, passed as null where unused.

        fun crypto_scalarmult_curve25519_base(q: Pointer, n: Pointer): Int

        fun crypto_scalarmult_curve25519(q: Pointer, n: Pointer, p: Pointer): Int

        fun crypto_aead_chacha20poly1305_ietf_encrypt(
            c: Pointer,
            clen: Pointer?,
            m: Pointer?,
            mlen: Long,
            ad: Pointer?,
            adlen: Long,
            nsec: Pointer?,
            npub: Pointer,
            k: Pointer,
        ): Int

        fun crypto_aead_chacha20poly1305_ietf_decrypt(
            m: Pointer?,
            mlen: Pointer?,
            nsec: Pointer?,
            c: Pointer,
            clen: Long,
            ad: Pointer?,
            adlen: Long,
            npub: Pointer,
            k: Pointer,
        ): Int

        fun crypto_sign_ed25519_seed_keypair(pk: Pointer, sk: Pointer, seed: Pointer): Int

        fun crypto_sign_ed25519_detached(sig: Pointer, siglen: Pointer?, m: Pointer?, mlen: Long, sk: Pointer): Int

        fun crypto_sign_ed25519_verify_detached(sig: Pointer, m: Pointer?, mlen: Long, pk: Pointer): Int
    }

    /** `size_t`, whatever its width on this platform (32 bits on 32-bit Android ABIs, 64 on Windows x64). */
    class SizeT(value: Long = 0) : IntegerType(Native.SIZE_T_SIZE, value, true) {
        override fun toByte(): Byte = toLong().toByte()
        override fun toShort(): Short = toLong().toShort()

        @Deprecated("Number.toChar() is deprecated", ReplaceWith("toInt().toChar()"))
        override fun toChar(): Char = toInt().toChar()
    }

    /** The library, or null when it cannot be loaded here; loaded once. */
    val library: Library? by lazy {
        try {
            val loaded = Native.load(libraryName(), Library::class.java)
            // 0: initialized now, 1: already initialized; -1: failed.
            if (loaded.sodium_init() < 0) null else loaded
        } catch (e: Throwable) {
            println("Serotine: libsodium cannot be loaded (${e::class.simpleName})")
            null
        }
    }

    private fun libraryName(): String {
        if (isAndroid()) return "sodium"
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val resource = desktopResource(os, arch)
        val stream = Sodium::class.java.classLoader?.getResourceAsStream(resource)
            ?: error("no libsodium for $os/$arch")
        val file = File.createTempFile("serotine-libsodium", resource.substringAfterLast('.').let { ".$it" })
        file.deleteOnExit()
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.absolutePath
    }

    /**
     * The name of the libsodium binary the bindings' jar ships for [os] and [arch] (both lower
     * case, as the JVM reports them): a universal dylib for macOS, x64 for Windows, x64 and
     * arm64 for Linux. Windows on ARM gets the x64 name, which an arm64 JVM cannot load, so the
     * key store is unavailable there rather than wrong.
     */
    internal fun desktopResource(os: String, arch: String): String = when {
        "mac" in os -> "libdynamic-macos.dylib"
        "win" in os -> "libdynamic-msvc-x86-64-libsodium.dll"
        arch == "aarch64" || arch == "arm64" -> "libdynamic-linux-arm64-libsodium.so"
        else -> "libdynamic-linux-x86-64-libsodium.so"
    }

    private fun isAndroid(): Boolean =
        System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true) ||
            System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)
}
