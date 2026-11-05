package app.assistant.ui.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.CredTopBar
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
        topBar = {
            CredTopBar(title = {
                Text(stringResource(R.string.mail_title))
            }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeader(stringResource(if (ui.connected) R.string.mail_header_connected else R.string.mail_header_connect).uppercase()) }
            item { ConnectCard(ui, viewModel) }

            item { SectionHeader(stringResource(R.string.mail_rules).uppercase()) }
            if (ui.rulesEmpty) {
                item { CredCard { Column { Text(stringResource(R.string.mail_rules_empty), style = MaterialTheme.typography.bodyMedium) } } }
            }
            items(ui.rules, key = { it.id }) { rule ->
                CredCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val any = stringResource(R.string.mail_rule_any)
                            Text(
                                stringResource(
                                    R.string.mail_rule_summary,
                                    rule.senderContains.ifBlank {
                                        any
                                    },
                                    rule.subjectContains.ifBlank { any },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.mail_rule_score, rule.score),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { viewModel.deleteRule(rule.id) }) { Text(stringResource(R.string.mail_rule_remove)) }
                    }
                }
            }
            item { AddRuleCard(viewModel) }

            item { SectionHeader(stringResource(R.string.mail_quality).uppercase()) }
            item {
                CredCard {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.mail_quality_notifications, ui.notifications8w), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.mail_quality_wrong, ui.wrongAlerts8w), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.mail_quality_average, ui.avgNotificationsPerDay),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.mail_inbox).uppercase()) }
            items(ui.inbox, key = { it.id }) { row ->
                CredCard {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.subject, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.mail_inbox_meta, row.sender, row.category, row.finalScore),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onCreateTask(row.subject) }) { Text(stringResource(R.string.mail_create_task)) }
                            if (row.category == "BILL") TextButton(onClick = { onCreateBill(row.subject) }) { Text(stringResource(R.string.mail_create_bill)) }
                            TextButton(onClick = { viewModel.notImportant(row) }) { Text(stringResource(R.string.mail_not_important)) }
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
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.connected) {
                Text(stringResource(R.string.mail_connected_body), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { viewModel.disconnect() }) { Text(stringResource(R.string.mail_disconnect)) }
                return@Column
            }
            var host by remember { mutableStateOf("") }
            var port by remember { mutableStateOf("993") }
            var user by remember { mutableStateOf("") }
            var pass by remember { mutableStateOf("") }
            OutlinedTextField(host, {
                host = it
            }, label = { Text(stringResource(R.string.mail_imap_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, {
                port = it.filter(Char::isDigit)
            }, label = { Text(stringResource(R.string.mail_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, {
                user = it
            }, label = { Text(stringResource(R.string.mail_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, {
                pass = it
            }, label = {
                Text(stringResource(R.string.mail_app_password))
            }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            ui.testError?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            TextButton(enabled = !ui.testing, onClick = { viewModel.connect(host, port.toIntOrNull() ?: 993, user, pass) }) {
                Text(stringResource(if (ui.testing) R.string.mail_testing else R.string.mail_connect))
            }
        }
    }
}

@Composable
private fun AddRuleCard(viewModel: MailViewModel) {
    CredCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var sender by remember { mutableStateOf("") }
            var subject by remember { mutableStateOf("") }
            var score by remember { mutableStateOf("5") }
            OutlinedTextField(sender, {
                sender = it
            }, label = { Text(stringResource(R.string.mail_sender_contains)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subject, {
                subject = it
            }, label = { Text(stringResource(R.string.mail_subject_contains)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(score, {
                score = it.filter(Char::isDigit)
            }, label = { Text(stringResource(R.string.mail_score)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                viewModel.addRule(sender, subject, score.toIntOrNull() ?: 5)
                sender = ""
                subject = ""
                score = "5"
            }) { Text(stringResource(R.string.mail_add_rule)) }
        }
    }
}
