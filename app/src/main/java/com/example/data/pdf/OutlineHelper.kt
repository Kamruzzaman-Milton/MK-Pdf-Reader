package com.example.data.pdf

import java.util.Locale
import kotlin.math.max

/**
 * Precomputed immutable lookup index for fast O(1) chapter tracking, flat lazy-list
 * visibility filtering, and background-thread search over large (2,000–5,000+ item) outlines.
 */
class OutlineIndex(
    val flat: List<PdfOutlineItem>,
    val parentIndex: IntArray,
    val lowerTitles: Array<String>,
    val chapterForPage: IntArray,
    internal val expandableIds: List<String> = flat.mapNotNull { if (it.children.isNotEmpty()) it.id else null },
    internal val collapsedAllMap: Map<String, Boolean> = expandableIds.associateWith { false }
)

/**
 * Helper utilities for PDF Table of Contents / Outline navigation and tracking.
 * Provides chapter-related operations:
 * - Finding the active current chapter for the reading page.
 * - Finding the next / previous chapter.
 * - Finding the root parent chapter for the current page.
 * - Hierarchy flattening and precomputed OutlineIndex construction.
 */
object OutlineHelper {

    /**
     * Builds an immutable [OutlineIndex] once per outline (intended to be called on Dispatchers.Default).
     */
    fun buildIndex(outline: List<PdfOutlineItem>, totalPages: Int): OutlineIndex {
        val safePages = totalPages.coerceAtLeast(0)
        if (outline.isEmpty()) {
            return OutlineIndex(
                flat = emptyList(),
                parentIndex = IntArray(0),
                lowerTitles = emptyArray(),
                chapterForPage = IntArray(safePages) { -1 },
                expandableIds = emptyList(),
                collapsedAllMap = emptyMap()
            )
        }

        val flat = ArrayList<PdfOutlineItem>(outline.size * 2)
        val parents = ArrayList<Int>(outline.size * 2)
        val stack = ArrayList<Pair<PdfOutlineItem, Int>>(64)
        for (i in outline.size - 1 downTo 0) {
            stack.add(outline[i] to -1)
        }

        while (stack.isNotEmpty()) {
            val (item, parentIdx) = stack.removeAt(stack.size - 1)
            val myIdx = flat.size
            flat.add(item)
            parents.add(parentIdx)
            val children = item.children
            for (i in children.size - 1 downTo 0) {
                stack.add(children[i] to myIdx)
            }
        }

        val n = flat.size
        val parentIndex = IntArray(n) { parents[it] }
        val lowerTitles = Array(n) { i -> flat[i].title.lowercase(Locale.ROOT) }
        val expandableIds = ArrayList<String>()
        for (i in 0 until n) {
            if (flat[i].children.isNotEmpty()) {
                expandableIds.add(flat[i].id)
            }
        }
        val collapsedAllMap = HashMap<String, Boolean>(expandableIds.size)
        for (id in expandableIds) {
            collapsedAllMap[id] = false
        }

        val chapterForPage = IntArray(safePages) { -1 }
        if (safePages > 0) {
            for (i in 0 until n) {
                val p = flat[i].pageIndex
                if (p in 0 until safePages) {
                    chapterForPage[p] = max(chapterForPage[p], i)
                }
            }
            var runningMax = -1
            for (page in 0 until safePages) {
                if (chapterForPage[page] > runningMax) {
                    runningMax = chapterForPage[page]
                }
                chapterForPage[page] = runningMax
            }
        }

        return OutlineIndex(
            flat = flat,
            parentIndex = parentIndex,
            lowerTitles = lowerTitles,
            chapterForPage = chapterForPage,
            expandableIds = expandableIds,
            collapsedAllMap = collapsedAllMap
        )
    }

    /**
     * O(1) lookup of the currently active chapter for [currentPageIndex] using a precomputed [OutlineIndex].
     */
    fun getCurrentChapter(currentPageIndex: Int, index: OutlineIndex): PdfOutlineItem? {
        if (index.flat.isEmpty() || index.chapterForPage.isEmpty()) return null
        val clamped = currentPageIndex.coerceIn(0, index.chapterForPage.size - 1)
        val flatIdx = index.chapterForPage[clamped]
        return if (flatIdx in index.flat.indices) index.flat[flatIdx] else null
    }

    /**
     * Finds the root chapter for [currentPageIndex] by walking up [OutlineIndex.parentIndex].
     */
    fun getRootChapter(currentPageIndex: Int, index: OutlineIndex): PdfOutlineItem? {
        if (index.flat.isEmpty() || index.chapterForPage.isEmpty()) return null
        val clamped = currentPageIndex.coerceIn(0, index.chapterForPage.size - 1)
        var idx = index.chapterForPage[clamped]
        if (idx !in index.flat.indices) return null
        while (index.parentIndex[idx] >= 0) {
            idx = index.parentIndex[idx]
        }
        return index.flat[idx]
    }

    /**
     * Finds the next chapter after [currentPageIndex] using [OutlineIndex.flat] without re-flattening.
     */
    fun getNextChapter(currentPageIndex: Int, index: OutlineIndex): PdfOutlineItem? {
        if (index.flat.isEmpty()) return null
        for (item in index.flat) {
            if (item.pageIndex > currentPageIndex) return item
        }
        return null
    }

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
     * Flattens the recursive outline tree into a flat linear sequence iteratively.
     */
    fun flatten(outline: List<PdfOutlineItem>): List<PdfOutlineItem> {
        if (outline.isEmpty()) return emptyList()
        val result = ArrayList<PdfOutlineItem>(outline.size * 2)
        val stack = ArrayList<PdfOutlineItem>(64)
        for (i in outline.size - 1 downTo 0) {
            stack.add(outline[i])
        }
        while (stack.isNotEmpty()) {
            val item = stack.removeAt(stack.size - 1)
            result.add(item)
            val children = item.children
            for (i in children.size - 1 downTo 0) {
                stack.add(children[i])
            }
        }
        return result
    }

    private fun containsItem(parent: PdfOutlineItem, targetId: String): Boolean {
        val stack = ArrayList<PdfOutlineItem>(parent.children.size)
        stack.addAll(parent.children)
        while (stack.isNotEmpty()) {
            val child = stack.removeAt(stack.size - 1)
            if (child.id == targetId) return true
            if (child.children.isNotEmpty()) {
                stack.addAll(child.children)
            }
        }
        return false
    }
}
