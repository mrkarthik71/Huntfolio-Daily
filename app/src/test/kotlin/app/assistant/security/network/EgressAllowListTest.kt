package app.assistant.security.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Requirements 1.7 and 4.2. No test touches the real network. */
class EgressAllowListTest {

    private var reachedNetwork = false

    /** Stands in for the network: records that a request got past the allow-list. */
    private val fakeNetwork = Interceptor { chain ->
        reachedNetwork = true
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()
    }

    private fun client(vararg hosts: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(EgressAllowListInterceptor { hosts.toSet() })
        .addInterceptor(fakeNetwork)
        .build()

    private fun call(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun `allowed host passes, ignoring letter case`() {
        assertEquals(200, call(client("api.example-ai.com"), "https://API.Example-AI.com/v1/generate"))
    }

    @Test
    fun `unknown host is blocked before the network`() {
        assertThrows<EgressBlockedException> { call(client("api.example-ai.com"), "https://evil.example.net/") }
        assertFalse(reachedNetwork)
    }

    @Test
    fun `subdomain of an allowed host is blocked`() {
        assertThrows<EgressBlockedException> { call(client("example-ai.com"), "https://api.example-ai.com/") }
    }

    @Test
    fun `ip literals are blocked even when listed`() {
        assertThrows<EgressBlockedException> { call(client("192.168.1.10"), "https://192.168.1.10/") }
        assertThrows<EgressBlockedException> { call(client("[::1]", "::1"), "https://[::1]/") }
    }

    @Test
    fun `plain http is blocked`() {
        assertThrows<EgressBlockedException> { call(client("api.example-ai.com"), "http://api.example-ai.com/") }
    }

    @Test
    fun `a host taken from message text is blocked`() {
        val smsText = "Your KYC expires, update at https://kyc-update.example.in/verify now"
        val linkInMessage = Regex("""https://\S+""").find(smsText)!!.value
        assertThrows<EgressBlockedException> { call(client("api.example-ai.com"), linkInMessage) }
        assertFalse(reachedNetwork)
    }

    @Test
    fun `empty allow-list blocks everything`() {
        assertThrows<EgressBlockedException> { call(client(), "https://api.example-ai.com/") }
    }
}
