package app.assistant.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Layer and safety rules from the design, enforced in the single :app module (ADR 0001).
 * A failing test here fails CI, so a layer break cannot be merged.
 */
class ArchitectureTest {

    private val scope = Konsist.scopeFromProduction(moduleName = "app")
    private val files: List<KoFileDeclaration> = scope.files.filter { it.packagee?.name?.startsWith(ROOT) == true }

    private fun filesIn(pkg: String) = files.filter { it.packagee?.name?.let { n -> n == "$ROOT.$pkg" || n.startsWith("$ROOT.$pkg.") } == true }

    private fun KoFileDeclaration.importsAny(prefixes: List<String>): Boolean =
        imports.any { imp -> prefixes.any { p -> imp.name == p || imp.name.startsWith("$p.") } }

    /** Must-not-depend-on table from the design ("Dependency rules"). */
    private val forbidden: Map<String, List<String>> = mapOf(
        "domain" to listOf("parse", "ai", "data", "ingest", "notify", "security", "ui"),
        "parse" to listOf("ai", "data", "ingest", "notify", "security", "ui"),
        "ai" to listOf("data", "ingest", "notify", "ui"),
        "data" to listOf("ai", "ingest", "notify", "ui"),
        "ingest" to listOf("ai", "data", "notify", "ui"),
        "notify" to listOf("ai", "data", "ingest", "ui"),
        "security" to listOf("parse", "ai", "data", "ingest", "notify", "ui"),
        "ui" to listOf("parse", "ai", "data", "ingest", "notify"),
    )

    @Test
    fun `layers depend only in the allowed directions`() {
        // Konsist rejects layers with no files, so rules apply only to layers that already have code.
        // Every layer gets checked as soon as its phase adds the package.
        val present = forbidden.keys.filter { filesIn(it).isNotEmpty() }.toSet()
        scope.assertArchitecture {
            val layers = present.associateWith { Layer(it, "$ROOT.$it..") }
            for ((name, targets) in forbidden) {
                val layer = layers[name] ?: continue
                val others = targets.mapNotNull { layers[it] }
                if (others.isNotEmpty()) layer.doesNotDependOn(others.first(), *others.drop(1).toTypedArray())
            }
        }
    }

    @Test
    fun `layer rules cover at least domain data security and ui`() {
        assertTrue(listOf("domain", "data", "security", "ui").all { filesIn(it).isNotEmpty() })
    }

    @Test
    fun `domain and parse are plain Kotlin without Android`() {
        (filesIn("domain") + filesIn("parse")).assertFalse(testName = "domain-no-android") {
            it.importsAny(listOf("android", "androidx", "dagger", "com.google"))
        }
    }

    @Test
    fun `only security log uses android Log or Timber`() {
        files.filterNot { it.packagee?.name == "$ROOT.security.log" }.assertFalse(testName = "no-raw-logging") {
            it.importsAny(listOf("android.util.Log", "timber.log.Timber")) || it.text.contains("println(")
        }
    }

    @Test
    fun `only data time reads the clock and the default zone`() {
        val banned =
            listOf(
                "Instant.now()",
                "ZoneId.systemDefault()",
                "Clock.systemDefaultZone()",
                "LocalDate.now()",
                "LocalDateTime.now()",
                "System.currentTimeMillis()",
            )
        files.filterNot { it.packagee?.name == "$ROOT.data.time" }.assertFalse(testName = "explicit-time") { f ->
            banned.any { f.text.contains(it) }
        }
    }

    @Test
    fun `money is never a floating point number in domain and parse`() {
        val floatingMoney = Regex("""\b\w*(amount|paise|balance)\w*\s*:\s*(Double|Float)\b""", RegexOption.IGNORE_CASE)
        (filesIn("domain") + filesIn("parse")).assertFalse(testName = "no-float-money") { floatingMoney.containsMatchIn(it.text) }
    }

    @Test
    fun `every Room entity lives in data db entity`() {
        scope.classes().filter { c -> c.annotations.any { it.name == "Entity" } }
            .assertTrue(testName = "entity-package") { it.resideInPackage("$ROOT.data.db.entity..") }
    }

    @Test
    fun `the app never sends, calls, writes the calendar or acts on other apps' notifications`() {
        val banned = listOf(
            "SmsManager",
            "sendTextMessage",
            "sendMultipartTextMessage",
            "ACTION_CALL",
            "cancelNotification(",
            "snoozeNotification(",
        )
        files.assertFalse(testName = "read-only") { f -> banned.any { f.text.contains(it) } }
        // Calendar provider writes (Requirement 2.2).
        files.filter { it.text.contains("CalendarContract") }.assertFalse(testName = "calendar-read-only") { f ->
            listOf(".insert(", ".update(", ".delete(", "applyBatch(").any { f.text.contains(it) }
        }
        // Readers never trigger another app's notification actions (Requirement 2.3).
        filesIn("ingest").assertFalse(testName = "no-pending-intent-send") { it.text.contains(".send(") && it.text.contains("PendingIntent") }
    }

    @Test
    fun `no advertising or analytics libraries`() {
        val banned = listOf("com.google.android.gms.ads", "com.google.firebase.analytics", "com.google.firebase.crashlytics", "io.sentry", "com.facebook")
        files.assertFalse(testName = "no-ads-analytics") { it.importsAny(banned) }
    }

    private companion object {
        const val ROOT = "app.assistant"
    }
}
