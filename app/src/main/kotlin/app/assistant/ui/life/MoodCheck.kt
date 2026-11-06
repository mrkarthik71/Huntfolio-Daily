package app.assistant.ui.life

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.model.MoodEntry
import app.assistant.domain.port.ReflectionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class MoodViewModel @Inject constructor(private val store: ReflectionStore) : ViewModel() {
    private val _entry = MutableStateFlow<MoodEntry?>(null)
    val entry: StateFlow<MoodEntry?> = _entry.asStateFlow()

    fun load(date: LocalDate) {
        viewModelScope.launch { _entry.value = store.moods(date, date).first().firstOrNull() }
    }

    fun set(date: LocalDate, mood: Int?, energy: Int?) {
        val cur = _entry.value ?: MoodEntry(date, DEFAULT_MOOD, DEFAULT_ENERGY)
        val next = cur.copy(date = date, mood = mood ?: cur.mood, energy = energy ?: cur.energy)
        _entry.value = next
        viewModelScope.launch { store.saveMood(next) }
    }

    private companion object {
        const val DEFAULT_MOOD = 3
        const val DEFAULT_ENERGY = 2
    }
}

private val MOODS = listOf(R.string.mood_1, R.string.mood_2, R.string.mood_3, R.string.mood_4, R.string.mood_5)
private val ENERGIES = listOf(R.string.energy_1, R.string.energy_2, R.string.energy_3)

/** The one-tap evening check in the review: how the day felt and how much energy is left. */
@Composable
fun MoodCheck(date: LocalDate, modifier: Modifier = Modifier, viewModel: MoodViewModel = hiltViewModel()) {
    androidx.compose.runtime.LaunchedEffect(date) { viewModel.load(date) }
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupCaption(stringResource(R.string.mood_caption))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MOODS.forEachIndexed { i, res -> OptionKey(stringResource(res), entry?.mood == i + 1, { viewModel.set(date, i + 1, null) }, Modifier.weight(1f)) }
        }
        GroupCaption(stringResource(R.string.energy_caption))
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ENERGIES.forEachIndexed { i, res ->
                OptionKey(stringResource(res), entry?.energy == i + 1, { viewModel.set(date, null, i + 1) }, Modifier.weight(1f))
            }
        }
    }
}
