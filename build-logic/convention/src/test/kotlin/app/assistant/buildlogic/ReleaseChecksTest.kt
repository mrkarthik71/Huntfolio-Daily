package app.assistant.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 5.6: release verification checks (Requirements 2.1, 65.4, 67.2). */
class ReleaseChecksTest {

    private fun manifest(vararg permissions: String) = buildString {
        append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n")
        permissions.forEach { append("    <uses-permission android:name=\"$it\" />\n") }
        append("</manifest>")
    }

    @Test
    fun eachAlwaysForbiddenPermissionIsRejected() {
        for (p in ReleaseChecks.ALWAYS_FORBIDDEN) {
            assertEquals(setOf(p), ReleaseChecks.forbiddenPermissions(manifest(p, "android.permission.INTERNET"), smsFree = false))
        }
    }

    @Test
    fun readSmsIsAllowedOnlyWithSms() {
        val m = manifest("android.permission.READ_SMS", "android.permission.RECEIVE_SMS")
        assertTrue(ReleaseChecks.forbiddenPermissions(m, smsFree = false).isEmpty())
        assertEquals(
            setOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS"),
            ReleaseChecks.forbiddenPermissions(m, smsFree = true),
        )
    }

    @Test
    fun commentsMentioningPermissionsAreIgnored() {
        val m = "<manifest><!-- never declare android.permission.SEND_SMS --></manifest>"
        assertTrue(ReleaseChecks.forbiddenPermissions(m, smsFree = true).isEmpty())
    }

    @Test
    fun versionCodeMustIncrease() {
        assertNull(ReleaseChecks.versionCodeProblem(5, "4"))
        assertNull(ReleaseChecks.versionCodeProblem(1, null))
        assertNull(ReleaseChecks.versionCodeProblem(1, "  "))
        assertNotNull(ReleaseChecks.versionCodeProblem(4, "4"))
        assertNotNull(ReleaseChecks.versionCodeProblem(3, "4\n"))
        assertNotNull(ReleaseChecks.versionCodeProblem(0, null))
        assertNotNull(ReleaseChecks.versionCodeProblem(2, "abc"))
    }
}
