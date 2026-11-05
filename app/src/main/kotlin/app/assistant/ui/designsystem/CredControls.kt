package app.assistant.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One destination in the [CredBottomBar]. */
data class CredBarItem(val key: String, val label: String, val selectedIcon: ImageVector, val icon: ImageVector)

/**
 * A floating, pill-shaped bottom bar in the Cred style: a raised dark capsule over the canvas, the
 * selected tab shown as a filled accent pill with its label, the rest as quiet icons. Every item
 * keeps a 48dp touch target and a text label for screen readers (Requirement 63).
 */
@Composable
fun CredBottomBar(items: List<CredBarItem>, selectedKey: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .background(scheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .border(1.dp, scheme.outlineVariant, CircleShape)
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = item.key == selectedKey
                val bg by animateColorAsState(if (selected) scheme.primary else Color.Transparent, label = "barBg")
                val fg by animateColorAsState(if (selected) scheme.onPrimary else scheme.onSurfaceVariant, label = "barFg")
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(bg)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item.key) })
                        .heightIn(min = 48.dp)
                        .padding(horizontal = if (selected) 16.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(if (selected) item.selectedIcon else item.icon, contentDescription = if (selected) null else item.label, tint = fg)
                    if (selected) {
                        Text(item.label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Horizontally scrolling pill tabs: the Cred replacement for an underlined tab row. The selected
 * pill is filled with the on-background ink so it reads as the one strong element on the strip.
 */
@Composable
fun CredPillTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable (index: Int) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (selected) scheme.onBackground else scheme.surface)
                    .border(1.dp, if (selected) scheme.onBackground else scheme.outlineVariant, CircleShape)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) scheme.background else scheme.onSurface,
                )
                trailing?.invoke(index)
            }
        }
    }
}

/** A rounded group of [CredRow]s, separated by hairlines, under an optional section label. */
@Composable
fun CredGroup(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (title != null) SectionHeader(title.uppercase())
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
        ) { content() }
    }
}

/** Hairline between rows inside a [CredGroup]. */
@Composable
fun CredDivider() {
    HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * A settings-style row: an icon in a tinted circle, a title with optional supporting text, and a
 * chevron when the row navigates. [destructive] paints the title in the error colour.
 */
@Composable
fun CredRow(
    title: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    destructive: Boolean = false,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (destructive) scheme.errorContainer else scheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (destructive) scheme.onErrorContainer else scheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (destructive) scheme.error else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
        if (trailing != null) trailing()
        if (onClick != null && showChevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}

/**
 * The Cred primary call to action: a full-width, high-contrast pill (ink text on near-white). Used
 * once per screen at most, so the strongest element on the page is always the next step.
 */
@Composable
fun CredPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(if (enabled) scheme.onBackground else scheme.surfaceVariant)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) scheme.background else scheme.onSurfaceVariant,
        )
    }
}

/** A large stat for hero cards: small uppercase caption over a big confident number. */
@Composable
fun CredStat(caption: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Column(modifier) {
        Text(
            caption.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineLarge,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onBackground else valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
