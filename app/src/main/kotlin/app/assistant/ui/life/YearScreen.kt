package app.assistant.ui.life

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.life.HabitRules
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.HabitStore
import app.assistant.domain.port.ReflectionStore
import app.assistant.domain.port.SavingsStore
import app.assistant.domain.port.TimeSource
import app.assistant.domain.port.TransactionRepository
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.ReadoutTile
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.enterAnimated
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject

data class YearUi(
    val year: Int = 0,
    val focusMinutes: Long = 0,
    val checkIns: Int = 0,
    val bestStreak: Int = 0,
    val goalsHit: Int = 0,
    val goalsTotal: Int = 0,
    val spent: Paise = Paise(0),
    val saved: Paise = Paise(0),
    val goodDays: Int = 0,
    val loggedDays: Int = 0,
)

/** The year so far, from what is already on the phone. Every number is counted, none estimated. */
@HiltViewModel
@Suppress("LongParameterList") // one read port per fact on the page
class YearViewModel @Inject constructor(
    private val time: TimeSource,
    private val reflection: ReflectionStore,
    private val habits: HabitStore,
    private val savings: SavingsStore,
    private val goals: GoalRepository,
    private val transactions: TransactionRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(YearUi())
    val ui: StateFlow<YearUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { _ui.value = load() }
    }

    private suspend fun load(): YearUi {
        val zone = time.zone()
        val today = time.today()
        val start = today.withDayOfYear(1)
        val startInstant = start.atStartOfDay(zone).toInstant()
        val sessions = runCatching { reflection.sessions(startInstant, time.now()).first() }.getOrDefault(emptyList())
        val focus = sessions.sumOf { s -> s.endedAt?.let { Duration.between(s.startedAt, it).toMinutes() } ?: 0L }
        val habitList = runCatching { habits.habitsNow() }.getOrDefault(emptyList())
        val checks = runCatching { habits.checks(start, today).first() }.getOrDefault(emptyList())
        val statuses = runCatching { goals.statusesNow(today) }.getOrDefault(emptyList())
        val spent = runCatching {
            transactions.transactions().first().filter { it.type == TxnType.DEBIT && !it.isReversal && !it.at.isBefore(startInstant) }
                .fold(Paise(0)) { a, t -> a + t.amount }
        }.getOrDefault(Paise(0))
        val saved = runCatching { savings.entries().first().filter { !it.date.isBefore(start) }.fold(Paise(0)) { a, e -> a + e.amount } }.getOrDefault(Paise(0))
        val moods = runCatching { reflection.moods(start, today).first() }.getOrDefault(emptyList())
        return YearUi(
            year = today.year,
            focusMinutes = focus,
            checkIns = checks.size,
            bestStreak = habitList.maxOfOrNull { HabitRules.streak(it, checks, today) } ?: 0,
            goalsHit = statuses.count { it.percent >= FULL },
            goalsTotal = statuses.size,
            spent = spent,
            saved = saved,
            goodDays = moods.count { it.mood >= GOOD_MOOD },
            loggedDays = moods.size,
        )
    }

    private companion object {
        const val FULL = 100
        const val GOOD_MOOD = 4
    }
}

@Composable
fun YearScreen(onBack: () -> Unit, viewModel: YearViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    YearContent(ui, onBack)
}

@Composable
fun YearContent(ui: YearUi, onBack: () -> Unit) {
    val tiles = listOf(
        Triple(stringResource(R.string.year_focus), stringResource(R.string.year_hours, ui.focusMinutes / MIN_PER_HOUR), null),
        Triple(stringResource(R.string.year_check_ins), "${ui.checkIns}", stringResource(R.string.year_best_streak, ui.bestStreak)),
        Triple(stringResource(R.string.year_goals), "${ui.goalsHit}/${ui.goalsTotal}", null),
        Triple(stringResource(R.string.year_saved), MoneyFormatter.format(ui.saved), null),
        Triple(stringResource(R.string.year_spent), MoneyFormatter.format(ui.spent), null),
        Triple(stringResource(R.string.year_good_days), "${ui.goodDays}", stringResource(R.string.year_of_logged, ui.loggedDays)),
    )
    LazyColumn(
        Modifier.fillMaxSize().consoleGround(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                Caption(stringResource(R.string.year_caption), Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Text(
                stringResource(R.string.year_title, ui.year),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.enterAnimated(0),
            )
        }
        tiles.chunked(2).forEachIndexed { i, pair ->
            item {
                Row(Modifier.fillMaxWidth().enterAnimated(i + 1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (cap, value, note) -> ReadoutTile(cap, value, Modifier.weight(1f), note = note) }
                }
            }
        }
    }
}

private const val MIN_PER_HOUR = 60
