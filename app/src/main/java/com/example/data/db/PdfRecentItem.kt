package com.example.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SQLite Database Entity for Recent PDFs (AppDB architecture).
 *
 * Requirements:
 * - pdf_path: Primary Key (absolute path or canonical document URI)
 * - is_recent: Boolean flag indicating if file is currently in recents
 * - recent_timestamp: Long timestamp (System.currentTimeMillis()) when opened/updated
 */
@Entity(tableName = "recent_pdfs")
data class PdfRecentItem(
    @PrimaryKey
    @ColumnInfo(name = "pdf_path")
    val pdf_path: String,

    @ColumnInfo(name = "is_recent")
    val is_recent: Boolean = true,

    @ColumnInfo(name = "recent_timestamp")
    val recent_timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "uriString")
    val uriString: String = pdf_path,

    @ColumnInfo(name = "fileName")
    val fileName: String = "",

    @ColumnInfo(name = "fileSize")
    val fileSize: Long = 0L,

    @ColumnInfo(name = "pageCount")
    val pageCount: Int = 0,

    @ColumnInfo(name = "lastReadPage")
    val lastReadPage: Int = 0,

    @ColumnInfo(name = "scrollOffsetFraction")
    val scrollOffsetFraction: Float = 0f,

    @ColumnInfo(name = "readingMode")
    val readingMode: String = "VERTICAL",

    @ColumnInfo(name = "thumbnailPath")
    val thumbnailPath: String? = null,

    @ColumnInfo(name = "localFilePath")
    val localFilePath: String? = null
) {
    // Backwards-compatibility property for existing UI
    val lastOpenedTimestamp: Long
        get() = recent_timestamp
}
