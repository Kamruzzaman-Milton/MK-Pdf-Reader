package com.example.data.pdf

import android.net.Uri

data class PdfDocumentInfo(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val pageCount: Int,
    val isSample: Boolean = false
)

enum class ReadingMode {
    VERTICAL,
    HORIZONTAL
}
