package app.assistant.data.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.assistant.domain.port.Connectivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** [Connectivity] over the system ConnectivityManager: true when a validated internet transport exists. */
@Singleton
class AndroidConnectivity @Inject constructor(@ApplicationContext private val context: Context) : Connectivity {
    override fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
