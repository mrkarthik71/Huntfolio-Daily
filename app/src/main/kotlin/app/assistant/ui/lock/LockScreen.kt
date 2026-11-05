package app.assistant.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.assistant.R
import app.assistant.security.lock.LockState

/** Shows no user data (Requirement 6.1, 6.3). Prompts automatically when first shown. */
@Composable
fun LockScreen(state: LockState, onUnlock: () -> Unit, onOpenSecuritySettings: () -> Unit) {
    LaunchedEffect(Unit) { if (state == LockState.LOCKED) onUnlock() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null)
            if (state == LockState.NO_DEVICE_LOCK) {
                Text(stringResource(R.string.lock_no_device_lock_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.lock_no_device_lock_body), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onOpenSecuritySettings, modifier = Modifier.widthIn(min = 48.dp)) {
                    Text(stringResource(R.string.lock_open_security_settings))
                }
                OutlinedButton(onClick = onUnlock) { Text(stringResource(R.string.lock_try_again)) }
            } else {
                Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.lock_body), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onUnlock, enabled = state != LockState.UNLOCKING) {
                    Text(stringResource(R.string.lock_unlock))
                }
            }
        }
    }
}
