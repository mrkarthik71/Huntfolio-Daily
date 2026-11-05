package app.assistant.ui.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.career.HuntfolioRecord
import app.assistant.domain.career.SkillChoice
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.Quantity
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.SectionHeader

/**
 * Import (Huntfolio) and encrypted export/restore (Requirement 59, 60). The export flow takes two
 * passphrase entries and shows the unrecoverable warning; restore takes one passphrase. The import
 * flow previews counts, lets the user pick skills to turn into Goals, then commits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataTransferScreen(onBack: () -> Unit, viewModel: DataTransferViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(viewModel::previewImport) }
    var exportPass by remember { mutableStateOf("") }
    var exportRepeat by remember { mutableStateOf("") }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { viewModel.export(it, exportPass.toCharArray(), exportRepeat.toCharArray()) }
    }
    var restorePass by remember { mutableStateOf("") }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.restore(it, restorePass.toCharArray()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(stringResource(R.string.transfer_title))
            }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            })
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(stringResource(R.string.transfer_import).uppercase())
            CredCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { importPicker.launch("application/json") }) { Text("Choose a Huntfolio file") }
                    ui.importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    ui.preview?.let { p ->
                        Text(
                            "${p.followUps} follow-ups (${p.followUpsAlreadyImported} already imported), ${p.skills} skills.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        SkillSelection(ui, viewModel)
                    }
                    ui.importResult?.let { r ->
                        Text("Imported: ${r.tasksCreated} tasks, ${r.goalsCreated} goals, ${r.skipped} skipped.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            SectionHeader(stringResource(R.string.transfer_backup).uppercase())
            CredCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.transfer_backup_body), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(exportPass, {
                        exportPass = it
                    }, label = {
                        Text(stringResource(R.string.transfer_passphrase))
                    }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(exportRepeat, {
                        exportRepeat = it
                    }, label = {
                        Text(stringResource(R.string.transfer_passphrase_repeat))
                    }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    ui.passphraseError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(enabled = !ui.busy, onClick = { exportPicker.launch("assistant-backup.paa") }) { Text(stringResource(R.string.transfer_export)) }
                    if (ui.exportDone) Text(stringResource(R.string.transfer_export_done), style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionHeader(stringResource(R.string.transfer_restore).uppercase())
            CredCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.transfer_restore_body), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(restorePass, {
                        restorePass = it
                    }, label = {
                        Text(stringResource(R.string.transfer_restore_passphrase))
                    }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = !ui.busy, onClick = { restorePicker.launch("*/*") }) { Text(stringResource(R.string.transfer_restore_choose)) }
                    ui.backupError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (ui.restoreDone) Text(stringResource(R.string.transfer_restore_done), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SkillSelection(ui: TransferUi, viewModel: DataTransferViewModel) {
    val skills = ui.parsedImport?.records?.filterIsInstance<HuntfolioRecord.Skill>().orEmpty()
    if (skills.isEmpty()) return
    val selected = remember { mutableStateOf(setOf<String>()) }
    Text(stringResource(R.string.transfer_skills), style = MaterialTheme.typography.titleSmall)
    skills.forEach { skill ->
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = skill.key in selected.value,
                onCheckedChange = { on -> selected.value = if (on) selected.value + skill.key else selected.value - skill.key },
            )
            Text(skill.name)
        }
    }
    TextButton(onClick = {
        val choices = selected.value.map { SkillChoice(it, GoalPeriod.MONTH, Quantity(100), "tasks") }
        viewModel.commitImport(choices)
    }) { Text(stringResource(R.string.transfer_import_action)) }
}
