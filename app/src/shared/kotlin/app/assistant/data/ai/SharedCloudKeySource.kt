package app.assistant.data.ai

import app.assistant.ai.cloud.CloudCredential
import app.assistant.ai.cloud.CloudKeySource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared flavour: there is NO Cloud AI key on the device (Requirement 45.2). Every Cloud call goes
 * through the Proxy_Server, so the credential is always [CloudCredential.Proxy]. Because a credential
 * is always returned, `cloudConfigured` depends only on the cap (and the user's consent, handled in
 * the consent flow), never on a key the shared build does not have.
 */
@Singleton
class SharedCloudKeySource @Inject constructor() : CloudKeySource {
    override suspend fun credential(): CloudCredential = CloudCredential.Proxy
}
