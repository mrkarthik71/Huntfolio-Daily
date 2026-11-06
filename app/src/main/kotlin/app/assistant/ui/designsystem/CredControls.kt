package app.assistant.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One destination in the [CredBottomBar]. */
data class CredBarItem(val key: String, val label: String, val selectedIcon: ImageVector, val icon: ImageVector)

/**
 * The console nav: a raised panel along the bottom. The selected destination sits pressed into a
 * recessed key with a signal-orange icon; the rest are quiet icons. [center] (the Ask key) sits in
 * the middle, raised above the panel. Every item keeps a 48dp target and its label for screen
 * readers (Requirement 63).
 */
@Composable
fun CredBottomBar(
    items: List<CredBarItem>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    center: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .raisedPanel(MaterialTheme.shapes.large, elevation = 18.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val half = items.size / 2
            items.forEachIndexed { index, item ->
                if (center != null && index == half) center()
                val selected = item.key == selectedKey
                val tint by animateColorAsState(if (selected) scheme.primary else scheme.onSurfaceVariant, label = "barTint")
                Box(
                    Modifier
                        .size(52.dp)
                        .let { if (selected) it.recessedWell(MaterialTheme.shapes.small, deep = true) else it.clip(MaterialTheme.shapes.small) }
                        .selectable(selected = selected, role = Role.Tab) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(item.key)
                        }
                        .semantics { contentDescription = item.label },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                }
            }
            if (center != null && items.size == half) center()
        }
    }
}

/**
 * A segmented control: the options sit in one recessed well and the selected one rises out of it
 * as a raised key. Scrolls sideways when there are more options than fit.
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
    Box(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .recessedWell(MaterialTheme.shapes.medium)
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Row(
                    Modifier
                        .let { if (selected) it.raisedPanel(MaterialTheme.shapes.small, elevation = 6.dp) else it.clip(MaterialTheme.shapes.small) }
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) scheme.onBackground else scheme.onSurfaceVariant,
                    )
                    trailing?.invoke(index)
                }
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
                .raisedPanel(MaterialTheme.shapes.large),
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
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .let {
                if (onClick != null) {
                    it.pressScale(interaction).clickable(interaction, ripple()) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                } else {
                    it
                }
            }
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
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressScale(interaction)
            .let { if (enabled) it.shadow(12.dp, CircleShape, ambientColor = scheme.primary, spotColor = scheme.primary) else it }
            .clip(CircleShape)
            .background(if (enabled) scheme.primary else scheme.surfaceVariant)
            .clickable(interaction, ripple(), enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
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
            style = AssistantType.Readout,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onBackground else valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The CRED top bar for pushed screens: the back control sits in a raised circle and the title is a
 * large, tight headline on the canvas (no tinted app-bar band). Same slots as Material's TopAppBar.
 */
@Composable
fun CredTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().background(scheme.background).statusBarsPadding().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.raisedPanel(CircleShape, elevation = 8.dp)) { navigationIcon() }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f)) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.headlineMedium.copy(color = scheme.onBackground)) { title() }
        }
        actions()
    }
}

/**
 * The CRED single-line input: a raised capsule with the hint inside and a round ink action button
 * at its end (white on black, the screen's strong element). Used for quick add and Ask.
 */
@Composable
@Suppress("LongParameterList") // mirrors a text field's slots plus its action
fun CredInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    actionLabel: String,
    actionIcon: ImageVector,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val canAct = value.isNotBlank()
    Row(
        modifier.fillMaxWidth().recessedWell(CircleShape)
            .let { if (isError) it.border(1.dp, scheme.error, CircleShape) else it }
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (canAct) onAction() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
            )
        }
        Box(
            Modifier.size(44.dp).pressScale(interaction).clip(CircleShape).background(if (canAct) scheme.onBackground else scheme.surfaceVariant)
                .clickable(interaction, ripple(), enabled = canAct, role = Role.Button) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAction()
                },
            contentAlignment = Alignment.Center,
        ) { Icon(actionIcon, contentDescription = actionLabel, tint = if (canAct) scheme.background else scheme.onSurfaceVariant) }
    }
}
