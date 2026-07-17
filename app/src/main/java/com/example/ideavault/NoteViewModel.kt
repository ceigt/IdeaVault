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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val username: String = "",
    val healthySynced: Boolean = false,
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
    private var autoSyncJob: Job? = null
    private var syncRequestedWhileRunning = false

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() to syncConfigStore.load() }
            _notes.value = loaded.first
            loaded.second?.let { config ->
                _syncState.value = SyncUiState(
                    configured = true,
                    serverUrl = config.serverUrl,
                    message = "自动同步已开启",
                )
                performSync(config)
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

    fun authenticateAndSync(serverUrl: String, username: String, password: String, register: Boolean) {
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        val normalizedUsername = username.trim().lowercase()
        if (!normalizedUrl.startsWith("https://")) {
            _syncState.value = _syncState.value.copy(healthySynced = false, message = "服务器地址必须使用 HTTPS")
            return
        }
        if (!normalizedUsername.matches(Regex("[a-z0-9][a-z0-9_-]{2,31}"))) {
            _syncState.value = _syncState.value.copy(healthySynced = false, message = "用户名格式不正确")
            return
        }
        if (password.length !in 10..128) {
            _syncState.value = _syncState.value.copy(healthySynced = false, message = "密码长度须为 10–128 个字符")
            return
        }
        val previous = _syncState.value
        _syncState.value = SyncUiState(
            running = true,
            serverUrl = normalizedUrl,
            username = normalizedUsername,
            message = if (register) "正在注册…" else "正在登录…",
        )
        viewModelScope.launch {
            try {
                val auth = withContext(Dispatchers.IO) {
                    syncClient.authenticate(normalizedUrl, normalizedUsername, password, register)
                }
                val config = SyncConfig(normalizedUrl, auth.accessToken)
                withContext(Dispatchers.IO) { syncConfigStore.save(config) }
                _syncState.value = SyncUiState(
                    configured = true,
                    serverUrl = normalizedUrl,
                    username = auth.username,
                    message = if (register) "注册成功，准备同步" else "登录成功，准备同步",
                )
                performSync(config)
            } catch (error: Exception) {
                _syncState.value = previous.copy(
                    running = false,
                    healthySynced = false,
                    message = "${if (register) "注册" else "登录"}失败：${error.message ?: "未知错误"}",
                )
            }
        }
    }

    fun configureAndSync(serverUrl: String, accessToken: String) {
        val config = SyncConfig(
            serverUrl = serverUrl.trim().trimEnd('/'),
            accessToken = accessToken.trim(),
        )
        if (!config.serverUrl.startsWith("https://") || config.accessToken.length < 32) {
            _syncState.value = _syncState.value.copy(message = "请填写 HTTPS 地址和有效访问令牌")
            return
        }
        viewModelScope.launch(Dispatchers.IO) { syncConfigStore.save(config) }
        _syncState.value = SyncUiState(true, serverUrl = config.serverUrl, message = "自动同步已开启")
        performSync(config)
    }

    fun syncNow() {
        if (_syncState.value.running) {
            syncRequestedWhileRunning = true
            return
        }
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) { syncConfigStore.load() }
            if (config == null) _syncState.value = SyncUiState(message = "请先配置同步服务器")
            else performSync(config)
        }
    }

    fun clearSyncConfig() {
        autoSyncJob?.cancel()
        syncRequestedWhileRunning = false
        viewModelScope.launch(Dispatchers.IO) { syncConfigStore.clear() }
        _syncState.value = SyncUiState(message = "同步配置已移除，本地笔记不受影响")
    }

    private fun scheduleAutoSync() {
        if (!_syncState.value.configured) return
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            delay(AUTO_SYNC_DELAY_MS)
            syncNow()
        }
    }

    private fun performSync(config: SyncConfig) {
        if (_syncState.value.running) {
            syncRequestedWhileRunning = true
            return
        }
        _syncState.value = _syncState.value.copy(running = true, message = "正在自动同步…")
        val snapshot = _notes.value
        viewModelScope.launch {
            try {
                val syncResult = withContext(Dispatchers.IO) { syncClient.sync(config, snapshot) }
                val remoteById = syncResult.notes.associateBy { it.id }
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
                _syncState.value = SyncUiState(
                    configured = true,
                    serverUrl = config.serverUrl,
                    username = syncResult.username,
                    healthySynced = true,
                    message = "自动同步完成 · ${merged.count { !it.deleted }} 条笔记",
                )
            } catch (error: Exception) {
                _syncState.value = SyncUiState(
                    configured = true,
                    serverUrl = config.serverUrl,
                    username = _syncState.value.username,
                    healthySynced = false,
                    message = "同步失败：${error.message ?: "未知错误"}",
                )
            } finally {
                if (syncRequestedWhileRunning) {
                    syncRequestedWhileRunning = false
                    scheduleAutoSync()
                }
            }
        }
    }

    private fun transform(noteId: String, operation: (Note) -> Note) {
        updateAndPersist(_notes.value.map { if (it.id == noteId) operation(it) else it })
    }

    private fun updateAndPersist(value: List<Note>) {
        _notes.value = value
        viewModelScope.launch(Dispatchers.IO) { store.save(value) }
        scheduleAutoSync()
    }

    private companion object {
        const val AUTO_SYNC_DELAY_MS = 900L
    }
}