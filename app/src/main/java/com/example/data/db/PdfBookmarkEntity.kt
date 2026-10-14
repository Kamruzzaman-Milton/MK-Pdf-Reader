package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pdf_bookmarks",
    indices = [Index(value = ["uriString", "pageIndex"], unique = true)]
)
data class PdfBookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uriString: String,
    val pageIndex: Int, // 0-indexed
    val pageNumber: Int = pageIndex + 1, // 1-indexed for display
    val title: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
