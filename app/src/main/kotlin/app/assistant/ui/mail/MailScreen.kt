package app.assistant.ui.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.SectionHeader

/**
 * The mail screen (Requirement 51 to 57): connect (IMAP app password), the sync settings, the
 * Shortlist_Rules editor with the empty-rules notice, the mailbox status, and the Inbox list with
 * "Create task" and "Create bill" actions. It shows headers and scores only — never a snippet or a
 * link (Requirement 54.7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    onBack: () -> Unit,
    onCreateTask: (subject: String) -> Unit,
    onCreateBill: (subject: String) -> Unit,
    viewModel: MailViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Mail") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeader(if (ui.connected) "Mailbox: connected" else "Connect your mailbox") }
            item { ConnectCard(ui, viewModel) }

            item { SectionHeader("Shortlist rules") }
            if (ui.rulesEmpty) {
                item { CredCard { Column(Modifier.padding(16.dp)) { Text("No rules yet — add one to start shortlisting mail. With no rules, nothing is read by AI.", style = MaterialTheme.typography.bodySmall) } } }
            }
            items(ui.rules, key = { it.id }) { rule ->
                CredCard {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("from: ${rule.senderContains.ifBlank { "any" }}  ·  subject: ${rule.subjectContains.ifBlank { "any" }}", style = MaterialTheme.typography.bodyMedium)
                            Text("score ${rule.score}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { viewModel.deleteRule(rule.id) }) { Text("Remove") }
                    }
                }
            }
            item { AddRuleCard(viewModel) }

            item { SectionHeader("Quality (last 8 weeks)") }
            item {
                CredCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Notifications: ${ui.notifications8w}", style = MaterialTheme.typography.bodyMedium)
                        Text("Marked not important: ${ui.wrongAlerts8w}", style = MaterialTheme.typography.bodyMedium)
                        Text("Average per day (7 days): ${"%.1f".format(ui.avgNotificationsPerDay)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item { SectionHeader("Inbox") }
            items(ui.inbox, key = { it.id }) { row ->
                CredCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.subject, style = MaterialTheme.typography.titleSmall)
                        Text("${row.sender} · ${row.category} · score ${row.finalScore}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onCreateTask(row.subject) }) { Text("Create task") }
                            if (row.category == "BILL") TextButton(onClick = { onCreateBill(row.subject) }) { Text("Create bill") }
                            TextButton(onClick = { viewModel.notImportant(row) }) { Text("Not important") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectCard(ui: MailUi, viewModel: MailViewModel) {
    CredCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.connected) {
                Text("Your mailbox is connected.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
                return@Column
            }
            var host by remember { mutableStateOf("") }
            var port by remember { mutableStateOf("993") }
            var user by remember { mutableStateOf("") }
            var pass by remember { mutableStateOf("") }
            OutlinedTextField(host, { host = it }, label = { Text("IMAP host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Email / username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("App password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            ui.testError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            TextButton(enabled = !ui.testing, onClick = { viewModel.connect(host, port.toIntOrNull() ?: 993, user, pass) }) {
                Text(if (ui.testing) "Testing…" else "Connect")
            }
        }
    }
}

@Composable
private fun AddRuleCard(viewModel: MailViewModel) {
    CredCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var sender by remember { mutableStateOf("") }
            var subject by remember { mutableStateOf("") }
            var score by remember { mutableStateOf("5") }
            OutlinedTextField(sender, { sender = it }, label = { Text("Sender contains") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subject, { subject = it }, label = { Text("Subject contains") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(score, { score = it.filter(Char::isDigit) }, label = { Text("Score (1-10)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                viewModel.addRule(sender, subject, score.toIntOrNull() ?: 5)
                sender = ""; subject = ""; score = "5"
            }) { Text("Add rule") }
        }
    }
}
