package app.assistant.ai.cloud

import app.assistant.domain.port.ProxyClient
import app.assistant.domain.port.CloudKeySource
import app.assistant.domain.port.CloudCredential
import app.assistant.domain.error.Outcome
import app.assistant.domain.port.AiFailure
import app.assistant.domain.port.AiProvider
import app.assistant.domain.port.ProviderRequest
import app.assistant.domain.model.AiMode




/**
 * Vendor-neutral Cloud provider base (design: Cloud adapter). Concrete vendors implement
 * [buildRequestBody] and [extractText]; the base handles mode, credential selection and mapping a
 * transport error to an [AiFailure]. It NEVER inspects the payload beyond serialising it, and it
 * only ever contacts the one allow-listed host configured on its [transport].
 */
abstract class CloudAiProvider(
    private val keySource: CloudKeySource,
    private val transport: CloudTransport,
    private val proxy: ProxyClient?,
) : AiProvider {
    override val mode: AiMode = AiMode.CLOUD

    override suspend fun complete(request: ProviderRequest): Outcome<String, AiFailure> {
        val credential = keySource.credential() ?: return Outcome.Err(AiFailure.NOT_CONFIGURED)
        val body = buildRequestBody(request)
        val raw = when (credential) {
            is CloudCredential.Direct -> transport.post(request.model, body, credential.apiKey)
            is CloudCredential.Proxy -> (proxy ?: return Outcome.Err(AiFailure.NOT_CONFIGURED)).forward(request.model, body)
        }
        return when (raw) {
            is Outcome.Ok -> Outcome.Ok(extractText(raw.value))
            is Outcome.Err -> raw
        }
    }

    /** Builds the vendor's request JSON from the redacted payload + prompt. No personal text is added. */
    protected abstract fun buildRequestBody(request: ProviderRequest): String

    /** Pulls the model's text content out of the vendor's response envelope. */
    protected abstract fun extractText(responseJson: String): String
}

/**
 * The HTTP leg of a direct Cloud call, over the shared egress-allow-listed OkHttp client. Keeping it
 * behind an interface lets tests use a recording client with zero real network (task 29.10).
 */
interface CloudTransport {
    /** POSTs [body] to the configured vendor endpoint with a bearer [apiKey]; returns raw response text. */
    suspend fun post(model: String?, body: String, apiKey: String): Outcome<String, AiFailure>
}
