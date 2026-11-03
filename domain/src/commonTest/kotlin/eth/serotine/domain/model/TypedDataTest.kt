package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TypedDataTest {

    private fun text(value: String) = TypedValue.Text(value)

    private val domainType = listOf(
        TypedField("name", "string"),
        TypedField("version", "string"),
        TypedField("chainId", "uint256"),
        TypedField("verifyingContract", "address"),
    )

    // EIP-712's own example.
    private val mail = TypedData(
        types = mapOf(
            "EIP712Domain" to domainType,
            "Person" to listOf(TypedField("name", "string"), TypedField("wallet", "address")),
            "Mail" to listOf(TypedField("from", "Person"), TypedField("to", "Person"), TypedField("contents", "string")),
        ),
        primaryType = "Mail",
        domain = mapOf(
            "name" to text("Ether Mail"),
            "version" to text("1"),
            "chainId" to TypedValue.Number("1"),
            "verifyingContract" to text("0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"),
        ),
        message = mapOf(
            "from" to TypedValue.Struct(
                mapOf("name" to text("Cow"), "wallet" to text("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826")),
            ),
            "to" to TypedValue.Struct(
                mapOf("name" to text("Bob"), "wallet" to text("0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB")),
            ),
            "contents" to text("Hello, Bob!"),
        ),
    )

    @Test
    fun theEipsExampleHashesToItsDigest() {
        val encoded = assertNotNull(Eip712.encode(mail))
        assertEquals("Mail(Person from,Person to,string contents)Person(string name,address wallet)", Eip712.encodeType(mail.types, "Mail"))
        assertEquals("f2cee375fa42b42143804025fc449deafd50cc031ca257e0b194a650a912090f", encoded.domainSeparator)
        assertEquals("c52c0ee5d84264471806290a3f2c4cecfc5490626bf912d01f240d7a274b371e", encoded.messageHash)
        assertEquals(Digest.of("be609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2"), encoded.digest)
    }

    @Test
    fun numbersAreTakenAsDecimalOrHexStringsToo() {
        val decimal = mail.copy(domain = mail.domain + ("chainId" to text("1")))
        val hex = mail.copy(domain = mail.domain + ("chainId" to text("0x1")))
        assertEquals(Eip712.encode(mail)?.digest, Eip712.encode(decimal)?.digest)
        assertEquals(Eip712.encode(mail)?.digest, Eip712.encode(hex)?.digest)
    }

    @Test
    fun anythingNotExactlyEip712IsRefused() {
        fun refused(data: TypedData) = assertNull(Eip712.encode(data), data.toString())
        val from = mail.message.getValue("from") as TypedValue.Struct
        // A member missing, or one the type does not have (it would be shown and not signed).
        refused(mail.copy(message = mail.message - "contents"))
        refused(mail.copy(message = mail.message + ("extra" to text("x"))))
        refused(mail.copy(message = mail.message + ("from" to TypedValue.Struct(from.fields + ("age" to TypedValue.Number("3"))))))
        // Values of the wrong shape.
        refused(mail.copy(domain = mail.domain + ("chainId" to TypedValue.Number("-1"))))
        refused(mail.copy(domain = mail.domain + ("chainId" to text("1.0"))))
        refused(mail.copy(domain = mail.domain + ("chainId" to TypedValue.Bool(true))))
        refused(mail.copy(domain = mail.domain + ("verifyingContract" to text("0xCcCC"))))
        refused(mail.copy(message = mail.message + ("contents" to TypedValue.Number("1"))))
        // Types that are not EIP-712's.
        refused(mail.copy(types = mail.types - "EIP712Domain"))
        refused(mail.copy(primaryType = "EIP712Domain"))
        refused(mail.copy(primaryType = "Letter"))
        refused(mail.copy(types = mail.types + ("Mail" to listOf(TypedField("from", "Human")))))
        refused(mail.copy(types = mail.types + ("Mail" to listOf(TypedField("from", "uint7")))))
        refused(mail.copy(types = mail.types + ("Mail" to listOf(TypedField("from", "Person"), TypedField("from", "Person")))))
    }

    @Test
    fun theDomainsMembersAreEip712sOwnEachOfItsOwnType() {
        fun domain(vararg fields: TypedField, value: TypedValue) = mail.copy(
            types = mail.types + ("EIP712Domain" to fields.toList()),
            domain = mapOf(fields.single().name to value),
        )
        assertNotNull(Eip712.encode(domain(TypedField("salt", "bytes32"), value = text("0x" + "00".repeat(32)))))
        assertNull(Eip712.encode(domain(TypedField("chainId", "string"), value = text("mainnet"))))
        assertNull(Eip712.encode(domain(TypedField("chainId", "int256"), value = text("-11155111"))))
        assertNull(Eip712.encode(domain(TypedField("verifyingContract", "uint256"), value = TypedValue.Number("1"))))
        assertNull(Eip712.encode(domain(TypedField("owner", "address"), value = text("0x" + "0".repeat(40)))))
    }

    @Test
    fun typesAndValuesStayWithinTheirLimits() {
        // Array dimensions, counted on the type's own string, before anything walks it.
        val deep = mail.copy(types = mail.types + ("Mail" to listOf(TypedField("from", "uint256" + "[]".repeat(30_000)))))
        assertNull(Eip712.encode(deep))
        val five = mail.copy(types = mail.types + ("Mail" to listOf(TypedField("from", "uint256[][][][][]"))))
        assertNull(Eip712.encode(five))
        assertNull(Eip712.encode(mail.copy(types = mail.types + ("M".repeat(65) to emptyList()))))
        // Values: at most MAX_VALUES in all.
        val many = TypedData(
            mapOf("EIP712Domain" to emptyList(), "V" to listOf(TypedField("v", "uint8[]"))),
            "V",
            emptyMap(),
            mapOf("v" to TypedValue.Items(List(Eip712.MAX_ITEMS) { TypedValue.Number("1") })),
        )
        assertNotNull(Eip712.encode(many))
        val nested = TypedData(
            mapOf("EIP712Domain" to emptyList(), "V" to listOf(TypedField("v", "uint8[][]"))),
            "V",
            emptyMap(),
            mapOf("v" to TypedValue.Items(List(5) { TypedValue.Items(List(Eip712.MAX_ITEMS) { TypedValue.Number("1") }) })),
        )
        assertNull(Eip712.encode(nested))
    }

    @Test
    fun integersStayInsideTheirTypesRange() {
        fun with(type: String, value: TypedValue) = TypedData(
            mapOf("EIP712Domain" to emptyList(), "V" to listOf(TypedField("v", type))),
            "V",
            emptyMap(),
            mapOf("v" to value),
        )
        assertNotNull(Eip712.encode(with("uint8", TypedValue.Number("255"))))
        assertNull(Eip712.encode(with("uint8", TypedValue.Number("256"))))
        assertNotNull(Eip712.encode(with("int8", TypedValue.Number("-128"))))
        assertNull(Eip712.encode(with("int8", TypedValue.Number("-129"))))
        assertNull(Eip712.encode(with("int8", TypedValue.Number("128"))))
        assertNull(Eip712.encode(with("uint256", text("0x" + "1".repeat(65)))))
        assertNull(Eip712.encode(with("bytes4", text("0xdeadbe"))))
        assertNull(Eip712.encode(with("uint8[2]", TypedValue.Items(listOf(TypedValue.Number("1"))))))
    }
}
