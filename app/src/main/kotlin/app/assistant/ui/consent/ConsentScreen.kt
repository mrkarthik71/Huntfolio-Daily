package app.assistant.ui.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.assistant.R

/**
 * The Consent_Screen shown before anything else in the shared build (Requirement 66.1). Accepting
 * records the version and lets startup continue; there is no decline button that proceeds — closing
 * the app without accepting simply shows this again next launch (Requirement 66.5).
 */
@Composable
fun ConsentScreen(onAccept: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.privacy_policy), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.consent_accept))
        }
    }
}
