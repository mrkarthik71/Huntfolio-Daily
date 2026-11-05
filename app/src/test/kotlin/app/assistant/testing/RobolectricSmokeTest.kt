package app.assistant.testing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.assistant.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves that Robolectric runs through the JUnit Vintage engine and sees app resources.
 * Uses a plain Application so the test does not depend on the Hilt graph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RobolectricSmokeTest {
    @Test
    fun appResourcesAreAvailable() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("Assistant", context.getString(R.string.app_name))
    }
}
