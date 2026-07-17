package com.example.ideavault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ideavault.data.EncryptedNoteStore
import com.example.ideavault.data.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class NoteViewModel(application: Application) : AndroidViewModel(application) {
    private val store = EncryptedNoteStore(application)
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    init {
        viewModelScope.launch {
            _notes.value = withContext(Dispatchers.IO) { store.load() }
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

    fun togglePinned(noteId: String) = transform(noteId) { it.copy(pinned = !it.pinned) }

    fun delete(noteId: String) {
        updateAndPersist(_notes.value.filterNot { it.id == noteId })
    }

    private fun transform(noteId: String, operation: (Note) -> Note) {
        updateAndPersist(_notes.value.map { if (it.id == noteId) operation(it) else it })
    }

    private fun updateAndPersist(value: List<Note>) {
        _notes.value = value
        viewModelScope.launch(Dispatchers.IO) { store.save(value) }
    }
}
