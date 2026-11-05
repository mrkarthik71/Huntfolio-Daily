package app.assistant.data.reader

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import app.assistant.data.reader.proto.ReaderStateProto
import app.assistant.domain.port.ReaderFlags
import app.assistant.domain.port.ReaderStateStore
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

object ReaderStateFiles {
    const val FILE_NAME = "reader_state.pb"

    fun file(context: Context): File = context.dataStoreFile(FILE_NAME)
}

/** The DataStore-backed [ReaderStateStore]. Each setter reads-modifies-writes the proto in one atomic update. */
@Singleton
class ReaderStateRepository @Inject constructor(private val store: DataStore<ReaderStateProto>) : ReaderStateStore {

    override val flags: Flow<ReaderFlags> = store.data.map(ReaderStateMapper::toDomain)

    override suspend fun current(): ReaderFlags = flags.first()

    override suspend fun enableSms(at: Instant) {
        store.updateData {
            it.toBuilder().setSchemaVersion(ReaderStateMapper.SCHEMA_VERSION).setSmsEnabled(true).setSmsEnabledAtMillis(at.toEpochMilli()).build()
        }
    }

    override suspend fun disableSms() {
        store.updateData { it.toBuilder().setSchemaVersion(ReaderStateMapper.SCHEMA_VERSION).setSmsEnabled(false).build() }
    }

    override suspend fun advanceSmsCursor(to: Instant) {
        store.updateData { proto ->
            val current = proto.smsCursorMillis
            if (to.toEpochMilli() <= current) {
                proto
            } else {
                proto.toBuilder().setSchemaVersion(ReaderStateMapper.SCHEMA_VERSION).setSmsCursorMillis(to.toEpochMilli()).build()
            }
        }
    }

    override suspend fun markHistoryImportOffered() {
        store.updateData { it.toBuilder().setSchemaVersion(ReaderStateMapper.SCHEMA_VERSION).setHistoryImportOffered(true).build() }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        store.updateData { it.toBuilder().setSchemaVersion(ReaderStateMapper.SCHEMA_VERSION).setNotificationsEnabled(enabled).build() }
    }
}

/** Converts between the stored proto and the domain flags. A zero timestamp means "none". */
object ReaderStateMapper {
    const val SCHEMA_VERSION = 1

    fun toDomain(p: ReaderStateProto): ReaderFlags = ReaderFlags(
        smsEnabled = p.smsEnabled,
        smsEnabledAt = instantOrNull(p.smsEnabledAtMillis),
        smsCursor = instantOrNull(p.smsCursorMillis),
        historyImportOffered = p.historyImportOffered,
        notificationsEnabled = p.notificationsEnabled,
    )

    private fun instantOrNull(millis: Long): Instant? = if (millis > 0) Instant.ofEpochMilli(millis) else null
}

object ReaderStateSerializer : Serializer<ReaderStateProto> {
    override val defaultValue: ReaderStateProto = ReaderStateProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): ReaderStateProto = try {
        ReaderStateProto.parseFrom(input)
    } catch (e: InvalidProtocolBufferException) {
        throw CorruptionException("Reader state file is damaged", e)
    }

    override suspend fun writeTo(t: ReaderStateProto, output: OutputStream) = t.writeTo(output)
}
