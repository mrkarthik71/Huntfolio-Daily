package app.assistant.ai.cloud

import app.assistant.domain.port.ProxyClient
import app.assistant.domain.port.CloudKeySource
import app.assistant.domain.error.Outcome
import app.assistant.domain.port.AiFailure
import app.assistant.domain.port.ProviderRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The OpenAI-compatible Cloud vendor (Open decision 2, resolved: OpenAI-compatible wire format with
 * a ₹200/month cap). It targets the `/v1/chat/completions` shape used by OpenAI and the many
 * compatible gateways, so the host is configurable without changing the request mapping. The vendor
 * host must be on the egress allow-list; the request carries only the redacted payload + prompt, and
 * the response is parsed strictly for the single assistant message, which the gateway then validates.
 */
class OpenAiCompatibleProvider(
    keySource: CloudKeySource,
    transport: CloudTransport,
    proxy: ProxyClient?,
    private val defaultModel: String,
) : CloudAiProvider(keySource, transport, proxy) {

    override fun buildRequestBody(request: ProviderRequest): String {
        val body = ChatRequest(
            model = request.model ?: defaultModel,
            messages = listOf(
                ChatMessage(role = "system", content = request.prompt),
                ChatMessage(role = "user", content = renderPayload(request)),
            ),
            temperature = 0.0,
            responseFormat = ResponseFormat(type = "json_object"),
        )
        return JSON.encodeToString(ChatRequest.serializer(), body)
    }

    override fun extractText(responseJson: String): String =
        runCatching { JSON.decodeFromString(ChatResponse.serializer(), responseJson) }
            .getOrNull()
            ?.choices
            ?.firstOrNull()
            ?.message
            ?.content
            .orEmpty()

    private fun renderPayload(request: ProviderRequest): String {
        val p = request.payload
        return buildString {
            p.fields.forEach { (k, v) -> append(k).append(": ").append(v.value).append('\n') }
            p.lists.forEach { (k, v) -> append(k).append(":\n"); v.forEach { append("  - ").append(it.value).append('\n') } }
        }
    }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double,
        @SerialName("response_format") val responseFormat: ResponseFormat,
    )

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ResponseFormat(val type: String)

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: ChatMessage)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/**
 * OkHttp transport for a direct OpenAI-compatible call. It uses the shared egress-allow-listed
 * client, so a request to any host but the configured vendor is refused before it leaves the phone.
 * A non-2xx reply, a transport error or a timeout maps to the matching [AiFailure].
 */
class OkHttpCloudTransport(
    private val endpointUrl: String,
    private val call: suspend (url: String, body: String, apiKey: String) -> HttpReply,
) : CloudTransport {
    override suspend fun post(model: String?, body: String, apiKey: String): Outcome<String, AiFailure> =
        try {
            val reply = call(endpointUrl, body, apiKey)
            when {
                reply.code in SUCCESS -> Outcome.Ok(reply.body)
                reply.code == TOO_MANY_REQUESTS -> Outcome.Err(AiFailure.CAP_REACHED)
                else -> Outcome.Err(AiFailure.TRANSPORT)
            }
        } catch (_: Exception) {
            Outcome.Err(AiFailure.TRANSPORT)
        }

    data class HttpReply(val code: Int, val body: String)

    private companion object {
        val SUCCESS = 200..299
        const val TOO_MANY_REQUESTS = 429
    }
}
