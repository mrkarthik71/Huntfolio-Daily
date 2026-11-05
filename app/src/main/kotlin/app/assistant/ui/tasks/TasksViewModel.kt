package app.assistant.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.engine.tasks.TaskField
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.Goal
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class TasksUi(val loading: Boolean = true, val tasks: List<Task> = emptyList(), val goals: List<Goal> = emptyList())

@HiltViewModel
class TasksViewModel @Inject constructor(private val tasks: TaskRepository, private val goals: GoalRepository, private val time: TimeSource) : ViewModel() {

    val state: StateFlow<TasksUi> = combine(
        tasks.openTasks(),
        goals.statuses(time.today()).map { statuses -> statuses.map { it.goal } },
    ) { open, goalList ->
        TasksUi(loading = false, tasks = open, goals = goalList)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUi())

    suspend fun load(id: TaskId): Task? = tasks.get(id)

    /** Returns the field errors, or null on success so the editor can close (Requirement 12.2). */
    fun save(input: TaskInput, existing: TaskId?, onResult: (FieldErrors<TaskField>?) -> Unit) {
        viewModelScope.launch {
            val result = tasks.save(input, existing, time.now(), time.today())
            onResult(if (result is Outcome.Err) result.error else null)
        }
    }

    fun markDone(id: TaskId) = viewModelScope.launch { tasks.markDone(id, time.now(), time.today()) }

    fun moveToTomorrow(id: TaskId) = viewModelScope.launch {
        tasks.move(id, time.today().plusDays(1), time.today())
    }

    fun drop(id: TaskId) = viewModelScope.launch { tasks.drop(id) }

    fun delete(id: TaskId) = viewModelScope.launch { tasks.delete(id) }

    fun today(): LocalDate = time.today()
}
