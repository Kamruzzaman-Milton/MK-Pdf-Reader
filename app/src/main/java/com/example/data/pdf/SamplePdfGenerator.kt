package com.example.data.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object SamplePdfGenerator {

    suspend fun getOrCreateSamplePdf(context: Context): Uri = withContext(Dispatchers.IO) {
        val sampleFile = File(context.filesDir, "MK_PDF_Reader_Guide.pdf")
        if (sampleFile.exists() && sampleFile.length() > 1024) {
            return@withContext Uri.fromFile(sampleFile)
        }

        val document = PdfDocument()
        val pageWidth = 595 // A4 standard width in points (72 dpi)
        val pageHeight = 842 // A4 standard height in points

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(211, 47, 47) // Primary Red
            textSize = 24f
            isFakeBoldText = true
        }

        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 41, 59)
            textSize = 14f
            isFakeBoldText = true
        }

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(51, 65, 85)
            textSize = 11f
        }

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 235, 238)
            style = Paint.Style.FILL
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(229, 115, 115)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(148, 163, 184)
            textSize = 9f
            textAlign = Paint.Align.CENTER
        }

        // Page 1: Welcome & Quick Overview
        createPage(document, 1, pageWidth, pageHeight) { canvas ->
            // Header accent banner
            val headerPaint = Paint().apply { color = Color.rgb(211, 47, 47) }
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), 90f, headerPaint)

            val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 26f
                isFakeBoldText = true
            }
            canvas.drawText("MK PDF Reader", 40f, 55f, headerTextPaint)

            val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 205, 210)
                textSize = 12f
            }
            canvas.drawText("Lightweight, Fast, and Battery-Optimized Document Engine", 40f, 75f, tagPaint)

            // Content
            canvas.drawText("Welcome to MK PDF Reader", 40f, 135f, titlePaint)
            canvas.drawText("High-performance document viewing built for modern Android devices.", 40f, 160f, subtitlePaint)

            var y = 200f
            val paragraphs = listOf(
                "MK PDF Reader is engineered from the ground up to handle both everyday files and",
                "very large multi-hundred-page technical manuals with effortless fluidity.",
                "",
                "Key Capabilities at a glance:",
                " • On-Demand Asynchronous Page Rendering: Never bottlenecks device memory.",
                " • Smooth Double-Tap Zoom & Pinch-to-Zoom: Examine micro-details effortlessly.",
                " • Dual Reading Modes: Seamlessly switch between continuous vertical scroll & horizontal paging.",
                " • Instant Page-Seek Slider & Jump Dialog: Navigate large documents in milliseconds.",
                " • Fullscreen Reading Mode: Clean, distraction-free reading with automatic control hiding.",
                " • Smart Keep-Awake: Extends display timeout by 5 minutes while actively reading.",
                " • Session Persistence: Automatically remembers your last-read page and scroll position.",
                " • Zero Ads, Zero Tracking, 100% Offline & Private."
            )

            for (line in paragraphs) {
                if (line.startsWith("Key Capabilities")) {
                    canvas.drawText(line, 40f, y, subtitlePaint)
                } else {
                    canvas.drawText(line, 40f, y, bodyPaint)
                }
                y += 18f
            }

            // Highlight Box
            val boxRect = RectF(40f, y + 20f, (pageWidth - 40).toFloat(), y + 105f)
            canvas.drawRoundRect(boxRect, 8f, 8f, boxPaint)
            canvas.drawRoundRect(boxRect, 8f, 8f, borderPaint)

            canvas.drawText("💡 Quick Navigation Tip", 55f, y + 45f, subtitlePaint)
            canvas.drawText("Tap anywhere on the reader canvas to toggle fullscreen mode and hide all toolbars.", 55f, y + 68f, bodyPaint)
            canvas.drawText("Use the slider at the bottom or click the page indicator to jump to any page.", 55f, y + 86f, bodyPaint)
        }

        // Page 2: Gesture & Zooming Showcase
        createPage(document, 2, pageWidth, pageHeight) { canvas ->
            canvas.drawText("Page 2 — Gesture & Zooming Capabilities", 40f, 60f, titlePaint)
            canvas.drawText("Precision zooming and panning without frame drops", 40f, 85f, subtitlePaint)

            var y = 120f
            val lines = listOf(
                "You can interact with documents using familiar, fluid mobile gestures:",
                "",
                "1. Double-Tap Zoom:",
                "   Double-tap anywhere on the page to instantly toggle between standard 1.0x view",
                "   and a crisp 2.5x zoomed-in detail view.",
                "",
                "2. Pinch-to-Zoom:",
                "   Pinch outward with two fingers to zoom up to 400% (4.0x) magnification.",
                "   Pinch inward to zoom back out.",
                "",
                "3. 2D Panning:",
                "   When zoomed in, drag with your finger to pan smoothly across charts, blueprints, and tables.",
                "",
                "4. Zoom Reset:",
                "   Tap the reset button in the top toolbar to instantly restore normal scale."
            )
            for (line in lines) {
                if (line.startsWith("1.") || line.startsWith("2.") || line.startsWith("3.") || line.startsWith("4.")) {
                    canvas.drawText(line, 40f, y, subtitlePaint)
                } else {
                    canvas.drawText(line, 40f, y, bodyPaint)
                }
                y += 18f
            }

            // Test visual grid for zoom testing
            y += 20f
            val gridPaint = Paint().apply {
                color = Color.rgb(241, 245, 249)
                style = Paint.Style.FILL
            }
            canvas.drawRect(40f, y, (pageWidth - 40).toFloat(), y + 160f, gridPaint)

            val gridLinePaint = Paint().apply {
                color = Color.rgb(203, 213, 225)
                strokeWidth = 1f
            }
            for (gx in 40..pageWidth - 40 step 30) {
                canvas.drawLine(gx.toFloat(), y, gx.toFloat(), y + 160f, gridLinePaint)
            }
            for (gy in y.toInt()..(y + 160).toInt() step 30) {
                canvas.drawLine(40f, gy.toFloat(), (pageWidth - 40).toFloat(), gy.toFloat(), gridLinePaint)
            }

            val testLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(211, 47, 47)
                textSize = 12f
                isFakeBoldText = true
            }
            canvas.drawText("🔎 Zoom Resolution Test Target [100% - 400%]", 55f, y + 25f, testLabelPaint)
            canvas.drawText("Tiny fine print: Micro-typography and vectorized glyphs remain sharp.", 55f, y + 50f, bodyPaint)
            canvas.drawText("Mathematical Formula: E = mc² | ∫₀^∞ e^(-x²) dx = √π / 2", 55f, y + 75f, subtitlePaint)
            canvas.drawText("RGB Color Calibration: [Red #D32F2F] [Amber #FFA000] [Teal #00897B]", 55f, y + 100f, bodyPaint)
            canvas.drawText("Try double-tapping directly inside this grid area!", 55f, y + 130f, subtitlePaint)
        }

        // Page 3: Dual Reading Modes
        createPage(document, 3, pageWidth, pageHeight) { canvas ->
            canvas.drawText("Page 3 — Dual Reading Modes", 40f, 60f, titlePaint)
            canvas.drawText("Choose between continuous scroll and book-style paging", 40f, 85f, subtitlePaint)

            var y = 125f
            val modeLines = listOf(
                "MK PDF Reader provides two distinct reading paradigms tailored for different types of content:",
                "",
                "1. Continuous Vertical Scroll Mode (Default):",
                "   • Perfect for articles, reports, code documents, and long papers.",
                "   • Scroll continuously from page to page without manual turning.",
                "   • The bottom slider and page indicator update in real-time as you scroll.",
                "",
                "2. Horizontal Page-by-Page Pager Mode:",
                "   • Ideal for books, magazines, slide presentations, and comic strips.",
                "   • Swipe left or right with smooth animated transitions.",
                "   • One page is centered and framed at a time.",
                "",
                "How to Switch Modes:",
                "Tap the reading mode toggle button located in the bottom control bar to switch instantly.",
                "In fullscreen mode, the button hides automatically to give you 100% of the screen canvas."
            )

            for (line in modeLines) {
                if (line.startsWith("1.") || line.startsWith("2.") || line.startsWith("How to Switch")) {
                    canvas.drawText(line, 40f, y, subtitlePaint)
                } else {
                    canvas.drawText(line, 40f, y, bodyPaint)
                }
                y += 18f
            }

            // Visual diagram of two modes
            y += 25f
            val cardRect1 = RectF(50f, y, 250f, y + 140f)
            val cardRect2 = RectF(290f, y, 490f, y + 140f)

            canvas.drawRoundRect(cardRect1, 10f, 10f, boxPaint)
            canvas.drawRoundRect(cardRect1, 10f, 10f, borderPaint)
            canvas.drawRoundRect(cardRect2, 10f, 10f, boxPaint)
            canvas.drawRoundRect(cardRect2, 10f, 10f, borderPaint)

            canvas.drawText("Vertical Mode ↕", 70f, y + 35f, subtitlePaint)
            canvas.drawText("Continuous stream", 70f, y + 60f, bodyPaint)
            canvas.drawText("Natural fluid scroll", 70f, y + 80f, bodyPaint)
            canvas.drawText("Great for manuals", 70f, y + 100f, bodyPaint)

            canvas.drawText("Horizontal Mode ↔", 310f, y + 35f, subtitlePaint)
            canvas.drawText("Page-by-page swipe", 310f, y + 60f, bodyPaint)
            canvas.drawText("Focus on one page", 310f, y + 80f, bodyPaint)
            canvas.drawText("Great for e-books", 310f, y + 100f, bodyPaint)
        }

        // Page 4: Page Seeking & Jump Dialog
        createPage(document, 4, pageWidth, pageHeight) { canvas ->
            canvas.drawText("Page 4 — Instant Page Navigation", 40f, 60f, titlePaint)
            canvas.drawText("Hop directly to any location in massive documents", 40f, 85f, subtitlePaint)

            var y = 130f
            val seekLines = listOf(
                "When reading large files (such as 500+ page books or spec sheets), navigating quickly is essential.",
                "",
                "Interactive Horizontal Slider:",
                " • Drag the bottom slider left or right to scrub through pages with live visual count.",
                " • Release to jump immediately to the target page.",
                "",
                "Direct Page Jump Dialog:",
                " • Tap the 'Page X / Y' badge in the bottom toolbar or click the Jump action in the top menu.",
                " • Enter any page number directly.",
                " • Use the quick +10 / -10 or first/last buttons for rapid stepping.",
                " • Press 'Go' to instantly transport your view.",
                "",
                "Session Memory & Resume Reading:",
                " • Whenever you close MK PDF Reader or switch to another app, your exact page",
                "   and reading mode are saved securely in the local database.",
                " • Reopening the document automatically restores your last-read position."
            )
            for (line in seekLines) {
                if (line.startsWith("Interactive") || line.startsWith("Direct Page") || line.startsWith("Session Memory")) {
                    canvas.drawText(line, 40f, y, subtitlePaint)
                } else {
                    canvas.drawText(line, 40f, y, bodyPaint)
                }
                y += 18f
            }
        }

        // Page 5: Smart Screen Keep-Awake & Battery Efficiency
        createPage(document, 5, pageWidth, pageHeight) { canvas ->
            canvas.drawText("Page 5 — Smart Screen Keep-Awake", 40f, 60f, titlePaint)
            canvas.drawText("Read comfortably without unexpected screen blackouts", 40f, 85f, subtitlePaint)

            var y = 130f
            val powerLines = listOf(
                "Normal Android devices turn off the screen after 30 to 60 seconds of inactivity.",
                "While reading dense text or study material, this often causes frustrating interruptions.",
                "",
                "How MK PDF Reader manages screen wake:",
                " • Smart 5-Minute Inactivity Extension: While actively reading, the app requests",
                "   temporary keep-screen-on window flags.",
                " • Whenever you touch, scroll, zoom, or flip a page, the 5-minute timer refreshes.",
                " • If you step away from your device for more than 5 minutes without touching the screen,",
                "   the wake flag is safely released to protect your device battery.",
                " • Zero Permanent System Changes: The app does NOT alter your Android system settings.",
                " • When you exit the reader or switch apps, all wake locks are instantly cleared."
            )
            for (line in powerLines) {
                if (line.startsWith("How MK PDF")) {
                    canvas.drawText(line, 40f, y, subtitlePaint)
                } else {
                    canvas.drawText(line, 40f, y, bodyPaint)
                }
                y += 18f
            }
        }

        // Page 6: Memory Architecture & Large PDF Optimization
        createPage(document, 6, pageWidth, pageHeight) { canvas ->
            canvas.drawText("Page 6 — Memory Optimization", 40f, 60f, titlePaint)
            canvas.drawText("How MK PDF Reader avoids OutOfMemoryError (OOM)", 40f, 85f, subtitlePaint)

            var y = 130f
            val memLines = listOf(
                "Traditional PDF viewers often crash on Android when opening 100MB+ documents because",
                "they attempt to rasterize too many pages simultaneously into RAM.",
                "",
                "MK PDF Reader's Architecture:",
                " 1. Native Stream Descriptor: Reads file pages directly from disk without loading the entire binary.",
                " 2. Dynamic LRU Cache: Bounded memory footprint (1/8th max heap, capped at 64MB).",
                " 3. Page Eviction & Recycling: Background memory pressure automatically trims off-screen bitmaps.",
                " 4. Thread-Safe Mutex Engine: Coordinates hardware rendering calls without lock contention.",
                " 5. Asynchronous Coroutines: Pages render smoothly on IO threads without blocking Compose UI 60/120 FPS."
            )
            for (line in memLines) {
                if (line.startsWith("MK PDF Reader's Architecture:")) {
                    canvas.drawText(line, 40f, y, subtitlePaint)
                } else {
                    canvas.drawText(line, 40f, y, bodyPaint)
                }
                y += 18f
            }
        }

        // Page 7: File Support & External App Integration
        createPage(document, 7, pageWidth, pageHeight) { canvas ->
            canvas.drawText("Page 7 — File Picker & System Integration", 40f, 60f, titlePaint)
            canvas.drawText("Open any PDF seamlessly from anywhere on your device", 40f, 85f, subtitlePaint)

            var y = 130f
            val sysLines = listOf(
                "MK PDF Reader integrates with Android's system document ecosystem:",
                "",
                " • Android Storage Access Framework (SAF) File Picker:",
                "   Open files from Internal Storage, SD Card, Downloads, or Cloud Drives.",
                "",
                " • 'ACTION_VIEW' & 'ACTION_SEND' Intent Handling:",
                "   Open PDFs directly from Chrome downloads, Gmail attachments, WhatsApp, Telegram, or File Managers.",
                "",
                " • Persistent Permissions & Offline Cache:",
                "   Handles temporary content stream URIs safely with automatic cache fallback.",
                "",
                " • Recent Documents Library:",
                "   Your recent list maintains quick shortcuts with page progression, file size, and timestamp."
            )
            for (line in sysLines) {
                if (line.startsWith(" • ")) {
                    canvas.drawText(line, 40f, y, subtitlePaint)
                } else {
                    canvas.drawText(line, 40f, y, bodyPaint)
                }
                y += 18f
            }
        }

        // Page 8: Summary & Getting Started
        createPage(document, 8, pageWidth, pageHeight) { canvas ->
            canvas.drawText("Page 8 — Ready to Read!", 40f, 60f, titlePaint)
            canvas.drawText("Enjoy clean, fast, distraction-free document reading", 40f, 85f, subtitlePaint)

            var y = 130f
            val finalLines = listOf(
                "You are all set to experience high-speed PDF reading.",
                "",
                "Try experimenting right now on this sample document:",
                " • Double-tap to zoom into any text or diagram.",
                " • Pinch with two fingers to zoom up to 4x.",
                " • Switch reading modes using the bottom toolbar button.",
                " • Drag the bottom slider to jump between pages 1 and 8.",
                " • Tap the screen to enter clean fullscreen reading mode.",
                "",
                "Thank you for using MK PDF Reader!"
            )
            for (line in finalLines) {
                if (line.startsWith("Try experimenting") || line.startsWith("Thank you")) {
                    canvas.drawText(line, 40f, y, subtitlePaint)
                } else {
                    canvas.drawText(line, 40f, y, bodyPaint)
                }
                y += 18f
            }

            // Footer note
            val bannerRect = RectF(40f, y + 40f, (pageWidth - 40).toFloat(), y + 110f)
            canvas.drawRoundRect(bannerRect, 8f, 8f, boxPaint)
            canvas.drawRoundRect(bannerRect, 8f, 8f, borderPaint)
            canvas.drawText("MK PDF Reader", 55f, y + 70f, subtitlePaint)
            canvas.drawText("Crafted for Android • Kotlin • Jetpack Compose • Material 3", 55f, y + 92f, bodyPaint)
        }

        // Write to file
        FileOutputStream(sampleFile).use { out ->
            document.writeTo(out)
        }
        document.close()

        // Attach rich PDF bookmarks/outlines using PDFBox
        try {
            PDFBoxResourceLoader.init(context.applicationContext)
            val pdDoc = PDDocument.load(sampleFile)
            val outline = PDDocumentOutline()
            pdDoc.documentCatalog.documentOutline = outline

            val chapters = listOf(
                Pair("Welcome & Overview", 0),
                Pair("Chapter 1: Multi-Directional Viewing Modes", 1),
                Pair("Chapter 2: Intelligent Zoom & Pan Controls", 2),
                Pair("Chapter 3: Instant Page Jumper & Navigation", 3),
                Pair("Chapter 4: Document Metadata & Insights", 4),
                Pair("Chapter 5: Local Database & History Tracking", 5),
                Pair("Chapter 6: High Performance Caching", 6),
                Pair("Chapter 7: About MK PDF Reader", 7)
            )

            for ((title, pageIndex) in chapters) {
                if (pageIndex < pdDoc.numberOfPages) {
                    val item = PDOutlineItem()
                    item.title = title
                    val dest = PDPageFitWidthDestination()
                    dest.page = pdDoc.getPage(pageIndex)
                    item.destination = dest
                    outline.addLast(item)
                }
            }

            pdDoc.save(sampleFile)
            pdDoc.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Uri.fromFile(sampleFile)
    }

    private inline fun createPage(
        document: PdfDocument,
        pageNumber: Int,
        width: Int,
        height: Int,
        drawContent: (Canvas) -> Unit
    ) {
        val pageInfo = PdfDocument.PageInfo.Builder(width, height, pageNumber).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        // Canvas background
        canvas.drawColor(Color.WHITE)

        // Draw content
        drawContent(canvas)

        // Draw footer page number
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(148, 163, 184)
            textSize = 9f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("MK PDF Reader • Page $pageNumber of 8", width / 2f, height - 25f, footerPaint)

        document.finishPage(page)
    }
}
