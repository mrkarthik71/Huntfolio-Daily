package app.assistant.widget

/**
 * What the home-screen widget is allowed to show (design: Today_Widget, Requirement 21.2). It is a
 * deliberately narrow projection: Priority title, done flag and the Task id needed to tick it. It
 * carries NO money, Bill, Goal-progress or email fields, and the Konsist allow-list test
 * (`WidgetModelAllowListTest`) fails the build if a field outside this set is ever added.
 */
data class WidgetModel(val taskId: Long, val title: String, val done: Boolean)
