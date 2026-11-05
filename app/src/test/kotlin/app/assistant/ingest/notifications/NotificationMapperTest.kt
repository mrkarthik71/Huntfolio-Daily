package app.assistant.ingest.notifications

import app.assistant.ingest.pipeline.RawMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class NotificationMapperTest {
    private val at: Instant = Instant.parse("2025-01-05T08:36:00Z")

    private fun posted(title: CharSequence?, text: CharSequence?, key: String = "0|com.example.upiapp|7|null|10042") =
        PostedNotification("com.example.upiapp", key, title, text, at)

    @Test
    fun `a notification becomes a message from its package with its key and posted time`() {
        val message = NotificationMapper.toMessage(posted("Payment", "Paid Rs 250.00 to a@b"))
        assertNotNull(message)
        val m = message as RawMessage.Notification
        assertEquals("com.example.upiapp", m.packageName)
        assertEquals("com.example.upiapp", m.origin)
        assertEquals("0|com.example.upiapp|7|null|10042", m.key)
        assertEquals(at, m.receivedAt)
        assertEquals("Payment Paid Rs 250.00 to a@b", m.text)
    }

    @Test
    fun `a notification with only a title or only a text is still read`() {
        assertEquals("Paid Rs 5", NotificationMapper.toMessage(posted("Paid Rs 5", null))?.text)
        assertEquals("Paid Rs 5", NotificationMapper.toMessage(posted(null, "Paid Rs 5"))?.text)
        assertEquals("Paid Rs 5", NotificationMapper.toMessage(posted("", "Paid Rs 5"))?.text)
    }

    @Test
    fun `a notification with an empty title and empty text is discarded`() {
        assertNull(NotificationMapper.toMessage(posted(null, null)))
        assertNull(NotificationMapper.toMessage(posted("", "")))
        assertNull(NotificationMapper.toMessage(posted("   ", "\n")))
    }

    @Test
    fun `styled text is read as plain text`() {
        val styled: CharSequence = StringBuilder("Paid ").append("Rs 5")
        assertEquals("Paid Rs 5", NotificationMapper.toMessage(posted(null, styled))?.text)
    }

    @Test
    fun `the same notification always gives the same message identity`() {
        val a = NotificationMapper.toMessage(posted("t", "x")) as RawMessage.Notification
        val b = NotificationMapper.toMessage(posted("t", "x")) as RawMessage.Notification
        assertEquals(listOf(a.packageName, a.key, a.title, a.body), listOf(b.packageName, b.key, b.title, b.body))
    }

    @Test
    fun `a posted notification never prints its content`() {
        assertFalse(posted("SECRET-TITLE", "SECRET-TEXT").toString().contains("SECRET"))
    }
}
