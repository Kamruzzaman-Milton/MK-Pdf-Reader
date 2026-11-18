package com.example.data.pdf

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin, zero-native-dependency PDF Table of Contents (Outline) reader.
 * Reads directly from a FileChannel in small windows so 100+ MB PDFs never load into RAM.
 */
object RawPdfOutlineReader {

    private const val INITIAL_WINDOW_BYTES = 16 * 1024
    private const val MAX_WINDOW_BYTES = 8 * 1024 * 1024
    private const val MAX_STREAM_BYTES = 32 * 1024 * 1024
    private const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
    private const val TAIL_READ_BYTES = 2048
    private const val OBJ_STM_LRU_SIZE = 8
    private const val MAX_OUTLINE_DEPTH = 30
    private const val MAX_OUTLINE_ITEMS = 100_000
    private const val MAX_REF_DEPTH = 16

    private val threadDiagnostic = ThreadLocal<String?>()

    internal fun consumeLastDiagnostic(): String? {
        val msg = threadDiagnostic.get()
        threadDiagnostic.remove()
        return msg
    }

    private fun fail(reason: String): List<PdfOutlineItem>? {
        threadDiagnostic.set(reason)
        return null
    }

    /**
     * Reads the PDF outline / bookmarks directly from [pfd] without closing [pfd].
     * Returns null on any failure, empty outline, or encrypted document.
     */
    fun read(pfd: ParcelFileDescriptor, totalPages: Int): List<PdfOutlineItem>? {
        threadDiagnostic.remove()
        return try {
            // Do NOT close FileInputStream or FileChannel: caller owns pfd.
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val fileSize = channel.size()
            if (fileSize <= 0L) {
                return fail("Raw: empty file")
            }
            val reader = PdfFileReader(channel, fileSize)
            reader.readOutline(totalPages)
        } catch (t: Throwable) {
            fail("Raw: ${t::class.java.simpleName}")
        }
    }

    // ---- PDF Value Types ----

    private data class Ref(val num: Int)
    private data class Name(val value: String)
    private class PdfString(val bytes: ByteArray)
    private class PdfStream(val dict: Map<String, Any?>, val bytes: ByteArray)
    private data class Keyword(val text: String)

    private sealed interface XrefEntry {
        data class Type1(val offset: Long) : XrefEntry
        data class Type2(val objStmNum: Int, val indexInStream: Int) : XrefEntry
        data object Free : XrefEntry
    }

    private class NeedsMoreBytesException : RuntimeException()
    private class ParseException(msg: String) : RuntimeException(msg)

    private class ParsedObjStm(
        val first: Int,
        val pairs: List<Pair<Int, Int>>,
        val data: ByteArray
    )

    private class MutableOutlineNode(
        val id: String,
        val title: String,
        val pageIndex: Int,
        val level: Int,
        val children: MutableList<MutableOutlineNode> = mutableListOf()
    )

    private data class WalkFrame(
        val ref: Ref,
        val level: Int,
        val indexInParent: Int,
        val parentNode: MutableOutlineNode?,
        val parentPath: String
    )

    // ---- Per-Document Reader Context ----

