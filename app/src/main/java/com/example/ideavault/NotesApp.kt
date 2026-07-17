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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ideavault.data.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotesApp(viewModel: NoteViewModel = viewModel()) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val editingNote = notes.firstOrNull { it.id == editingId }

    if (creating || editingNote != null) {
        EditorScreen(
            note = editingNote,
            onClose = {
                creating = false
                editingId = null
            },
            onSave = { title, content, sensitive ->
                viewModel.save(editingId, title, content, sensitive)
                creating = false
                editingId = null
            },
        )
    } else {
        NoteListScreen(
            notes = notes,
            onCreate = { creating = true },
            onOpen = { editingId = it.id },
            onPin = { viewModel.togglePinned(it.id) },
            onDelete = { viewModel.delete(it.id) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteListScreen(
    notes: List<Note>,
    onCreate: () -> Unit,
    onOpen: (Note) -> Unit,
    onPin: (Note) -> Unit,
    onDelete: (Note) -> Unit,
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
            TopAppBar(title = {
                Column {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                    Text("${notes.size} 条笔记 · 本地加密", style = MaterialTheme.typography.labelMedium)
                }
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) { Text("＋", style = MaterialTheme.typography.headlineMedium) }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
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
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
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
            text = { Text("删除后无法恢复。") },
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
private fun EditorScreen(note: Note?, onClose: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
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
                keyboardActions = KeyboardActions(onDone = { }),
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
fun LockScreen(message: String, canAuthenticate: Boolean, onUnlock: () -> Unit, onUnsafeContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔐", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.app_locked_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            if (canAuthenticate) {
                Button(onClick = onUnlock) { Text("解锁") }
            } else {
                Button(onClick = onUnsafeContinue) { Text("继续使用（不安全）") }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(timestamp))
