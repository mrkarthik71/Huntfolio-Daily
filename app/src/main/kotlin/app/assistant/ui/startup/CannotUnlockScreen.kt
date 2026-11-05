package app.assistant.ui.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.assistant.R
import app.assistant.domain.port.UnlockFailure
import app.assistant.ui.common.DeleteEverythingDialog

/**
 * Shown when the database key cannot be obtained (Requirement 1.5). The data file is untouched;
 * the only action is "Delete everything". Restore from an Export_File is added in Phase 7.
 */
@Composable
fun CannotUnlockScreen(reason: UnlockFailure, onDeleteEverything: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.unlock_error_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.unlock_error_body), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.unlock_error_reason, stringResource(reason.label())),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { confirm = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(stringResource(R.string.delete_everything)) }
        }
    }
    if (confirm) {
        DeleteEverythingDialog(onConfirm = {
            confirm = false
            onDeleteEverything()
        }, onDismiss = { confirm = false })
    }
}

private fun UnlockFailure.label(): Int = when (this) {
    UnlockFailure.KEYSTORE_UNAVAILABLE -> R.string.unlock_reason_keystore
    UnlockFailure.KEY_INVALIDATED -> R.string.unlock_reason_invalidated
    UnlockFailure.KEY_FILE_DAMAGED -> R.string.unlock_reason_damaged
    UnlockFailure.DATABASE_ERROR -> R.string.unlock_reason_database
}
