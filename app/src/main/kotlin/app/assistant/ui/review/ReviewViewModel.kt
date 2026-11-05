package app.assistant.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.engine.review.ReviewChoice
import app.assistant.domain.engine.review.ReviewDraft
import app.assistant.domain.engine.review.ReviewEngine
import app.assistant.domain.engine.review.ReviewSummary
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.port.ReviewRepository
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ReviewUi(
    val loading: Boolean = true,
    val date: LocalDate? = null,
    val tasks: List<Task> = emptyList(),
    val draft: ReviewDraft? = null,
    val summary: ReviewSummary = ReviewSummary(0, 0, 0, 0),
    val finished: Boolean = false,
    /** AI wording layered on after the rule-based summary (Requirement 48.1); null = none yet. */
    val aiSummary: String? = null,
    val aiSuggestions: List<AiReviewSuggestion> = emptyList(),
    val aiUnavailable: Boolean = false,
)

/** An AI-proposed idea from the evening review (shown, never applied automatically). */
data class AiReviewSuggestion(val text: String, val reason: String)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val reviews: ReviewRepository,
    private val time: TimeSource,
    private val advisor: app.assistant.ai.feature.FeatureAdvisor,
) : ViewModel() {

    private val reviewDate = MutableStateFlow<LocalDate?>(null)
    private val draftFlow = MutableStateFlow<ReviewDraft?>(null)
    private val finished = MutableStateFlow(false)
    private val aiOverlay = MutableStateFlow(AiReviewOverlay())

    private val plannedTasks = reviewDate.flatMapLatest { date ->
        if (date == null) flowOf(emptyList()) else tasks.plannedOn(date)
    }

    val state: StateFlow<ReviewUi> =
        combine(reviewDate, draftFlow, finished, plannedTasks, aiOverlay) { date, draft, done, planned, ai ->
            val d = draft ?: (date?.let { ReviewDraft(it) })
            ReviewUi(
                loading = date == null,
                date = date,
                tasks = planned,
                draft = d,
                summary = if (d != null) ReviewEngine.summarise(planned, d) else ReviewSummary(0, 0, 0, 0),
                finished = done,
                aiSummary = ai.summary,
                aiSuggestions = ai.suggestions,
                aiUnavailable = ai.unavailable,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUi())

    private data class AiReviewOverlay(val summary: String? = null, val suggestions: List<AiReviewSuggestion> = emptyList(), val unavailable: Boolean = false)

    /** Requests AI wording for the evening review after the rule-based summary is shown (Requirement 48.1). */
    fun requestAiWording() {
        val ui = state.value
        if (ui.date == null) return
        val s = ui.summary
        viewModelScope.launch {
            val facts = app.assistant.ai.payload.SummaryBuilder.ReviewFacts(
                numbers = mapOf(
                    "done" to s.done.toString(),
                    "moved" to s.moved.toString(),
                    "dropped" to s.dropped.toString(),
                    "left_open" to s.leftOpen.toString(),
                ),
            )
            when (val r = advisor.review(app.assistant.domain.model.PromptType.EVENING_REVIEW, facts)) {
                is app.assistant.ai.feature.FeatureAdvisor.Result.Advice -> {
                    val summary = (r.output as? app.assistant.ai.output.ValidatedOutput.Review)?.summary
                    val suggestions = app.assistant.ai.feature.FeatureAdvisor.suggestionsOf(r.output).map { AiReviewSuggestion(it.text, it.reason) }
                    aiOverlay.value = AiReviewOverlay(summary = summary, suggestions = suggestions, unavailable = false)
                }
                is app.assistant.ai.feature.FeatureAdvisor.Result.Unavailable ->
                    aiOverlay.value = AiReviewOverlay(unavailable = r.reason != app.assistant.ai.feature.FeatureAdvisor.Reason.DISABLED)
            }
        }
    }

    fun setDate(date: LocalDate) {
        if (reviewDate.value != date) {
            reviewDate.value = date
            draftFlow.value = ReviewDraft(date)
            finished.value = false
        }
    }

    fun choose(id: TaskId, choice: ReviewChoice?) {
        val current = draftFlow.value ?: return
        draftFlow.value = current.choose(id, choice)
    }

    fun finish(onDone: () -> Unit) {
        val draft = draftFlow.value ?: return
        viewModelScope.launch {
            reviews.finish(draft, time.now(), time.today())
            finished.value = true
            onDone()
        }
    }

    companion object {
        /** A task already resolved is shown as such and takes no choice (Requirement 16.4). */
        fun isResolved(task: Task): Boolean = task.status != TaskStatus.OPEN
    }
}
