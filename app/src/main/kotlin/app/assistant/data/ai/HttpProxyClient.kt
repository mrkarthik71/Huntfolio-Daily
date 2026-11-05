package app.assistant.data.ai

import app.assistant.ai.cloud.ProxyClient
import app.assistant.data.db.DatabaseHolder
import app.assistant.domain.error.Outcome
import app.assistant.domain.port.AiFailure
import app.assistant.security.keys.KeyManager
import app.assistant.security.network.EgressPolicy
import app.assistant.security.network.HttpClientFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The shared-build Proxy_Server client (Requirement 45.2, 66.3). It forwards an already-redacted
 * request body to the configured proxy host (on the egress allow-list) with an invite token read
 * from the `secret` table. The SERVER side is blocked on ADR 0023, so only the client is built here;
 * until a proxy host + token are configured it fails closed (NOT_CONFIGURED), so the shared build
 * never contacts an unset endpoint.
 *
 * Optional certificate pinning with a backup pin is applied by the OkHttp client when the host and
 * pins are configured; the egress interceptor still runs first.
 */
class HttpProxyClient(
    private val endpointUrl: String,
    private val holder: DatabaseHolder,
    private val keyManager: KeyManager,
    policy: EgressPolicy,
) : ProxyClient {
    private val client = HttpClientFactory.create(policy)

    override suspend fun forward(model: String?, body: String): Outcome<String, AiFailure> {
        if (endpointUrl.isBlank()) return Outcome.Err(AiFailure.NOT_CONFIGURED)
        val token = inviteToken() ?: return Outcome.Err(AiFailure.NOT_CONFIGURED)
        return try {
            val request = Request.Builder()
                .url(endpointUrl)
                .header("Authorization", "Bearer $token")
                .apply { if (model != null) header("X-Model", model) }
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Outcome.Ok(response.body?.string().orEmpty())
                    response.code == TOO_MANY_REQUESTS -> Outcome.Err(AiFailure.CAP_REACHED)
                    else -> Outcome.Err(AiFailure.TRANSPORT)
                }
            }
        } catch (_: Exception) {
            Outcome.Err(AiFailure.TRANSPORT)
        }
    }

    private suspend fun inviteToken(): String? {
        val secret = holder.database().secretDao().get(INVITE_SECRET) ?: return null
        return when (val plain = keyManager.decryptSecret(secret.blob)) {
            is Outcome.Ok -> String(plain.value, Charsets.UTF_8).also { plain.value.fill(0) }.ifBlank { null }
            is Outcome.Err -> null
        }
    }

    private companion object {
        const val INVITE_SECRET = "proxy_invite_token"
        const val TOO_MANY_REQUESTS = 429
    }
}
