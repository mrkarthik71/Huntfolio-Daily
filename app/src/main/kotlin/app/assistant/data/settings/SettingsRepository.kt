package app.assistant.data.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import app.assistant.data.settings.proto.SettingsProto
import app.assistant.domain.engine.settings.SettingsField
import app.assistant.domain.engine.settings.SettingsValidator
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.AiMode
import app.assistant.domain.model.AppSettings
import app.assistant.domain.model.LocalTimeRange
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.PromptType
import app.assistant.domain.model.StreakRule
import app.assistant.domain.port.SettingsStore
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

object SettingsFiles {
    const val FILE_NAME = "settings.pb"

    fun file(context: Context): File = context.dataStoreFile(FILE_NAME)
}

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<SettingsProto>) : SettingsStore {

    override val settings: Flow<AppSettings> = store.data.map(SettingsMapper::toDomain)

    override suspend fun current(): AppSettings = settings.first()

    /**
     * Applies [change] and stores the result only if every field is valid; otherwise nothing is
     * written and the errors are returned.
     */
    override suspend fun update(change: (AppSettings) -> AppSettings): Outcome<AppSettings, FieldErrors<SettingsField>> {
        var outcome: Outcome<AppSettings, FieldErrors<SettingsField>>? = null
        store.updateData { proto ->
            val next = change(SettingsMapper.toDomain(proto))
            val errors = SettingsValidator.validate(next)
            if (errors.isValid) {
                outcome = Outcome.Ok(next)
                SettingsMapper.toProto(next)
            } else {
                outcome = Outcome.Err(errors)
                proto
            }
        }
        return checkNotNull(outcome)
    }
}

/** Converts between the stored proto and the domain settings. Unknown enum names are dropped. */
object SettingsMapper {
    const val SCHEMA_VERSION = 1

    fun toDomain(p: SettingsProto): AppSettings {
        if (p.schemaVersion == 0) return AppSettings()
        val defaults = AppSettings()
        return AppSettings(
            lockTimeout = Duration.ofSeconds(p.lockTimeoutSeconds.toLong()),
            morningBrief = minuteToTime(p.morningBriefMinute) ?: defaults.morningBrief,
            eveningReview = minuteToTime(p.eveningReviewMinute) ?: defaults.eveningReview,
            workingHours = range(p.workingStartMinute, p.workingEndMinute) ?: defaults.workingHours,
            quietHours = if (p.quietHoursEnabled) range(p.quietStartMinute, p.quietEndMinute) else null,
            dailyCap = p.dailyCap,
            perTypeLimit = AppSettings.defaultPerTypeLimits() + p.perTypeLimitMap.mapNotNull { (k, v) ->
                enumOrNull<NotificationType>(k)?.let { it to v }
            },
            capExemptTypes = p.capExemptTypesList.mapNotNull { enumOrNull<NotificationType>(it) }.toSet(),
            enabledTypes = p.enabledTypesList.mapNotNull { enumOrNull<NotificationType>(it) }.toSet(),
            lastDayOfWeek = p.lastDayOfWeek.takeIf { it in 1..7 }?.let(DayOfWeek::of) ?: defaults.lastDayOfWeek,
            streakRule = enumOrNull<StreakRule>(p.streakRule) ?: defaults.streakRule,
            minFocusLength = if (p.calendarSettingsSet) Duration.ofMinutes(p.minFocusMinutes.toLong()) else defaults.minFocusLength,
            eventLeadTime = if (p.calendarSettingsSet) Duration.ofMinutes(p.eventLeadMinutes.toLong()) else defaults.eventLeadTime,
            aiMode = enumOrNull<AiMode>(p.aiMode) ?: defaults.aiMode,
            aiMonthlyCapPaise = p.aiMonthlyCapPaise,
            aiModels = p.aiModelsMap.mapNotNull { (k, v) -> enumOrNull<PromptType>(k)?.let { it to v } }.toMap(),
            smsFallbackParserEnabled = p.smsFallbackParserEnabled,
            mailImportanceThreshold = if (p.mailSettingsSet) p.mailImportanceThreshold else defaults.mailImportanceThreshold,
            mailSnippetLength = if (p.mailSettingsSet) p.mailSnippetLength else defaults.mailSnippetLength,
            mailDigestTime = if (p.mailSettingsSet) (minuteToTime(p.mailDigestMinute) ?: defaults.mailDigestTime) else defaults.mailDigestTime,
            mailSyncIntervalMinutes = if (p.mailSettingsSet) p.mailSyncIntervalMinutes else defaults.mailSyncIntervalMinutes,
        )
    }

    fun toProto(s: AppSettings): SettingsProto = SettingsProto.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setLockTimeoutSeconds(s.lockTimeout.seconds.toInt())
        .setMorningBriefMinute(s.morningBrief.toMinute())
        .setEveningReviewMinute(s.eveningReview.toMinute())
        .setWorkingStartMinute(s.workingHours.start.toMinute())
        .setWorkingEndMinute(s.workingHours.end.toMinute())
        .setQuietHoursEnabled(s.quietHours != null)
        .setQuietStartMinute(s.quietHours?.start?.toMinute() ?: 0)
        .setQuietEndMinute(s.quietHours?.end?.toMinute() ?: 0)
        .setDailyCap(s.dailyCap)
        .putAllPerTypeLimit(s.perTypeLimit.mapKeys { it.key.name })
        .addAllCapExemptTypes(s.capExemptTypes.map { it.name }.sorted())
        .addAllEnabledTypes(s.enabledTypes.map { it.name }.sorted())
        .setLastDayOfWeek(s.lastDayOfWeek.value)
        .setStreakRule(s.streakRule.name)
        .setCalendarSettingsSet(true)
        .setMinFocusMinutes(s.minFocusLength.toMinutes().toInt())
        .setEventLeadMinutes(s.eventLeadTime.toMinutes().toInt())
        .setAiMode(s.aiMode.name)
        .setAiMonthlyCapPaise(s.aiMonthlyCapPaise)
        .putAllAiModels(s.aiModels.mapKeys { it.key.name })
        .setSmsFallbackParserEnabled(s.smsFallbackParserEnabled)
        .setMailSettingsSet(true)
        .setMailImportanceThreshold(s.mailImportanceThreshold)
        .setMailSnippetLength(s.mailSnippetLength)
        .setMailDigestMinute(s.mailDigestTime.toMinute())
        .setMailSyncIntervalMinutes(s.mailSyncIntervalMinutes)
        .build()

    private fun LocalTime.toMinute(): Int = hour * 60 + minute

    private fun minuteToTime(m: Int): LocalTime? = if (m in 0 until 24 * 60) LocalTime.of(m / 60, m % 60) else null

    private fun range(start: Int, end: Int): LocalTimeRange? {
        val s = minuteToTime(start) ?: return null
        val e = minuteToTime(end) ?: return null
        return LocalTimeRange(s, e)
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
}

object SettingsSerializer : Serializer<SettingsProto> {
    override val defaultValue: SettingsProto = SettingsMapper.toProto(AppSettings())

    override suspend fun readFrom(input: InputStream): SettingsProto = try {
        SettingsProto.parseFrom(input)
    } catch (e: InvalidProtocolBufferException) {
        throw CorruptionException("Settings file is damaged", e)
    }

    override suspend fun writeTo(t: SettingsProto, output: OutputStream) = t.writeTo(output)
}
