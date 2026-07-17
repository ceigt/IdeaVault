package com.example.ideavault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ideavault.data.EncryptedNoteStore
import com.example.ideavault.data.Note
import com.example.ideavault.sync.SyncClient
import com.example.ideavault.sync.SyncConfig
import com.example.ideavault.sync.SyncConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class SyncUiState(
    val configured: Boolean = false,
    val running: Boolean = false,
    val serverUrl: String = "",
    val message: String = "尚未配置同步",
)

class NoteViewModel(application: Application) : AndroidViewModel(application) {
    private val store = EncryptedNoteStore(application)
    private val syncConfigStore = SyncConfigStore(application)
    private val syncClient = SyncClient()
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()
    private val _syncState = MutableStateFlow(SyncUiState())
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                store.load() to syncConfigStore.load()
            }
            _notes.value = loaded.first
            loaded.second?.let { config ->
                _syncState.value = SyncUiState(
                    configured = true,
                    serverUrl = config.serverUrl,
                    message = "已配置 · 点按同步",
                )
            }
        }
    }

    fun save(noteId: String?, title: String, content: String, sensitive: Boolean) {
        val cleanTitle = title.trim()
        val cleanContent = content.trim()
        if (cleanTitle.isBlank() && cleanContent.isBlank()) return

        val now = System.currentTimeMillis()
        val current = _notes.value.toMutableList()
        val index = noteId?.let { id -> current.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) {
            current[index] = current[index].copy(
                title = cleanTitle,
                content = cleanContent,
                sensitive = sensitive,
                updatedAt = now,
                deleted = false,
            )
        } else {
            current += Note(
                id = UUID.randomUUID().toString(),
                title = cleanTitle,
                content = cleanContent,
                sensitive = sensitive,
                pinned = false,
                createdAt = now,
                updatedAt = now,
            )
        }
        updateAndPersist(current)
    }

    fun togglePinned(noteId: String) = transform(noteId) {
        it.copy(pinned = !it.pinned, updatedAt = System.currentTimeMillis())
    }

    fun delete(noteId: String) = transform(noteId) {
        it.copy(deleted = true, updatedAt = System.currentTimeMillis())
    }

    fun configureAndSync(serverUrl: String, accessToken: String, encryptionPassphrase: String) {
        val config = SyncConfig(
            serverUrl = serverUrl.trim().trimEnd('/'),
            accessToken = accessToken.trim(),
            encryptionPassphrase = encryptionPassphrase,
        )
        if (config.serverUrl.isBlank() || config.accessToken.length < 16 || encryptionPassphrase.length < 8) {
            _syncState.value = _syncState.value.copy(message = "请填写有效地址、访问令牌和至少 8 位加密口令")
            return
        }
        viewModelScope.launch(Dispatchers.IO) { syncConfigStore.save(config) }
        _syncState.value = SyncUiState(true, serverUrl = config.serverUrl, message = "配置已保存")
        performSync(config)
    }

    fun syncNow() {
        if (_syncState.value.running) return
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) { syncConfigStore.load() }
            if (config == null) {
                _syncState.value = SyncUiState(message = "请先配置同步服务器")
            } else {
                performSync(config)
            }
        }
    }

    fun clearSyncConfig() {
        viewModelScope.launch(Dispatchers.IO) { syncConfigStore.clear() }
        _syncState.value = SyncUiState(message = "同步配置已移除，本地笔记不受影响")
    }

    private fun performSync(config: SyncConfig) {
        if (_syncState.value.running) return
        _syncState.value = _syncState.value.copy(running = true, message = "正在端到端加密同步…")
        val snapshot = _notes.value
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { syncClient.sync(config, snapshot) }
            }.onSuccess { remote ->
                val remoteById = remote.associateBy { it.id }
                val merged = (_notes.value.map { it.id } + remoteById.keys)
                    .distinct()
                    .mapNotNull { id ->
                        val local = _notes.value.firstOrNull { it.id == id }
                        val server = remoteById[id]
                        when {
                            local == null -> server
                            server == null -> local
                            local.updatedAt > server.updatedAt -> local
                            else -> server
                        }
                    }
                _notes.value = merged
                withContext(Dispatchers.IO) { store.save(merged) }
                val count = merged.count { !it.deleted }
                _syncState.value = SyncUiState(
                    configured = true,
                    serverUrl = config.serverUrl,
                    message = "同步完成 · $count 条笔记",
                )
            }.onFailure { error ->
                _syncState.value = SyncUiState(
                    configured = true,
                    serverUrl = config.serverUrl,
                    message = "同步失败：${error.message ?: "未知错误"}",
                )
            }
        }
    }

    private fun transform(noteId: String, operation: (Note) -> Note) {
        updateAndPersist(_notes.value.map { if (it.id == noteId) operation(it) else it })
    }

    private fun updateAndPersist(value: List<Note>) {
        _notes.value = value
        viewModelScope.launch(Dispatchers.IO) { store.save(value) }
    }
}