package com.example.ideavault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ideavault.data.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotesApp(
    lockTimeoutMillis: Long,
    onLockTimeoutChanged: (Long) -> Unit,
    viewModel: NoteViewModel = viewModel(),
) {
    val allNotes by viewModel.notes.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val notes = remember(allNotes) { allNotes.filterNot { it.deleted } }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var showSyncSettings by rememberSaveable { mutableStateOf(false) }
    var showServerStatus by rememberSaveable { mutableStateOf(false) }
    var showAppLockSettings by rememberSaveable { mutableStateOf(false) }
    val editingNote = notes.firstOrNull { it.id == editingId }

    if (creating || editingNote != null) {
        EditorScreen(
            note = editingNote,
            onSave = { title, content, sensitive ->
                viewModel.save(editingId, title, content, sensitive)
                creating = false
                editingId = null
            },
        )
    } else {
        NoteListScreen(
            notes = notes,
            syncState = syncState,
            onCreate = { creating = true },
            onOpen = { editingId = it.id },
            onPin = { viewModel.togglePinned(it.id) },
            onDelete = { viewModel.delete(it.id) },
            onAccount = { if (syncState.configured) showServerStatus = true else showSyncSettings = true },
            onAppLockSettings = { showAppLockSettings = true },
        )
    }

    if (showSyncSettings) {
        SyncSettingsDialog(
            syncState = syncState,
            onDismiss = { showSyncSettings = false },
            onAuthenticate = { url, username, password, register ->
                viewModel.authenticateAndSync(url, username, password, register)
                showSyncSettings = false
            },
            onClear = {
                viewModel.clearSyncConfig()
                showSyncSettings = false
            },
        )
    }

    if (showServerStatus) {
        ServerStatusDialog(
            syncState = syncState,
            onDismiss = { showServerStatus = false },
            onSync = { viewModel.syncNow() },
            onSettings = {
                showServerStatus = false
                showSyncSettings = true
            },
        )
    }

    if (showAppLockSettings) {
        AppLockSettingsDialog(
            currentTimeoutMillis = lockTimeoutMillis,
            onDismiss = { showAppLockSettings = false },
            onSave = { timeoutMillis ->
                onLockTimeoutChanged(timeoutMillis)
                showAppLockSettings = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteListScreen(
    notes: List<Note>,
    syncState: SyncUiState,
    onCreate: () -> Unit,
    onOpen: (Note) -> Unit,
    onPin: (Note) -> Unit,
    onDelete: (Note) -> Unit,
    onAccount: () -> Unit,
    onAppLockSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Note?>(null) }
    val visibleNotes = remember(notes, query) {
        notes.asSequence()
            .filter { query.isBlank() || it.title.contains(query, true) || it.content.contains(query, true) }
            .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
            .toList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        Text("${notes.size} 条笔记 · ${syncState.message}", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    TextButton(onClick = onAppLockSettings) {
                        Text(stringResource(R.string.settings))
                    }
                    TextButton(onClick = onAccount) {
                        Text(
                            when {
                                syncState.username.isNotBlank() -> syncState.username
                                syncState.configured -> "连接中"
                                else -> "未登录"
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) { Text("＋", style = MaterialTheme.typography.headlineMedium) }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索笔记") },
                placeholder = { Text("标题或内容") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (visibleNotes.isEmpty()) {
                EmptyState(hasQuery = query.isNotBlank(), modifier = Modifier.weight(1f))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                    items(visibleNotes, key = { it.id }) { note ->
                        NoteCard(note, onOpen, onPin, { pendingDelete = note })
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条笔记？") },
            text = { Text("删除操作会在下次同步时发送到其他设备。") },
            confirmButton = {
                TextButton(onClick = { onDelete(note); pendingDelete = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun NoteCard(note: Note, onOpen: (Note) -> Unit, onPin: (Note) -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(note) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.title.ifBlank { "无标题" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.sensitive) Text("🔒", modifier = Modifier.padding(start = 8.dp))
                if (note.pinned) Text("置顶", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (note.sensitive) "敏感内容已隐藏，点按查看" else note.content.ifBlank { "空白笔记" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(note.updatedAt), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onPin(note) }) { Text(if (note.pinned) "取消置顶" else "置顶") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun EmptyState(hasQuery: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (hasQuery) "没有找到相关笔记" else "还没有笔记", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(if (hasQuery) "换个关键词试试" else "点右下角的 ＋ 记录第一个想法")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(note: Note?, onSave: (String, String, Boolean) -> Unit) {
    var title by rememberSaveable(note?.id) { mutableStateOf(note?.title.orEmpty()) }
    var content by rememberSaveable(note?.id) { mutableStateOf(note?.content.orEmpty()) }
    var sensitive by rememberSaveable(note?.id) { mutableStateOf(note?.sensitive ?: false) }
    val saveAndClose = { onSave(title, content, sensitive) }
    BackHandler(onBack = saveAndClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (note == null) "新建笔记" else "编辑笔记") },
                navigationIcon = { TextButton(onClick = saveAndClose) { Text("返回") } },
                actions = { TextButton(onClick = saveAndClose) { Text("保存") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("标题") },
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = { Text("写下你的点子、想法或临时信息……") },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable { sensitive = !sensitive }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = sensitive, onCheckedChange = { sensitive = it })
                Column {
                    Text("敏感笔记", fontWeight = FontWeight.Medium)
                    Text("在列表中隐藏正文预览", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SyncSettingsDialog(
    syncState: SyncUiState,
    onDismiss: () -> Unit,
    onAuthenticate: (String, String, String, Boolean) -> Unit,
    onClear: () -> Unit,
) {
    var serverUrl by rememberSaveable { mutableStateOf(syncState.serverUrl) }
    var username by rememberSaveable { mutableStateOf(syncState.username) }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var registerMode by rememberSaveable { mutableStateOf(false) }
    var validationMessage by rememberSaveable { mutableStateOf("") }
    val submit = {
        validationMessage = when {
            !serverUrl.trim().startsWith("https://") -> "服务器地址必须使用 HTTPS"
            !username.trim().lowercase().matches(Regex("[a-z0-9][a-z0-9_-]{2,31}")) -> "用户名格式不正确"
            password.length !in 10..128 -> "密码长度须为 10–128 个字符"
            registerMode && password != confirmPassword -> "两次输入的密码不一致"
            else -> ""
        }
        if (validationMessage.isBlank()) {
            onAuthenticate(serverUrl, username, password, registerMode)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (registerMode) "注册同步账户" else "登录同步账户") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(if (registerMode) "设置自己的用户名和密码，注册后会自动登录并同步。" else "使用用户名和密码登录；密码不会保存在设备上。")
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://notes.example.com") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.lowercase() },
                    label = { Text("用户名") },
                    supportingText = { Text("3–32 位小写字母、数字、_ 或 -") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    supportingText = { Text("至少 10 个字符") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (registerMode) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("确认密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (validationMessage.isNotBlank()) {
                    Text(validationMessage, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = {
                    registerMode = !registerMode
                    validationMessage = ""
                }) {
                    Text(if (registerMode) "已有账户？返回登录" else "没有账户？立即注册")
                }
                if (syncState.configured) {
                    TextButton(onClick = onClear) { Text("退出当前同步账户") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit) { Text(if (registerMode) "注册并同步" else "登录并同步") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
@Composable
private fun ServerStatusDialog(
    syncState: SyncUiState,
    onDismiss: () -> Unit,
    onSync: () -> Unit,
    onSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(syncState.username.ifBlank { "同步账户" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = syncState.serverUrl.ifBlank { "未配置服务器" },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (syncState.healthySynced) "✓" else "○",
                        color = if (syncState.healthySynced) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(syncState.message, style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = onSync, enabled = !syncState.running) {
                        Text(if (syncState.running) "同步中" else "立即同步")
                    }
                    TextButton(onClick = onSettings) { Text("修改配置") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private data class LockTimeoutChoice(val timeoutMillis: Long, val labelResource: Int)

private val lockTimeoutChoices = listOf(
    LockTimeoutChoice(AppLockPreferences.TIMEOUT_IMMEDIATELY, R.string.timeout_immediately),
    LockTimeoutChoice(AppLockPreferences.TIMEOUT_ONE_MINUTE, R.string.timeout_one_minute),
    LockTimeoutChoice(AppLockPreferences.TIMEOUT_FIVE_MINUTES, R.string.timeout_five_minutes),
    LockTimeoutChoice(AppLockPreferences.TIMEOUT_FIFTEEN_MINUTES, R.string.timeout_fifteen_minutes),
    LockTimeoutChoice(AppLockPreferences.TIMEOUT_THIRTY_MINUTES, R.string.timeout_thirty_minutes),
    LockTimeoutChoice(AppLockPreferences.TIMEOUT_ONE_HOUR, R.string.timeout_one_hour),
    LockTimeoutChoice(AppLockPreferences.TIMEOUT_FOUR_HOURS, R.string.timeout_four_hours),
)

@Composable
private fun AppLockSettingsDialog(
    currentTimeoutMillis: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
) {
    var selectedTimeoutMillis by rememberSaveable(currentTimeoutMillis) {
        mutableStateOf(currentTimeoutMillis)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_lock_settings_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.app_lock_timeout_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                lockTimeoutChoices.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTimeoutMillis = choice.timeoutMillis }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedTimeoutMillis == choice.timeoutMillis,
                            onClick = { selectedTimeoutMillis = choice.timeoutMillis },
                        )
                        Text(stringResource(choice.labelResource))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selectedTimeoutMillis) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun LockScreen(message: String, canAuthenticate: Boolean, onUnlock: () -> Unit, onUnsafeContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔐", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.app_locked_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            if (canAuthenticate) Button(onClick = onUnlock) { Text("解锁") }
            else Button(onClick = onUnsafeContinue) { Text("继续使用（不安全）") }
        }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(timestamp))