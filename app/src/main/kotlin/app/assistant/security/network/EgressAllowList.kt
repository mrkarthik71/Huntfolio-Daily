package app.assistant.security.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The set of hosts the app may contact (Requirement 1.7): the configured AI vendor host,
 * the Proxy_Server host and the configured mail host. It starts empty, so a build with no
 * AI or mail connected makes no network request at all.
 */
fun interface EgressPolicy {
    fun allowedHosts(): Set<String>
}

/** Thrown before any connection is made, so a blocked request never leaves the phone. */
class EgressBlockedException : IOException("Host is not on the egress allow-list")

/**
 * Rejects any request whose host is not exactly on the allow-list (case-insensitive) and every
 * IP-literal host. Hosts found in Message_Text are never added to the list (Requirement 4.2).
 */
class EgressAllowListInterceptor(private val policy: EgressPolicy) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val host = url.host.lowercase(Locale.ROOT)
        val allowed = policy.allowedHosts().mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (url.scheme != "https" || isIpLiteral(host) || host !in allowed) {
            throw EgressBlockedException()
        }
        return chain.proceed(chain.request())
    }

    private fun isIpLiteral(host: String): Boolean = host.contains(':') || IPV4.matches(host)

    private companion object {
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}

/** The single OkHttpClient factory used by every network adapter. */
object HttpClientFactory {
    fun create(policy: EgressPolicy, timeoutSeconds: Long = 30): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(EgressAllowListInterceptor(policy))
        // Redirects could lead to another host after the allow-list check; the interceptor runs
        // again on each follow-up, but turning redirects off keeps behaviour obvious.
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}
