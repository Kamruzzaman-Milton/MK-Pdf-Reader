package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfRecentDao {
    @Query("SELECT * FROM recent_pdfs WHERE is_recent = 1 ORDER BY recent_timestamp DESC")
    fun getAllRecents(): Flow<List<PdfRecentItem>>

    @Query("SELECT * FROM recent_pdfs WHERE is_recent = 1 ORDER BY recent_timestamp DESC")
    fun getAllRecentsSync(): List<PdfRecentItem>

    @Query("SELECT * FROM recent_pdfs WHERE pdf_path = :path OR uriString = :path OR localFilePath = :path LIMIT 1")
    fun getRecentByPath(path: String): PdfRecentItem?

    @Query("SELECT * FROM recent_pdfs WHERE pdf_path = :uriString OR uriString = :uriString OR localFilePath = :uriString LIMIT 1")
    fun getRecentByUri(uriString: String): PdfRecentItem?

    @Query("SELECT * FROM recent_pdfs WHERE localFilePath = :localPath OR pdf_path = :localPath LIMIT 1")
    fun getRecentByLocalPath(localPath: String): PdfRecentItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(item: PdfRecentItem)

    @Query("UPDATE recent_pdfs SET lastReadPage = :lastReadPage, scrollOffsetFraction = :scrollOffset, recent_timestamp = :timestamp, readingMode = :readingMode, is_recent = 1 WHERE pdf_path = :path OR uriString = :path")
    fun updateReadingProgress(
        path: String,
        lastReadPage: Int,
        scrollOffset: Float,
        readingMode: String,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM recent_pdfs WHERE pdf_path = :path OR uriString = :path")
    fun deleteRecent(path: String)

    @Query("DELETE FROM recent_pdfs")
    fun clearAll()
}
