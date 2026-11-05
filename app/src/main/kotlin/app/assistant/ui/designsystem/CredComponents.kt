package app.assistant.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The standard content card: a raised surface with generous radius and soft elevation. This is the
 * workhorse surface across the app; reach for [HeroCard] only for a screen's single focal element.
 */
@Composable
fun CredCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

/**
 * A focal card with a restrained diagonal sheen — Cred's premium hero treatment. Depth is used
 * here and nowhere else, so it stays a signal rather than decoration (honours the "restraint over
 * flashiness" preference: one subtle gradient on the hero, no glass elsewhere).
 */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(heroSheen(base = scheme.surfaceVariant, highlight = scheme.primaryContainer))
            .padding(20.dp),
    ) {
        Column { content() }
    }
}

/** A quiet, tightly-tracked section label above a group of cards. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(start = 4.dp, top = 8.dp, bottom = 2.dp)) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(contentPadding).semantics { heading() },
    )
}

/** A large, confident screen title in the Cred style, aligned with the hero type. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** A calm, centered empty state: a muted line with room to breathe, never a bare top-left string. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
