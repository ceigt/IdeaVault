package com.example.ideavault.data

data class Note(
    val id: String,
    val title: String,
    val content: String,
    val sensitive: Boolean,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)