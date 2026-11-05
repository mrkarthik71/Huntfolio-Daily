package app.assistant.data.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only class that calls `Instant.now()` and `ZoneId.systemDefault()`.
 * Architecture tests (task 5.3) fail the build if any other class does.
 */
@Singleton
class SystemTimeSource @Inject constructor(@param:ApplicationContext private val context: Context) : TimeSource {

    override fun now(): Instant = Instant.now()

    override fun zone(): ZoneId = ZoneId.systemDefault()

    override val zoneChanges: Flow<ZoneId> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(ZoneId.systemDefault())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
}