    private class PdfFileReader(
        private val channel: FileChannel,
        private val fileSize: Long
    ) {
        private val xref = HashMap<Int, XrefEntry>(1024)
        private val objStmCache = object : LinkedHashMap<Int, ParsedObjStm>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ParsedObjStm>?): Boolean {
                return size > OBJ_STM_LRU_SIZE
            }
        }
        private val activeObjNums = HashSet<Int>()
        private var pageMap: HashMap<Int, Int>? = null

        fun readOutline(totalPages: Int): List<PdfOutlineItem>? {
            val startXrefOffset = findStartXref()
                ?: return fail("Raw: no startxref")

            val newestTrailer = loadXrefChain(startXrefOffset)
                ?: return null // threadDiagnostic already set

            if (newestTrailer.containsKey("Encrypt") && newestTrailer["Encrypt"] != null) {
                return fail("Raw: encrypted")
            }

            val rootDict = resolve(newestTrailer["Root"]) as? Map<*, *>
                ?: return fail("Raw: no /Root")

            @Suppress("UNCHECKED_CAST")
            val rootMap = rootDict as Map<String, Any?>

            val outlinesDict = resolve(rootMap["Outlines"]) as? Map<*, *>
                ?: return fail("Raw: no /Outlines")

            @Suppress("UNCHECKED_CAST")
            val outlinesMap = outlinesDict as Map<String, Any?>
            val firstRef = outlinesMap["First"] as? Ref
                ?: return fail("Raw: empty /Outlines")

            val items = walkOutlines(rootMap, firstRef, totalPages)
            if (items.isEmpty()) {
                return fail("Raw: empty outline")
            }
            return items
        }

        // ---- I/O Helper ----

        private fun readAt(pos: Long, len: Int): ByteArray {
            if (pos < 0L || pos >= fileSize || len <= 0) return ByteArray(0)
            val actualLen = min(len.toLong(), fileSize - pos).toInt()
            if (actualLen <= 0) return ByteArray(0)
            val buf = ByteArray(actualLen)
            val bb = ByteBuffer.wrap(buf)
            var currentPos = pos
            while (bb.hasRemaining()) {
                val n = channel.read(bb, currentPos)
                if (n <= 0) break
                currentPos += n
            }
            val readCount = bb.position()
            return if (readCount == actualLen) buf else buf.copyOf(readCount)
        }

        // ---- 2. Xref Reader ----

        private fun findStartXref(): Long? {
            val tailLen = min(TAIL_READ_BYTES.toLong(), fileSize).toInt()
            val tailStart = fileSize - tailLen
            val tail = readAt(tailStart, tailLen)
            if (tail.isEmpty()) return null

            val needle = "startxref".toByteArray(Charsets.US_ASCII)
            var matchPos = -1
            for (i in (tail.size - needle.size) downTo 0) {
                var matched = true
                for (j in needle.indices) {
                    if (tail[i + j] != needle[j]) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    val beforeOk = i == 0 || isWsOrDelim(tail[i - 1])
                    val afterIdx = i + needle.size
                    val afterOk = afterIdx >= tail.size || isWsOrDelim(tail[afterIdx])
                    if (beforeOk && afterOk) {
                        matchPos = i + needle.size
                        break
                    }
                }
            }
            if (matchPos < 0) return null

            var p = matchPos
            while (p < tail.size && isWs(tail[p])) p++
            val numStart = p
            while (p < tail.size && tail[p] in '0'.code.toByte()..'9'.code.toByte()) p++
            if (p == numStart) return null
            val offset = String(tail, numStart, p - numStart, Charsets.US_ASCII).toLongOrNull() ?: return null
            return if (offset in 0L until fileSize) offset else null
        }

        private fun loadXrefChain(startOffset: Long): Map<String, Any?>? {
            var newestTrailer: Map<String, Any?>? = null
            val visitedOffsets = HashSet<Long>()
            val stack = ArrayList<Long>()
            stack.add(startOffset)

            while (stack.isNotEmpty()) {
                val off = stack.removeAt(stack.size - 1)
                if (off < 0L || off >= fileSize || !visitedOffsets.add(off)) continue

                val trailer = parseXrefSectionAt(off) ?: continue
                if (trailer.containsKey("Encrypt") && trailer["Encrypt"] != null) {
                    threadDiagnostic.set("Raw: encrypted")
                    return null
                }
                if (newestTrailer == null) {
                    newestTrailer = trailer
                }

                // Push /Prev first, then /XRefStm so LIFO processes /XRefStm before /Prev
                val prevOff = (trailer["Prev"] as? Long)
                if (prevOff != null && prevOff in 0L until fileSize && prevOff !in visitedOffsets) {
                    stack.add(prevOff)
                }
                val xrefStmOff = (trailer["XRefStm"] as? Long)
                if (xrefStmOff != null && xrefStmOff in 0L until fileSize && xrefStmOff !in visitedOffsets) {
                    stack.add(xrefStmOff)
                }
            }

            if (newestTrailer == null) {
                threadDiagnostic.set("Raw: invalid xref")
            }
            return newestTrailer
        }

        private fun parseXrefSectionAt(offset: Long): Map<String, Any?>? {
            val probe = readAt(offset, 64)
            if (probe.isEmpty()) return null
            var p = 0
            while (p < probe.size && isWs(probe[p])) p++
            val isClassicXref = p + 4 <= probe.size &&
                probe[p] == 'x'.code.toByte() &&
                probe[p + 1] == 'r'.code.toByte() &&
                probe[p + 2] == 'e'.code.toByte() &&
                probe[p + 3] == 'f'.code.toByte() &&
                (p + 4 == probe.size || isWsOrDelim(probe[p + 4]))

            return if (isClassicXref) {
                parseClassicXref(offset + p)
            } else {
                parseXrefStream(offset)
            }
        }

        private fun parseClassicXref(xrefKeywordOffset: Long): Map<String, Any?>? {
            val scanner = ChannelScanner(channel, fileSize, xrefKeywordOffset)
            val kw = scanner.nextToken() ?: return null
            if (kw != "xref") return null

            while (true) {
                val firstTok = scanner.nextToken() ?: return null
                if (firstTok == "trailer") {
                    break
                }
                val startObj = firstTok.toLongOrNull() ?: return null
                val countTok = scanner.nextToken() ?: return null
                val count = countTok.toLongOrNull() ?: return null
                if (count < 0L || count > 10_000_000L) return null

                for (i in 0L until count) {
                    val offTok = scanner.nextToken() ?: return null
                    val genTok = scanner.nextToken() ?: return null
                    val flagTok = scanner.nextToken() ?: return null
                    val entryOff = offTok.toLongOrNull() ?: continue
                    genTok.toLongOrNull() ?: continue
                    val objNumLong = startObj + i
                    if (objNumLong in 0L..Int.MAX_VALUE.toLong()) {
                        val objNum = objNumLong.toInt()
                        if (flagTok == "n") {
                            xref.putIfAbsent(objNum, XrefEntry.Type1(entryOff))
                        } else if (flagTok == "f") {
                            xref.putIfAbsent(objNum, XrefEntry.Free)
                        }
                    }
                }
            }

            // Parse trailer dictionary starting from current file position
            val trailerStartPos = scanner.currentPos()
            var window = INITIAL_WINDOW_BYTES
            while (window <= MAX_WINDOW_BYTES) {
                val bytes = readAt(trailerStartPos, window)
                val truncated = trailerStartPos + bytes.size < fileSize
                try {
                    val tok = Tokenizer(bytes, 0, truncated)
                    @Suppress("UNCHECKED_CAST")
                    return tok.parseValue() as? Map<String, Any?>
                } catch (_: NeedsMoreBytesException) {
                    if (!truncated) return null
                    window *= 2
                } catch (_: Throwable) {
                    return null
                }
            }
            return null
        }

        private fun parseXrefStream(offset: Long): Map<String, Any?>? {
            val obj = parseObjectAtOffset(offset, -1) as? PdfStream ?: return null
            val dict = obj.dict
            val typeName = dict["Type"] as? Name
            if (typeName?.value != "XRef") return null

            val wList = (dict["W"] as? List<*>)?.mapNotNull { (it as? Long)?.toInt() } ?: return null
            if (wList.size < 3) return null
            val w0 = wList[0].coerceAtLeast(0)
            val w1 = wList[1].coerceAtLeast(0)
            val w2 = wList[2].coerceAtLeast(0)
            val entrySize = w0 + w1 + w2
            if (entrySize <= 0) return null

            val sizeVal = (dict["Size"] as? Long) ?: return null
            val indexRaw = (dict["Index"] as? List<*>)?.mapNotNull { it as? Long }
            val indexPairs: List<Long> = if (indexRaw != null && indexRaw.size >= 2) {
                indexRaw
            } else {
                listOf(0L, sizeVal)
            }

            val data = obj.bytes
            var dataPos = 0
            var pairIdx = 0
            while (pairIdx + 1 < indexPairs.size) {
                val startObj = indexPairs[pairIdx]
                val count = indexPairs[pairIdx + 1]
                pairIdx += 2
                if (count < 0L) continue

                for (i in 0L until count) {
                    if (dataPos + entrySize > data.size) break
                    val type = if (w0 == 0) 1L else readUInt(data, dataPos, w0)
                    val f1 = readUInt(data, dataPos + w0, w1)
                    val f2 = readUInt(data, dataPos + w0 + w1, w2)
                    dataPos += entrySize

                    val objNumLong = startObj + i
                    if (objNumLong !in 0L..Int.MAX_VALUE.toLong()) continue
                    val objNum = objNumLong.toInt()

                    when (type) {
                        0L -> xref.putIfAbsent(objNum, XrefEntry.Free)
                        1L -> xref.putIfAbsent(objNum, XrefEntry.Type1(f1))
                        2L -> {
                            if (f1 in 0L..Int.MAX_VALUE.toLong() && f2 in 0L..Int.MAX_VALUE.toLong()) {
                                xref.putIfAbsent(objNum, XrefEntry.Type2(f1.toInt(), f2.toInt()))
                            }
                        }
                    }
                }
            }

            return dict
        }

        private fun readUInt(data: ByteArray, pos: Int, width: Int): Long {
            var v = 0L
            for (i in 0 until width) {
                v = (v shl 8) or (data[pos + i].toInt() and 0xFF).toLong()
            }
            return v
        }

        // ---- 3. Object Loader ----

        private fun resolve(value: Any?): Any? {
            var current = value
            var depth = 0
            while (current is Ref && depth < MAX_REF_DEPTH) {
                current = getObject(current.num)
                depth++
            }
            return current
        }

        private fun getObject(num: Int): Any? {
            if (num < 0 || !activeObjNums.add(num)) return null
            try {
                return when (val entry = xref[num]) {
                    is XrefEntry.Type1 -> parseObjectAtOffset(entry.offset, num)
                    is XrefEntry.Type2 -> getObjectFromObjStm(entry.objStmNum, entry.indexInStream, num)
                    XrefEntry.Free, null -> null
                }
            } finally {
                activeObjNums.remove(num)
            }
        }

        private fun parseObjectAtOffset(offset: Long, expectedNum: Int): Any? {
            if (offset < 0L || offset >= fileSize) return null
            var window = INITIAL_WINDOW_BYTES
            while (window <= MAX_WINDOW_BYTES) {
                val bytes = readAt(offset, window)
                if (bytes.isEmpty()) return null
                val truncated = offset + bytes.size < fileSize
                try {
                    val tok = Tokenizer(bytes, 0, truncated)
                    val objNum = tok.parseValue() as? Long ?: return null
                    if (expectedNum >= 0 && objNum.toInt() != expectedNum) {
                        // Still parse if valid indirect object header, or mismatch
                    }
                    tok.parseValue() as? Long ?: return null // generation
                    val objKw = tok.parseValue()
                    if (objKw != Keyword("obj")) return null

                    val value = tok.parseValue()
                    if (value is Map<*, *>) {
                        val afterPos = tok.pos
                        val nextWord = tok.peekKeyword()
                        if (nextWord == "stream") {
                            tok.skipToAfterStreamKeyword()
                            val streamFileStart = offset + tok.pos
                            @Suppress("UNCHECKED_CAST")
                            val dict = value as Map<String, Any?>
                            val rawBytes = readStreamBytes(dict, streamFileStart) ?: return null
                            val decodedBytes = decodeStreamBytes(dict, rawBytes) ?: return null
                            return PdfStream(dict, decodedBytes)
                        }
                        tok.pos = afterPos
                    }
                    return value
                } catch (_: NeedsMoreBytesException) {
                    if (!truncated) return null
                    window *= 2
                } catch (_: Throwable) {
                    return null
                }
            }
            return null
        }

        private fun readStreamBytes(dict: Map<String, Any?>, streamFileStart: Long): ByteArray? {
            val lenObj = dict["Length"]
            val resolvedLen = when (lenObj) {
                is Long -> lenObj
                is Ref -> resolve(lenObj) as? Long
                else -> null
            }

            if (resolvedLen != null && resolvedLen in 0L..MAX_STREAM_BYTES.toLong() && streamFileStart + resolvedLen <= fileSize) {
                return readAt(streamFileStart, resolvedLen.toInt())
            }

            // Fallback: search for "endstream"
            val marker = "endstream".toByteArray(Charsets.US_ASCII)
            val chunkSize = 64 * 1024
            val overlap = marker.size
            val out = ByteArrayOutputStream()
            var pos = streamFileStart
            var tailCarry = ByteArray(0)

            while (pos < fileSize && out.size() < MAX_STREAM_BYTES) {
                val chunk = readAt(pos, chunkSize)
                if (chunk.isEmpty()) break
                val combined = if (tailCarry.isEmpty()) chunk else tailCarry + chunk
                val foundIdx = indexOfBytes(combined, marker)
                if (foundIdx >= 0) {
                    val toWrite = foundIdx - tailCarry.size
                    if (toWrite > 0) {
                        out.write(chunk, 0, toWrite)
                    } else if (toWrite < 0) {
                        // Marker spanned tailCarry and chunk; trim already written bytes
                        val currentBytes = out.toByteArray()
                        val trimLen = (currentBytes.size + toWrite).coerceAtLeast(0)
                        return trimTrailingEol(currentBytes, trimLen)
                    }
                    val result = out.toByteArray()
                    return trimTrailingEol(result, result.size)
                }

                if (combined.size > overlap) {
                    val safeWriteLen = chunk.size - overlap
                    if (tailCarry.isNotEmpty()) {
                        out.write(tailCarry)
                    }
                    if (safeWriteLen > 0) {
                        out.write(chunk, 0, safeWriteLen)
                        tailCarry = chunk.copyOfRange(safeWriteLen, chunk.size)
                    } else {
                        tailCarry = combined.copyOfRange(combined.size - overlap, combined.size)
                    }
                } else {
                    tailCarry = combined
                }
                pos += chunk.size
            }
            return null
        }

        private fun trimTrailingEol(bytes: ByteArray, length: Int): ByteArray {
            var end = length
            if (end >= 2 && bytes[end - 2] == '\r'.code.toByte() && bytes[end - 1] == '\n'.code.toByte()) {
                end -= 2
            } else if (end >= 1 && (bytes[end - 1] == '\n'.code.toByte() || bytes[end - 1] == '\r'.code.toByte())) {
                end -= 1
            }
            return if (end == bytes.size) bytes else bytes.copyOf(end)
        }

        private fun decodeStreamBytes(dict: Map<String, Any?>, rawBytes: ByteArray): ByteArray? {
            val filterObj = resolve(dict["Filter"])
            val filters: List<String> = when (filterObj) {
                is Name -> listOf(filterObj.value)
                is List<*> -> filterObj.mapNotNull { (resolve(it) as? Name)?.value }
                else -> emptyList()
            }

            if (filters.isEmpty()) return rawBytes

            var data = rawBytes
            for ((idx, filterName) in filters.withIndex()) {
                when (filterName) {
                    "FlateDecode", "Fl" -> {
                        data = inflateFlate(data) ?: return null
                        val parms = extractDecodeParms(dict["DecodeParms"], idx)
                        if (parms != null) {
                            data = applyPredictorIfNeeded(data, parms, dict)
                        }
                    }
                    else -> return null
                }
            }
            return data
        }

        private fun extractDecodeParms(rawParms: Any?, filterIdx: Int): Map<String, Any?>? {
            val resolved = resolve(rawParms) ?: return null
            @Suppress("UNCHECKED_CAST")
            return when (resolved) {
                is Map<*, *> -> resolved as Map<String, Any?>
                is List<*> -> resolve(resolved.getOrNull(filterIdx)) as? Map<String, Any?>
                else -> null
            }
        }

        private fun inflateFlate(input: ByteArray): ByteArray? {
            if (input.isEmpty()) return ByteArray(0)
            return inflateWithMode(input, nowrap = false) ?: inflateWithMode(input, nowrap = true)
        }

        private fun inflateWithMode(input: ByteArray, nowrap: Boolean): ByteArray? {
            val inflater = Inflater(nowrap)
            try {
                inflater.setInput(input)
                val out = ByteArrayOutputStream(max(256, input.size * 2))
                val buf = ByteArray(8192)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buf)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        out.write(buf, 0, count)
                        if (out.size() > MAX_DECOMPRESSED_BYTES) return null
                    }
                }
                return out.toByteArray()
            } catch (_: DataFormatException) {
                return null
            } finally {
                inflater.end()
            }
        }

        private fun applyPredictorIfNeeded(
            data: ByteArray,
            parms: Map<String, Any?>,
            streamDict: Map<String, Any?>
        ): ByteArray {
            val predictor = (resolve(parms["Predictor"]) as? Long)?.toInt() ?: 1
            if (predictor < 10 && predictor != 2) return data

            val defaultCols = (streamDict["W"] as? List<*>)?.sumOf { (it as? Long)?.toInt() ?: 0 } ?: 1
            val columns = ((resolve(parms["Columns"]) as? Long)?.toInt() ?: defaultCols).coerceAtLeast(1)
            val colors = ((resolve(parms["Colors"]) as? Long)?.toInt() ?: 1).coerceAtLeast(1)
            val bpc = ((resolve(parms["BitsPerComponent"]) as? Long)?.toInt() ?: 8).coerceAtLeast(1)

            val bpp = max(1, (colors * bpc + 7) / 8)
            val rowBytes = max(1, (columns * colors * bpc + 7) / 8)

            if (predictor == 2) {
                // TIFF Predictor 2 (8-bit components)
                val out = data.copyOf()
                var offset = 0
                while (offset + rowBytes <= out.size) {
                    for (i in bpp until rowBytes) {
                        out[offset + i] = ((out[offset + i].toInt() and 0xFF) + (out[offset + i - bpp].toInt() and 0xFF)).toByte()
                    }
                    offset += rowBytes
                }
                return out
            }

            // PNG Predictor (10..15)
            val stride = rowBytes + 1
            val rowCount = data.size / stride
            if (rowCount <= 0) return data

            val out = ByteArray(rowCount * rowBytes)
            val prevRow = ByteArray(rowBytes)
            val currRow = ByteArray(rowBytes)

            for (r in 0 until rowCount) {
                val srcBase = r * stride
                val filterType = data[srcBase].toInt() and 0xFF
                val payloadBase = srcBase + 1

                for (i in 0 until rowBytes) {
                    val raw = data[payloadBase + i].toInt() and 0xFF
                    val left = if (i >= bpp) currRow[i - bpp].toInt() and 0xFF else 0
                    val up = prevRow[i].toInt() and 0xFF
                    val upLeft = if (i >= bpp) prevRow[i - bpp].toInt() and 0xFF else 0

                    val decoded = when (filterType) {
                        0 -> raw
                        1 -> (raw + left) and 0xFF
                        2 -> (raw + up) and 0xFF
                        3 -> (raw + ((left + up) ushr 1)) and 0xFF
                        4 -> (raw + paethPredictor(left, up, upLeft)) and 0xFF
                        else -> raw
                    }
                    currRow[i] = decoded.toByte()
                }

                System.arraycopy(currRow, 0, out, r * rowBytes, rowBytes)
                System.arraycopy(currRow, 0, prevRow, 0, rowBytes)
            }
            return out
        }

        private fun paethPredictor(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = abs(p - a)
            val pb = abs(p - b)
            val pc = abs(p - c)
            return when {
                pa <= pb && pa <= pc -> a
                pb <= pc -> b
                else -> c
            }
        }

        private fun getObjectFromObjStm(objStmNum: Int, indexInStream: Int, expectedNum: Int): Any? {
            var parsed = objStmCache[objStmNum]
            if (parsed == null) {
                val stm = getObject(objStmNum) as? PdfStream ?: return null
                val n = (resolve(stm.dict["N"]) as? Long)?.toInt() ?: return null
                val first = (resolve(stm.dict["First"]) as? Long)?.toInt() ?: return null
                if (n <= 0 || first < 0 || first > stm.bytes.size) return null

                val headerTok = Tokenizer(stm.bytes, 0, truncated = false)
                val pairs = ArrayList<Pair<Int, Int>>(min(n, 4096))
                try {
                    for (i in 0 until n) {
                        val oNum = (headerTok.parseValue() as? Long)?.toInt() ?: break
                        val relOff = (headerTok.parseValue() as? Long)?.toInt() ?: break
                        pairs.add(oNum to relOff)
                    }
                } catch (_: Throwable) {
                    if (pairs.isEmpty()) return null
                }

                parsed = ParsedObjStm(first, pairs, stm.bytes)
                objStmCache[objStmNum] = parsed
            }

            val pair = parsed.pairs.getOrNull(indexInStream)
                ?.takeIf { it.first == expectedNum }
                ?: parsed.pairs.firstOrNull { it.first == expectedNum }
                ?: return null

            val objOffset = parsed.first + pair.second
            if (objOffset < 0 || objOffset >= parsed.data.size) return null
            return try {
                Tokenizer(parsed.data, objOffset, truncated = false).parseValue()
            } catch (_: Throwable) {
                null
            }
        }

        // ---- 4. Outline Walk & Destinations ----

        private fun walkOutlines(
            rootDict: Map<String, Any?>,
            firstRef: Ref,
            totalPages: Int
        ): List<PdfOutlineItem> {
            val rootNodes = mutableListOf<MutableOutlineNode>()
            val visited = HashSet<Int>()
            val stack = ArrayList<WalkFrame>(64)
            stack.add(
                WalkFrame(
                    ref = firstRef,
                    level = 0,
                    indexInParent = 0,
                    parentNode = null,
                    parentPath = "native_toc"
                )
            )

            val maxPageIdx = if (totalPages > 0) totalPages - 1 else Int.MAX_VALUE
            var lastResolvedPageIndex = 0
            var totalItems = 0

            while (stack.isNotEmpty() && totalItems < MAX_OUTLINE_ITEMS) {
                val frame = stack.removeAt(stack.size - 1)
                if (!visited.add(frame.ref.num)) continue

                @Suppress("UNCHECKED_CAST")
                val itemDict = getObject(frame.ref.num) as? Map<String, Any?> ?: continue

                val rawTitle = decodeTitle(resolve(itemDict["Title"]))
                val cleanedTitle = PdfOutlineParser.cleanTitle(rawTitle)

                val fallbackPage = (frame.parentNode?.pageIndex ?: lastResolvedPageIndex)
                    .let { if (frame.indexInParent == 0 && frame.parentNode != null) frame.parentNode.pageIndex else lastResolvedPageIndex }
                    .coerceIn(0, maxPageIdx)

                val resolvedPage = resolveDestinationPage(rootDict, itemDict, totalPages)
                val pageIndex = (resolvedPage ?: fallbackPage).coerceIn(0, maxPageIdx)
                lastResolvedPageIndex = pageIndex

                val itemId = "${frame.parentPath}_l${frame.level}_i${frame.indexInParent}_p${pageIndex}"
                val node = MutableOutlineNode(
                    id = itemId,
                    title = cleanedTitle,
                    pageIndex = pageIndex,
                    level = frame.level
                )

                if (frame.parentNode != null) {
                    frame.parentNode.children.add(node)
                } else {
                    rootNodes.add(node)
                }
                totalItems++

                // Push /Next first, then /First so LIFO pops /First before /Next (pre-order DFS)
                val nextRef = itemDict["Next"] as? Ref
                if (nextRef != null && nextRef.num !in visited) {
                    stack.add(
                        WalkFrame(
                            ref = nextRef,
                            level = frame.level,
                            indexInParent = frame.indexInParent + 1,
                            parentNode = frame.parentNode,
                            parentPath = frame.parentPath
                        )
                    )
                }

                if (frame.level < MAX_OUTLINE_DEPTH) {
                    val childRef = itemDict["First"] as? Ref
                    if (childRef != null && childRef.num !in visited) {
                        stack.add(
                            WalkFrame(
                                ref = childRef,
                                level = frame.level + 1,
                                indexInParent = 0,
                                parentNode = node,
                                parentPath = itemId
                            )
                        )
                    }
                }
            }

            return convertNodesToImmutable(rootNodes)
        }

        private fun convertNodesToImmutable(roots: List<MutableOutlineNode>): List<PdfOutlineItem> {
            fun convert(node: MutableOutlineNode): PdfOutlineItem {
                val childItems = if (node.children.isEmpty()) {
                    emptyList()
                } else {
                    node.children.map(::convert)
                }
                return PdfOutlineItem(
                    id = node.id,
                    title = node.title,
                    pageIndex = node.pageIndex,
                    level = node.level,
                    children = childItems
                )
            }
            return roots.map(::convert)
        }

        private fun decodeTitle(value: Any?): String? {
            val ps = value as? PdfString ?: return null
            val b = ps.bytes
            if (b.isEmpty()) return ""
            return when {
                b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() ->
                    String(b, 2, b.size - 2, Charsets.UTF_16BE)
                b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() ->
                    String(b, 2, b.size - 2, Charsets.UTF_16LE)
                b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
                    String(b, 3, b.size - 3, Charsets.UTF_8)
                else ->
                    String(b, Charsets.ISO_8859_1)
            }
        }

        private fun resolveDestinationPage(
            rootDict: Map<String, Any?>,
            itemDict: Map<String, Any?>,
            totalPages: Int
        ): Int? {
            var destObj: Any? = null
            if (itemDict.containsKey("Dest")) {
                destObj = resolve(itemDict["Dest"])
            }
            if (destObj == null && itemDict.containsKey("A")) {
                val action = resolve(itemDict["A"]) as? Map<*, *>
                if (action != null) {
                    val s = resolve(action["S"]) as? Name
                    if (s?.value == "GoTo") {
                        destObj = resolve(action["D"])
                    }
                }
            }
            if (destObj == null) return null

            return resolveDestValueToPage(rootDict, destObj, totalPages, depth = 0)
        }

        private fun resolveDestValueToPage(
            rootDict: Map<String, Any?>,
            destObj: Any?,
            totalPages: Int,
            depth: Int
        ): Int? {
            if (destObj == null || depth > 4) return null
            val resolved = resolve(destObj) ?: return null

            return when (resolved) {
                is List<*> -> {
                    val first = resolved.firstOrNull()
                    if (first is Ref) {
                        lookupPageIndex(rootDict, first.num, totalPages)
                    } else {
                        null
                    }
                }
                is Map<*, *> -> {
                    val d = resolved["D"] ?: return null
                    resolveDestValueToPage(rootDict, d, totalPages, depth + 1)
                }
                is Name, is PdfString -> {
                    val namedTarget = lookupNamedDestination(rootDict, resolved) ?: return null
                    resolveDestValueToPage(rootDict, namedTarget, totalPages, depth + 1)
                }
                else -> null
            }
        }

        private fun lookupNamedDestination(rootDict: Map<String, Any?>, key: Any): Any? {
            // 1. Root /Dests dictionary
            val rootDests = resolve(rootDict["Dests"]) as? Map<*, *>
            if (rootDests != null) {
                val direct = when (key) {
                    is Name -> rootDests[key.value]
                    is PdfString -> rootDests[String(key.bytes, Charsets.ISO_8859_1)]
                        ?: rootDests[String(key.bytes, Charsets.UTF_8)]
                    else -> null
                }
                if (direct != null) return direct
            }

            // 2. Root /Names -> /Dests name tree
            val namesDict = resolve(rootDict["Names"]) as? Map<*, *> ?: return null
            val destsTree = resolve(namesDict["Dests"]) as? Map<*, *> ?: return null

            val targetBytes = when (key) {
                is PdfString -> key.bytes
                is Name -> key.value.toByteArray(Charsets.UTF_8)
                else -> return null
            }

            return searchNameTree(destsTree, targetBytes)
        }

        private fun searchNameTree(rootNode: Map<*, *>, targetBytes: ByteArray): Any? {
            val stack = ArrayList<Map<*, *>>(16)
            val visitedNodes = HashSet<Map<*, *>>()
            stack.add(rootNode)

            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.size - 1)
                if (!visitedNodes.add(node)) continue

                val namesArr = resolve(node["Names"]) as? List<*>
                if (namesArr != null && namesArr.size >= 2) {
                    val found = searchNamesArray(namesArr, targetBytes)
                    if (found != null) return found
                }

                val kids = resolve(node["Kids"]) as? List<*> ?: continue
                for (i in kids.size - 1 downTo 0) {
                    val kidMap = resolve(kids[i]) as? Map<*, *> ?: continue
                    val limits = resolve(kidMap["Limits"]) as? List<*>
                    if (limits != null && limits.size >= 2) {
                        val minBytes = (resolve(limits[0]) as? PdfString)?.bytes
                        val maxBytes = (resolve(limits[1]) as? PdfString)?.bytes
                        if (minBytes != null && maxBytes != null) {
                            if (compareBytesUnsigned(targetBytes, minBytes) < 0 ||
                                compareBytesUnsigned(targetBytes, maxBytes) > 0
                            ) {
                                continue
                            }
                        }
                    }
                    stack.add(kidMap)
                }
            }
            return null
        }

        private fun searchNamesArray(namesArr: List<*>, targetBytes: ByteArray): Any? {
            val pairCount = namesArr.size / 2
            var lo = 0
            var hi = pairCount - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val k = resolve(namesArr[mid * 2]) as? PdfString
                if (k == null) break
                val cmp = compareBytesUnsigned(k.bytes, targetBytes)
                when {
                    cmp == 0 -> return namesArr[mid * 2 + 1]
                    cmp < 0 -> lo = mid + 1
                    else -> hi = mid - 1
                }
            }
            // Fallback linear scan in case a PDF has unsorted /Names keys
            for (i in 0 until pairCount) {
                val k = resolve(namesArr[i * 2]) as? PdfString ?: continue
                if (compareBytesUnsigned(k.bytes, targetBytes) == 0) {
                    return namesArr[i * 2 + 1]
                }
            }
            return null
        }

        private fun compareBytesUnsigned(a: ByteArray, b: ByteArray): Int {
            val minLen = min(a.size, b.size)
            for (i in 0 until minLen) {
                val av = a[i].toInt() and 0xFF
                val bv = b[i].toInt() and 0xFF
                if (av != bv) return av - bv
            }
            return a.size - b.size
        }

        // ---- 5. Lazy Page Map ----

        private fun lookupPageIndex(rootDict: Map<String, Any?>, pageObjNum: Int, totalPages: Int): Int? {
            val map = pageMap ?: buildPageMap(rootDict, totalPages).also { pageMap = it }
            return map[pageObjNum]
        }

        private fun buildPageMap(rootDict: Map<String, Any?>, totalPages: Int): HashMap<Int, Int> {
            val map = HashMap<Int, Int>(if (totalPages > 0) totalPages * 2 else 256)
            val pagesRef = rootDict["Pages"] as? Ref ?: return map

            val stack = ArrayList<Ref>(64)
            val visited = HashSet<Int>()
            stack.add(pagesRef)

            var runningIndex = 0
            val limit = if (totalPages > 0) totalPages else Int.MAX_VALUE

            while (stack.isNotEmpty() && runningIndex < limit) {
                val ref = stack.removeAt(stack.size - 1)
                if (!visited.add(ref.num)) continue

                val dict = getObject(ref.num) as? Map<*, *> ?: continue
                val kids = resolve(dict["Kids"]) as? List<*>
                if (dict.containsKey("Kids") && kids != null) {
                    for (i in kids.size - 1 downTo 0) {
                        val kidRef = kids[i] as? Ref ?: continue
                        if (kidRef.num !in visited) {
                            stack.add(kidRef)
                        }
                    }
                } else {
                    map[ref.num] = runningIndex++
                }
            }
            return map
        }
    }

    // ---- 1. Tokenizer ----

    private class Tokenizer(
        private val data: ByteArray,
        startPos: Int = 0,
        private val truncated: Boolean = false
    ) {
        var pos: Int = startPos

        fun parseValue(): Any? {
            skipWsAndComments()
            if (pos >= data.size) {
                if (truncated) throw NeedsMoreBytesException()
                throw ParseException("Unexpected EOF")
            }

            return when (val ch = data[pos].toInt() and 0xFF) {
                '<'.code -> {
                    if (pos + 1 >= data.size && truncated) throw NeedsMoreBytesException()
                    if (pos + 1 < data.size && data[pos + 1] == '<'.code.toByte()) {
                        parseDictionary()
                    } else {
                        parseHexString()
                    }
                }
                '('.code -> parseLiteralString()
                '/'.code -> parseName()
                '['.code -> parseArray()
                '+'.code, '-'.code, '.'.code, in '0'.code..'9'.code -> parseNumberOrRef()
                else -> {
                    val word = readWord()
                    when (word) {
                        "true" -> true
                        "false" -> false
                        "null" -> null
                        else -> Keyword(word)
                    }
                }
            }
        }

        fun peekKeyword(): String? {
            val saved = pos
            return try {
                skipWsAndComments()
                if (pos >= data.size) {
                    if (truncated) throw NeedsMoreBytesException()
                    return null
                }
                val ch = data[pos]
                if (isDelim(ch)) return null
                readWord()
            } finally {
                pos = saved
            }
        }

        fun skipToAfterStreamKeyword() {
            skipWsAndComments()
            val word = readWord()
            if (word != "stream") return
            if (pos >= data.size && truncated) throw NeedsMoreBytesException()
            if (pos < data.size && data[pos] == '\r'.code.toByte()) {
                pos++
                if (pos >= data.size && truncated) throw NeedsMoreBytesException()
                if (pos < data.size && data[pos] == '\n'.code.toByte()) {
                    pos++
                }
            } else if (pos < data.size && data[pos] == '\n'.code.toByte()) {
                pos++
            }
        }

        private fun parseDictionary(): Map<String, Any?> {
            pos += 2 // skip <<
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                skipWsAndComments()
                if (pos >= data.size) {
                    if (truncated) throw NeedsMoreBytesException()
                    break
                }
                if (data[pos] == '>'.code.toByte()) {
                    if (pos + 1 >= data.size && truncated) throw NeedsMoreBytesException()
                    if (pos + 1 < data.size && data[pos + 1] == '>'.code.toByte()) {
                        pos += 2
                        return map
                    }
                }
                val keyObj = parseValue()
                val key = (keyObj as? Name)?.value ?: break
                skipWsAndComments()
                if (pos + 1 < data.size && data[pos] == '>'.code.toByte() && data[pos + 1] == '>'.code.toByte()) {
                    pos += 2
                    return map
                }
                val value = parseValue()
                map[key] = value
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            pos++ // skip [
            val list = ArrayList<Any?>()
            while (true) {
                skipWsAndComments()
                if (pos >= data.size) {
                    if (truncated) throw NeedsMoreBytesException()
                    break
                }
                if (data[pos] == ']'.code.toByte()) {
                    pos++
                    return list
                }
                list.add(parseValue())
            }
            return list
        }

        private fun parseLiteralString(): PdfString {
            pos++ // skip (
            val out = ByteArrayOutputStream(32)
            var depth = 1
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xFF
                when (b) {
                    '('.code -> {
                        depth++
                        out.write(b)
                    }
                    ')'.code -> {
                        depth--
                        if (depth == 0) return PdfString(out.toByteArray())
                        out.write(b)
                    }
                    '\\'.code -> {
                        if (pos >= data.size) {
                            if (truncated) throw NeedsMoreBytesException()
                            break
                        }
                        val esc = data[pos++].toInt() and 0xFF
                        when (esc) {
                            'n'.code -> out.write('\n'.code)
                            'r'.code -> out.write('\r'.code)
                            't'.code -> out.write('\t'.code)
                            'b'.code -> out.write('\b'.code)
                            'f'.code -> out.write(0x0C)
                            '('.code -> out.write('('.code)
                            ')'.code -> out.write(')'.code)
                            '\\'.code -> out.write('\\'.code)
                            '\r'.code -> {
                                if (pos >= data.size && truncated) throw NeedsMoreBytesException()
                                if (pos < data.size && data[pos] == '\n'.code.toByte()) pos++
                            }
                            '\n'.code -> {
                                // line continuation
                            }
                            in '0'.code..'7'.code -> {
                                var oct = esc - '0'.code
                                if (pos < data.size && data[pos] in '0'.code.toByte()..'7'.code.toByte()) {
                                    oct = (oct shl 3) or (data[pos++].toInt() - '0'.code)
                                    if (pos < data.size && data[pos] in '0'.code.toByte()..'7'.code.toByte()) {
                                        oct = (oct shl 3) or (data[pos++].toInt() - '0'.code)
                                    }
                                }
                                if (pos >= data.size && truncated) throw NeedsMoreBytesException()
                                out.write(oct and 0xFF)
                            }
                            else -> out.write(esc)
                        }
                    }
                    else -> out.write(b)
                }
            }
            if (truncated) throw NeedsMoreBytesException()
            return PdfString(out.toByteArray())
        }

        private fun parseHexString(): PdfString {
            pos++ // skip <
            val out = ByteArrayOutputStream(32)
            var highNibble = -1
            while (pos < data.size) {
                val b = data[pos++]
                if (b == '>'.code.toByte()) {
                    if (highNibble >= 0) {
                        out.write(highNibble shl 4)
                    }
                    return PdfString(out.toByteArray())
                }
                if (isWs(b)) continue
                val hex = hexVal(b)
                if (hex >= 0) {
                    if (highNibble < 0) {
                        highNibble = hex
                    } else {
                        out.write((highNibble shl 4) or hex)
                        highNibble = -1
                    }
                }
            }
            if (truncated) throw NeedsMoreBytesException()
            if (highNibble >= 0) out.write(highNibble shl 4)
            return PdfString(out.toByteArray())
        }

        private fun parseName(): Name {
            pos++ // skip /
            val out = ByteArrayOutputStream(24)
            while (pos < data.size) {
                val b = data[pos]
                if (isWsOrDelim(b)) break
                pos++
                if (b == '#'.code.toByte() && pos + 1 < data.size) {
                    val h1 = hexVal(data[pos])
                    val h2 = hexVal(data[pos + 1])
                    if (h1 >= 0 && h2 >= 0) {
                        out.write((h1 shl 4) or h2)
                        pos += 2
                        continue
                    }
                } else if (b == '#'.code.toByte() && pos + 1 >= data.size && truncated) {
                    throw NeedsMoreBytesException()
                }
                out.write(b.toInt() and 0xFF)
            }
            if (pos >= data.size && truncated) throw NeedsMoreBytesException()
            return Name(String(out.toByteArray(), Charsets.UTF_8))
        }

        private fun parseNumberOrRef(): Any {
            val token = readWord()
            if ('.' in token) {
                return token.toDoubleOrNull() ?: 0.0
            }
            val num = token.toLongOrNull() ?: return Keyword(token)
            if (num < 0L || num > Int.MAX_VALUE.toLong()) return num

            val saved = pos
            skipWsAndComments()
            if (pos >= data.size) {
                if (truncated) throw NeedsMoreBytesException()
                pos = saved
                return num
            }
            val c1 = data[pos]
            if (c1 in '0'.code.toByte()..'9'.code.toByte()) {
                val genWord = readWord()
                val gen = genWord.toLongOrNull()
                if (gen != null && gen >= 0L) {
                    skipWsAndComments()
                    if (pos >= data.size) {
                        if (truncated) throw NeedsMoreBytesException()
                        pos = saved
                        return num
                    }
                    if (!isWsOrDelim(data[pos])) {
                        val rWord = readWord()
                        if (rWord == "R") {
                            return Ref(num.toInt())
                        }
                    }
                }
            }
            pos = saved
            return num
        }

        private fun readWord(): String {
            val start = pos
            while (pos < data.size && !isWsOrDelim(data[pos])) {
                pos++
            }
            if (pos >= data.size && truncated) throw NeedsMoreBytesException()
            if (pos == start) {
                pos++
                return String(data, start, 1, Charsets.ISO_8859_1)
            }
            return String(data, start, pos - start, Charsets.ISO_8859_1)
        }

        private fun skipWsAndComments() {
            while (pos < data.size) {
                val b = data[pos]
                if (isWs(b)) {
                    pos++
                } else if (b == '%'.code.toByte()) {
                    pos++
                    while (pos < data.size && data[pos] != '\n'.code.toByte() && data[pos] != '\r'.code.toByte()) {
                        pos++
                    }
                } else {
                    break
                }
            }
        }
    }

    // ---- Streaming Scanner for Classic Xref Tables ----

    private class ChannelScanner(
        private val channel: FileChannel,
        private val fileSize: Long,
        startOffset: Long
    ) {
        private var filePos = startOffset
        private var buf = ByteArray(64 * 1024)
        private var bufStartFilePos = startOffset
        private var bufLen = 0
        private var bufIdx = 0

        init {
            refill()
        }

        fun currentPos(): Long = bufStartFilePos + bufIdx

        fun nextToken(): String? {
            while (true) {
                val b = peekByte() ?: return null
                if (isWs(b)) {
                    bufIdx++
                } else if (b == '%'.code.toByte()) {
                    bufIdx++
                    while (true) {
                        val c = nextByte() ?: break
                        if (c == '\n'.code.toByte() || c == '\r'.code.toByte()) break
                    }
                } else {
                    break
                }
            }

            val sb = StringBuilder(16)
            while (true) {
                val b = peekByte() ?: break
                if (isWsOrDelim(b)) {
                    if (sb.isEmpty()) {
                        sb.append((b.toInt() and 0xFF).toChar())
                        bufIdx++
                    }
                    break
                }
                sb.append((b.toInt() and 0xFF).toChar())
                bufIdx++
            }
            return if (sb.isEmpty()) null else sb.toString()
        }

        private fun peekByte(): Byte? {
            if (bufIdx >= bufLen && !refill()) return null
            return buf[bufIdx]
        }

        private fun nextByte(): Byte? {
            if (bufIdx >= bufLen && !refill()) return null
            return buf[bufIdx++]
        }

        private fun refill(): Boolean {
            if (filePos >= fileSize) return false
            val toRead = min(buf.size.toLong(), fileSize - filePos).toInt()
            val bb = ByteBuffer.wrap(buf, 0, toRead)
            bufStartFilePos = filePos
            var readPos = filePos
            while (bb.hasRemaining()) {
                val n = channel.read(bb, readPos)
                if (n <= 0) break
                readPos += n
            }
            bufLen = bb.position()
            bufIdx = 0
            filePos += bufLen
            return bufLen > 0
        }
    }

    // ---- Byte Utilities ----

    private fun isWs(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v == 0x20 || v == 0x0A || v == 0x0D || v == 0x09 || v == 0x0C || v == 0x00
    }

    private fun isDelim(b: Byte): Boolean {
        return when (b.toInt() and 0xFF) {
            '('.code, ')'.code, '<'.code, '>'.code, '['.code, ']'.code, '{'.code, '}'.code, '/'.code, '%'.code -> true
            else -> false
        }
    }

    private fun isWsOrDelim(b: Byte): Boolean = isWs(b) || isDelim(b)

    private fun hexVal(b: Byte): Int {
        return when (val c = b.toInt() and 0xFF) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        val limit = haystack.size - needle.size
        for (i in 0..limit) {
            var matched = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }
}
