package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SignInMessageTest {

    // EIP-4361's own example.
    private val example = """
        example.com wants you to sign in with your Ethereum account:
        0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2

        I accept the ExampleOrg Terms of Service: https://example.com/tos

        URI: https://example.com/login
        Version: 1
        Chain ID: 1
        Nonce: 32891756
        Issued At: 2021-09-30T16:25:24Z
        Resources:
        - ipfs://bafybeiemxf5abjwjbikoz4mc3a3dla6ual3jsgpdr4cjr3oz3evfyavhwq/
        - https://example.com/my-web2-claim.json
    """.trimIndent()

    @Test
    fun theEipsExampleIsReadFieldByField() {
        val message = assertNotNull(SignInMessage.parse(example))
        assertEquals(null, message.scheme)
        assertEquals("example.com", message.domain)
        assertEquals(Address.of("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"), message.address)
        assertEquals("I accept the ExampleOrg Terms of Service: https://example.com/tos", message.statement)
        assertEquals("https://example.com/login", message.uri)
        assertEquals(1L, message.chainId)
        assertEquals("32891756", message.nonce)
        assertEquals(1_633_019_124L, message.issuedAtSeconds)
        assertEquals(null, message.expiresAtSeconds)
        assertEquals(2, message.resources.size)
    }

    @Test
    fun theOptionalFieldsAndASchemeAreRead() {
        val message = assertNotNull(
            SignInMessage.parse(
                """
                https://app.example.org:8443 wants you to sign in with your Ethereum account:
                0x0000000000000000000000000000000000000001


                URI: https://app.example.org:8443/
                Version: 1
                Chain ID: 11155111
                Nonce: abcdEFGH1234
                Issued At: 2026-11-03T10:00:00.123+01:00
                Expiration Time: 2026-11-03T10:10:00Z
                Not Before: 2026-11-03T09:59:00Z
                Request ID: req-7
                """.trimIndent(),
            ),
        )
        assertEquals("https", message.scheme)
        assertEquals("app.example.org:8443", message.domain)
        assertEquals(null, message.statement)
        assertEquals(11_155_111L, message.chainId)
        assertEquals(1_793_696_400L, message.issuedAtSeconds)
        assertEquals(1_793_700_600L, message.expiresAtSeconds)
        assertEquals(1_793_699_940L, message.notBeforeSeconds)
        assertEquals("req-7", message.requestId)
    }

    @Test
    fun withoutAStatementOneEmptyLineIsReadToo() {
        // The reference library's form: one empty line where the grammar has two.
        val message = SignInMessage.parse(
            """
            example.com wants you to sign in with your Ethereum account:
            0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2

            URI: https://example.com/login
            Version: 1
            Chain ID: 1
            Nonce: 32891756
            Issued At: 2021-09-30T16:25:24Z
            """.trimIndent(),
        )
        assertEquals(null, assertNotNull(message).statement)
    }

    @Test
    fun anythingNotExactlyASignInIsNone() {
        fun broken(from: String, to: String) = assertNull(SignInMessage.parse(example.replace(from, to)), "$from → $to")
        broken("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "0xc02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2") // checksum
        broken("Version: 1", "Version: 2")
        broken("Nonce: 32891756", "Nonce: 1234567")
        broken("Nonce: 32891756", "Nonce: 3289-1756")
        broken("Chain ID: 1", "Chain ID: 0x1")
        broken("Issued At: 2021-09-30T16:25:24Z", "Issued At: 2021-09-30 16:25:24")
        broken("Issued At: 2021-09-30T16:25:24Z", "Issued At: 2021-13-30T16:25:24Z")
        broken("URI: https://example.com/login", "URI: example.com/login")
        broken("example.com wants", "example.com/path wants")
        // A user part, or a character that only looks like one of the host's.
        broken("example.com wants", "app.example.org@example.com wants")
        broken("example.com wants", "exam\u00A0ple.com wants")
        broken("example.com wants", "\u0435xample.com wants")
        broken("wants you to sign in", "wants you to log in")
        broken("- https://example.com/my-web2-claim.json", "https://example.com/my-web2-claim.json")
        broken("Chain ID: 1\n", "")
        assertNull(SignInMessage.parse("$example\n"))
        assertNull(SignInMessage.parse(example.replace("\n", "\r\n")))
        assertNull(SignInMessage.parse("hello world"))
    }
}
