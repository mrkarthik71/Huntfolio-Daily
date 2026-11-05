package app.assistant.ui.common

import androidx.annotation.StringRes
import app.assistant.R
import app.assistant.domain.model.GoalPeriod
import java.time.DayOfWeek

/** Shared label maps for enums shown across screens. */

@StringRes
fun periodLabel(period: GoalPeriod): Int = when (period) {
    GoalPeriod.YEAR -> R.string.period_year
    GoalPeriod.MONTH -> R.string.period_month
    GoalPeriod.WEEK -> R.string.period_week
}

@StringRes
fun dayLabel(day: DayOfWeek): Int = when (day) {
    DayOfWeek.MONDAY -> R.string.day_monday
    DayOfWeek.TUESDAY -> R.string.day_tuesday
    DayOfWeek.WEDNESDAY -> R.string.day_wednesday
    DayOfWeek.THURSDAY -> R.string.day_thursday
    DayOfWeek.FRIDAY -> R.string.day_friday
    DayOfWeek.SATURDAY -> R.string.day_saturday
    DayOfWeek.SUNDAY -> R.string.day_sunday
}
