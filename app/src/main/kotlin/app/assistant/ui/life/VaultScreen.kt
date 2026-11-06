package app.assistant.ui.life

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.model.DateKind
import app.assistant.domain.model.ImportantDate
import app.assistant.domain.model.VaultItem
import app.assistant.domain.port.DateStore
import app.assistant.domain.port.TimeSource
import app.assistant.domain.port.VaultFiles
import app.assistant.domain.port.VaultStore
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val store: VaultStore,
    private val files: VaultFiles,
    dates: DateStore,
    private val time: TimeSource,
) : ViewModel() {
    val items: StateFlow<List<VaultItem>> = store.items().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())
    val documents: StateFlow<List<ImportantDate>> = kotlinx.coroutines.flow.flow {
        dates.dates().collect { l -> emit(l.filter { it.kind != DateKind.BIRTHDAY && it.kind != DateKind.ANNIVERSARY }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    /** Shrinks the photo (long side [MAX_SIDE] px, JPEG) and stores it encrypted. */
    fun add(title: String, dateId: Long?, raw: ByteArray) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val jpeg = withContext(Dispatchers.Default) { shrink(raw) } ?: return@launch
            val name = files.put(jpeg)
            store.add(title, dateId, name, MIME_JPEG, time.now())
        }
    }

    suspend fun image(item: VaultItem): Bitmap? = files.read(item.fileName)?.let { b -> withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b, 0, b.size) } }

    fun delete(item: VaultItem) {
        viewModelScope.launch {
            files.remove(item.fileName)
            store.delete(item.id)
        }
    }

    private fun shrink(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val STOP_MS = 5_000L
        const val MAX_SIDE = 2_000
        const val QUALITY = 85
        const val MIME_JPEG = "image/jpeg"
    }
}

/** Blocks screenshots and the recents thumbnail while vault content is on screen. */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
fun VaultScreen(onBack: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    SecureWindow()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val docs by viewModel.documents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var picked by remember { mutableStateOf<ByteArray?>(null) }
    var viewing by remember { mutableStateOf<VaultItem?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        picked = uri?.let { runCatching { context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } }.getOrNull() }
    }
    VaultContent(items, docs, onBack, onAdd = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, onOpen = { viewing = it })
    picked?.let { bytes ->
        var title by rememberSaveable { mutableStateOf("") }
        var link by rememberSaveable { mutableStateOf<Long?>(null) }
        LifeSheet(stringResource(R.string.vault_add), { picked = null }) {
            LifeField(title, { title = it }, stringResource(R.string.vault_name))
            if (docs.isNotEmpty()) {
                Caption(stringResource(R.string.vault_link))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionKey(stringResource(R.string.vault_no_link), link == null, { link = null })
                    docs.forEach { d -> OptionKey(d.title, link == d.id, { link = d.id }) }
                }
            }
            CredPrimaryButton(stringResource(R.string.life_save), onClick = {
                viewModel.add(title, link, bytes)
                picked = null
            }, enabled = title.isNotBlank())
        }
    }
    viewing?.let { item -> VaultViewer(item, viewModel, onClose = { viewing = null }) }
}

@Composable
fun VaultContent(items: List<VaultItem>, docs: List<ImportantDate>, onBack: () -> Unit, onAdd: () -> Unit, onOpen: (VaultItem) -> Unit) {
    val byId = docs.associateBy { it.id }
    LazyColumn(
        Modifier.fillMaxSize().consoleGround(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                Caption(stringResource(R.string.vault_caption), Modifier.padding(start = 14.dp).weight(1f), color = MaterialTheme.colorScheme.primary)
                KeyButton(Icons.Filled.Add, stringResource(R.string.vault_add), onClick = onAdd, tint = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Column(Modifier.enterAnimated(0)) {
                Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.vault_reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (items.isEmpty()) item { Text(stringResource(R.string.vault_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(items, key = { _, v -> v.id }) { i, v ->
            val doc = v.dateId?.let(byId::get)
            Column(Modifier.fillMaxWidth().enterAnimated(i + 1).raisedPanel(MaterialTheme.shapes.large).clickable { onOpen(v) }.padding(16.dp)) {
                Caption(stringResource(R.string.vault_locked), color = MaterialTheme.colorScheme.tertiary)
                Text(v.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                if (doc != null) Text(stringResource(R.string.vault_expires, doc.title, formatDate(doc.date)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun VaultViewer(item: VaultItem, viewModel: VaultViewModel, onClose: () -> Unit) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) { bmp = viewModel.image(item) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SecureWindow()
        Column(Modifier.fillMaxSize().consoleGround().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onClose)
                Text(item.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 14.dp).weight(1f))
                TextButton(onClick = {
                    viewModel.delete(item)
                    onClose()
                }) { Text(stringResource(R.string.life_delete), color = MaterialTheme.colorScheme.error) }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b == null) {
                    Text(stringResource(R.string.vault_opening), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Image(b.asImageBitmap(), contentDescription = item.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
