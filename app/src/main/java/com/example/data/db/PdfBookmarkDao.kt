package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfBookmarkDao {

    @Query("SELECT * FROM pdf_bookmarks WHERE uriString = :uriString ORDER BY pageIndex ASC")
    fun getBookmarksForUri(uriString: String): Flow<List<PdfBookmarkEntity>>

    @Query("SELECT * FROM pdf_bookmarks WHERE uriString = :uriString AND pageIndex = :pageIndex LIMIT 1")
    suspend fun getBookmarkForPage(uriString: String, pageIndex: Int): PdfBookmarkEntity?

    @Query("SELECT pageIndex FROM pdf_bookmarks WHERE uriString = :uriString")
    fun getBookmarkedPageIndices(uriString: String): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: PdfBookmarkEntity): Long

    @Query("DELETE FROM pdf_bookmarks WHERE uriString = :uriString AND pageIndex = :pageIndex")
    suspend fun deleteBookmarkByPage(uriString: String, pageIndex: Int)

    @Delete
    suspend fun deleteBookmark(bookmark: PdfBookmarkEntity)

    @Query("DELETE FROM pdf_bookmarks WHERE uriString = :uriString")
    suspend fun deleteAllBookmarksForUri(uriString: String)
}
