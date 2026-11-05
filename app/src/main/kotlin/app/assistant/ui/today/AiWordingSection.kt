package app.assistant.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.assistant.ui.designsystem.CredCard

/**
 * The AI wording block (Requirement 48.1 to 48.4): shown only after the rule-based plan, below Quick
 * Add. AI text replaces nothing destructively — the plan itself is unchanged; this is an overlay the
 * user reads. AI-proposed changes appear as suggestions, and a failed-but-attempted request shows
 * the "AI wording unavailable" line. When AI is off there is simply no summary and no indication.
 */
internal fun LazyListScope.aiWordingSection(ui: TodayUi) {
    if (ui.aiSummary == null && ui.aiSuggestions.isEmpty() && !ui.aiUnavailable) return

    item {
        CredCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.aiSummary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                ui.aiSuggestions.forEach { s -> AiSuggestion(s) }
                if (ui.aiUnavailable) {
                    Text(
                        "AI wording unavailable — showing your plan as computed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiSuggestion(s: AiSuggestionRow) {
    Column(Modifier.fillMaxWidth()) {
        Text(s.text, style = MaterialTheme.typography.titleSmall)
        if (s.reason.isNotBlank()) {
            Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
