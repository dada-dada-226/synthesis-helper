package com.gamehelper.synthesis

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 棋盘识别器：OCR + 多重解析策略
 */
object BoardAnalyzer {

    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    /**
     * 从截图中提取 4x4 棋盘。
     * @param screenshot 全屏截图（会被回收）
     * @param boardRect 手动指定的棋盘区域，null 则自动检测
     */
    suspend fun extractBoard(
        screenshot: Bitmap,
        boardRect: Rect? = null
    ): Array<IntArray> = withContext(Dispatchers.Default) {

        val cropArea = boardRect ?: autoDetectBoard(screenshot)
        val cropped = safeCrop(screenshot, cropArea)

        // OCR
        val inputImage = InputImage.fromBitmap(cropped, 0)
        val ocrText = suspendCoroutine { cont ->
            recognizer.process(inputImage)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { cont.resume("") }
        }

        val grid = parseGrid(ocrText)

        // 如果识别到的数字太少，尝试降级策略
        val count = grid.sumOf { row -> row.count { it > 0 } }
        if (count < 6) {
            // 放大图像重试
            val scaled = Bitmap.createScaledBitmap(cropped,
                (cropped.width * 1.5f).toInt(),
                (cropped.height * 1.5f).toInt(), true)
            val retryImage = InputImage.fromBitmap(scaled, 0)
            val retryText = suspendCoroutine { cont ->
                recognizer.process(retryImage)
                    .addOnSuccessListener { result -> cont.resume(result.text) }
                    .addOnFailureListener { cont.resume("") }
            }
            val grid2 = parseGrid(retryText)
            val count2 = grid2.sumOf { row -> row.count { it > 0 } }
            if (count2 > count) return@withContext grid2
        }

        cropped.recycle()
        grid
    }

    /** 安全裁剪，不越界 */
    private fun safeCrop(src: Bitmap, rect: Rect): Bitmap {
        val l = rect.left.coerceIn(0, src.width - 1)
        val t = rect.top.coerceIn(0, src.height - 1)
        val r = rect.right.coerceIn(l + 1, src.width)
        val b = rect.bottom.coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    /** 自动检测棋盘：屏幕中央偏上的最大方形区域 */
    private fun autoDetectBoard(bitmap: Bitmap): Rect {
        val w = bitmap.width
        val h = bitmap.height

        // 游戏棋盘通常在屏幕上半部
        val centerX = w / 2
        val centerY = (h * 0.38).toInt()
        val size = (minOf(w, h) * 0.58).toInt()

        val half = size / 2
        return Rect(
            (centerX - half).coerceAtLeast(0),
            (centerY - half).coerceAtLeast(0),
            (centerX + half).coerceAtMost(w),
            (centerY + half).coerceAtMost(h)
        )
    }

    // ============ 解析策略 ============

    private fun parseGrid(text: String): Array<IntArray> {
        if (text.isBlank()) return emptyGrid()

        // 策略1：按行解析（每行有4个数字）
        val grid1 = parseByLines(text)
        val n1 = grid1.sumOf { row -> row.count { it > 0 } }
        if (n1 >= 12) return grid1

        // 策略2：全局提取所有数字，按位置排列
        val grid2 = parseAllNumbers(text)
        val n2 = grid2.sumOf { row -> row.count { it > 0 } }
        if (n2 > n1) return grid2

        return if (n1 >= n2) grid1 else grid2
    }

    /** 策略1：按文本行解析 */
    private fun parseByLines(text: String): Array<IntArray> {
        val grid = Array(4) { IntArray(4) { 0 } }
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val numRegex = Regex("""(\d+)""")

        var row = 0
        for (line in lines) {
            val nums = numRegex.findAll(line)
                .map { it.value.toIntOrNull() ?: 0 }
                .filter { it in 1..18 }
                .toList()
            if (nums.isEmpty()) continue
            if (row >= 4) break

            for ((col, n) in nums.withIndex()) {
                if (col >= 4) break
                grid[row][col] = n
            }
            row++
        }
        return grid
    }

    /** 策略2：提取所有数字，按出现顺序填充 4x4 */
    private fun parseAllNumbers(text: String): Array<IntArray> {
        val grid = Array(4) { IntArray(4) { 0 } }
        val allNums = Regex("""\b(1[0-8]|[1-9])\b""")
            .findAll(text)
            .map { it.value.toInt() }
            .filter { it in 1..18 }
            .toList()

        for ((i, n) in allNums.withIndex()) {
            if (i >= 16) break
            grid[i / 4][i % 4] = n
        }
        return grid
    }

    private fun emptyGrid() = Array(4) { IntArray(4) { 0 } }
}
