package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppIsolationTest {

    private fun host(text: String): AppHost = (AppHost.parse(text) as AppHostResult.Host).host

    private fun ofUrl(url: String): String? = (AppHost.ofUrl(url) as? AppHostResult.Host)?.host?.name

    @Test
    fun theHostOfAStatedUrlIsLowerCasedWithoutPortUserPathOrTrailingDot() {
        assertEquals("app.uniswap.org", ofUrl("https://app.uniswap.org"))
        assertEquals("app.uniswap.org", ofUrl("HTTPS://App.Uniswap.ORG/#/swap?x=1"))
        assertEquals("app.uniswap.org", ofUrl("https://app.uniswap.org:8443/path"))
        assertEquals("app.uniswap.org", ofUrl("https://app.uniswap.org./"))
        assertEquals("localhost", ofUrl("http://localhost:3000"))
        // The user part is dropped: the host is what comes after the last '@', the site actually reached.
        assertEquals("evil.test", ofUrl("https://app.uniswap.org@evil.test/"))
        // The ASCII form of an internationalised name is a name like any other.
        assertEquals("xn--mnchen-3ya.de", ofUrl("https://xn--mnchen-3ya.de"))
    }

    @Test
    fun aUrlWithoutAWebSchemeOrAPlainNameHasNoHost() {
        assertEquals(AppHostResult.NotAWebAddress, AppHost.ofUrl("app.uniswap.org"))
        assertEquals(AppHostResult.NotAWebAddress, AppHost.ofUrl("ipfs://bafy"))
        assertEquals(AppHostResult.NotAWebAddress, AppHost.ofUrl(""))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://[::1]:8080"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://example.com:"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://example.com:80x"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://exa_mple.com"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://-example.com"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://example..com"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://exa%6dple.com"))
        assertEquals(AppHostResult.NotAscii, AppHost.ofUrl("https://münchen.de"))
        // A Cyrillic 'а' that looks like a Latin one is refused, not mapped.
        assertEquals(AppHostResult.NotAscii, AppHost.ofUrl("https://аpp.uniswap.org"))
    }

    @Test
    fun aTypedHostIsReadTheSameWay() {
        assertEquals(host("app.safe.global"), host(" App.Safe.Global. "))
        assertEquals(AppHostResult.NotAName, AppHost.parse("app.safe.global:443"))
        assertEquals(AppHostResult.NotAName, AppHost.parse("a".repeat(64) + ".com"))
        assertEquals(AppHostResult.NotAName, AppHost.parse(("a".repeat(63) + ".").repeat(4) + "com"))
    }

    // The fixed vectors of docs/keys-and-signing.md, *Per-app addresses*: host → the first four bytes of
    // keccak256("serotine/app-isolation/v1" ‖ host) → a. Computed independently of this code.
    @Test
    fun theAccountIsTheFirst31BitsOfTheTaggedHash() {
        assertEquals(197_617_227, AppIsolation.accountOf(host("example.com"))) // 178ecc97
        assertEquals(1_904_035_894, AppIsolation.accountOf(host("app.uniswap.org"))) // e2fa906d
        assertEquals(605_689_155, AppIsolation.accountOf(host("walletbeat.eth.limo"))) // 48342a87
        assertEquals(630_679_082, AppIsolation.accountOf(host("app.safe.global"))) // 4b2ecc55
        assertEquals(507_329_819, AppIsolation.accountOf(host("localhost"))) // 3c7a7a37
        assertEquals("m/44'/60'/197617227'/0/0", AppIsolation.pathOf(host("example.com")))
    }

    @Test
    fun anotherHostWithTheSameAccountIsACollisionAndTheHostItselfIsNot() {
        // Two hosts whose a collides, found by a birthday search over "h<n>.test": both 1424491982.
        val first = host("h34064.test")
        val second = host("h38875.test")
        assertEquals(AppIsolation.accountOf(first), AppIsolation.accountOf(second))
        assertEquals(first, AppIsolation.collision(second, listOf(host("example.com"), first)))
        val example = host("example.com")
        assertNull(AppIsolation.collision(example, listOf(example, host("app.uniswap.org"))))
        assertNull(AppIsolation.collision(example, emptyList()))
    }
}
