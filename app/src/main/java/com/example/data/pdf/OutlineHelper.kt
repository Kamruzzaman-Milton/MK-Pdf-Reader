package com.example.data.pdf

/**
 * Helper utilities for PDF Table of Contents / Outline navigation and tracking.
 * Provides chapter-related operations:
 * - Finding the active current chapter for the reading page.
 * - Finding the next / previous chapter.
 * - Finding the root parent chapter for the current page.
 * - Hierarchy flattening and formatting.
 */
object OutlineHelper {

    /**
     * Finds the currently active chapter for the given 0-indexed page number.
     * Selects the deepest/latest matching chapter in the outline hierarchy whose target page <= currentPageIndex.
     */
    fun getCurrentChapter(currentPageIndex: Int, outline: List<PdfOutlineItem>): PdfOutlineItem? {
        if (outline.isEmpty()) return null
        val flat = flatten(outline)
        return flat.lastOrNull { it.pageIndex <= currentPageIndex }
    }

    /**
     * Determines the root chapter for the current reading page.
     */
    fun getRootChapter(currentPageIndex: Int, outline: List<PdfOutlineItem>): PdfOutlineItem? {
        val current = getCurrentChapter(currentPageIndex, outline) ?: return null
        return outline.find { root ->
            root.id == current.id || containsItem(root, current.id)
        }
    }

    /**
     * Finds the next chapter after current page.
     */
    fun getNextChapter(currentPageIndex: Int, outline: List<PdfOutlineItem>): PdfOutlineItem? {
        if (outline.isEmpty()) return null
        val flat = flatten(outline)
        return flat.firstOrNull { it.pageIndex > currentPageIndex }
    }

    /**
     * Flattens the recursive outline tree into a flat linear sequence.
     */
    fun flatten(outline: List<PdfOutlineItem>): List<PdfOutlineItem> {
        val result = mutableListOf<PdfOutlineItem>()
        fun collect(items: List<PdfOutlineItem>) {
            for (item in items) {
                result.add(item)
                collect(item.children)
            }
        }
        collect(outline)
        return result
    }

    private fun containsItem(parent: PdfOutlineItem, targetId: String): Boolean {
        for (child in parent.children) {
            if (child.id == targetId || containsItem(child, targetId)) return true
        }
        return false
    }
}
