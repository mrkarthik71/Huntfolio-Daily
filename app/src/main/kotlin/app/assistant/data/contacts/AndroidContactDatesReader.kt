package app.assistant.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import app.assistant.domain.engine.life.DateRules
import app.assistant.domain.model.DateKind
import app.assistant.domain.model.ImportantDate
import app.assistant.domain.port.ContactDatesReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/**
 * Reads birthdays and anniversaries saved in the phone's contacts. Only the contact's display name
 * and the date are kept; nothing else about the contact is read. Returns nothing without the
 * contacts permission, which is asked for only when the User taps "Import from contacts".
 */
class AndroidContactDatesReader @Inject constructor(@param:ApplicationContext private val context: Context) : ContactDatesReader {
    override suspend fun read(): List<ImportantDate> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext emptyList()
        val out = ArrayList<ImportantDate>()
        val projection = arrayOf(
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Event.START_DATE,
            ContactsContract.CommonDataKinds.Event.TYPE,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
        runCatching {
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val date = parse(c.getString(1)) ?: continue
                    val kind = when (c.getInt(2)) {
                        ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY -> DateKind.BIRTHDAY
                        ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY -> DateKind.ANNIVERSARY
                        else -> continue
                    }
                    if (name.isNotEmpty()) out += ImportantDate(0, name, kind, date, yearly = true, fromContacts = true)
                }
            }
        }
        out
    }

    /** Contacts store "YYYY-MM-DD" or, without a year, "--MM-DD". */
    private fun parse(raw: String?): LocalDate? {
        val s = raw?.trim() ?: return null
        return runCatching {
            if (s.startsWith("--")) LocalDate.parse("${DateRules.NO_YEAR}-${s.removePrefix("--").take(MONTH_DAY)}") else LocalDate.parse(s.take(ISO_DATE))
        }.getOrNull()
    }

    private companion object {
        const val MONTH_DAY = 5
        const val ISO_DATE = 10
    }
}
