package com.gamehelper.synthesis

/**
 * AI 引擎：分析棋盘并返回最佳滑动方向。
 * 算法移植自 Web 版，适配 1~18 线性合成规则。
 */
object AIEngine {

    private const val SIZE = 4

    // 方向映射
    enum class Direction(val label: String, val arrow: String) {
        UP("👆 向上", "⬆️"),
        DOWN("👇 向下", "⬇️"),
        LEFT("👈 向左", "⬅️"),
        RIGHT("👉 向右", "➡️")
    }

    // ============ 游戏逻辑 ============

    /** 一行向左压缩合并，返回 [新行, 得分] */
    private fun slideRowLeft(row: IntArray): Pair<IntArray, Int> {
        var arr = row.filter { it != 0 }.toMutableList()
        var score = 0
        var i = 0
        while (i < arr.size - 1) {
            if (arr[i] == arr[i + 1] && arr[i] < 18) {
                arr[i] += 1
                score += arr[i]
                arr.removeAt(i + 1)
            }
            i++
        }
        while (arr.size < SIZE) arr.add(0)
        return Pair(arr.toIntArray(), score)
    }

    private fun slideRowRight(row: IntArray): Pair<IntArray, Int> {
        val rev = row.reversedArray()
        val (slid, score) = slideRowLeft(rev)
        return Pair(slid.reversedArray(), score)
    }

    private fun cloneGrid(grid: Array<IntArray>): Array<IntArray> {
        return Array(SIZE) { r -> grid[r].copyOf() }
    }

    private fun transpose(grid: Array<IntArray>): Array<IntArray> {
        return Array(SIZE) { r -> IntArray(SIZE) { c -> grid[c][r] } }
    }

    /**
     * 模拟滑动，返回 [新棋盘, 合并得分, 是否有效移动]
     */
    fun simulate(
        grid: Array<IntArray>,
        dir: Direction
    ): Triple<Array<IntArray>, Int, Boolean> {
        var g = cloneGrid(grid)
        var totalScore = 0

        when (dir) {
            Direction.UP -> {
                g = transpose(g)
                for (r in 0 until SIZE) {
                    val (newRow, s) = slideRowLeft(g[r])
                    g[r] = newRow; totalScore += s
                }
                g = transpose(g)
            }
            Direction.DOWN -> {
                g = transpose(g)
                for (r in 0 until SIZE) {
                    val (newRow, s) = slideRowRight(g[r])
                    g[r] = newRow; totalScore += s
                }
                g = transpose(g)
            }
            Direction.LEFT -> {
                for (r in 0 until SIZE) {
                    val (newRow, s) = slideRowLeft(g[r])
                    g[r] = newRow; totalScore += s
                }
            }
            Direction.RIGHT -> {
                for (r in 0 until SIZE) {
                    val (newRow, s) = slideRowRight(g[r])
                    g[r] = newRow; totalScore += s
                }
            }
        }

        var moved = false
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                if (g[r][c] != grid[r][c]) { moved = true; break }
            }
        }

        return Triple(g, totalScore, moved)
    }

    // ============ 评估函数 ============

    private fun countEmpty(grid: Array<IntArray>): Int {
        return grid.sumOf { row -> row.count { it == 0 } }
    }

    private fun monotonicityScore(grid: Array<IntArray>): Double {
        var score = 0.0
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE - 1) {
                if (grid[r][c] >= grid[r][c + 1] && grid[r][c] > 0) score += grid[r][c]
            }
        }
        for (c in 0 until SIZE) {
            for (r in 0 until SIZE - 1) {
                if (grid[r][c] >= grid[r + 1][c] && grid[r][c] > 0) score += grid[r][c]
            }
        }
        return score
    }

    private fun cornerBonus(grid: Array<IntArray>): Double {
        var maxV = 0; var maxR = 0; var maxC = 0
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                if (grid[r][c] > maxV) { maxV = grid[r][c]; maxR = r; maxC = c }
            }
        }
        if (maxV == 0) return 0.0
        val corners = listOf(0 to 0, 0 to 3, 3 to 0, 3 to 3)
        for ((cr, cc) in corners) {
            if (maxR == cr && maxC == cc) return maxV * 2.0
        }
        if (maxR == 0 || maxR == 3 || maxC == 0 || maxC == 3) return maxV * 0.8
        return 0.0
    }

    private fun smoothnessScore(grid: Array<IntArray>): Double {
        var penalty = 0.0
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                val v = grid[r][c]
                if (v == 0) continue
                if (c < SIZE - 1 && grid[r][c + 1] > 0) penalty += Math.abs(v - grid[r][c + 1])
                if (r < SIZE - 1 && grid[r + 1][c] > 0) penalty += Math.abs(v - grid[r + 1][c])
            }
        }
        return -penalty
    }

    private fun evaluate(grid: Array<IntArray>): Double {
        return countEmpty(grid) * 10.0 +
                monotonicityScore(grid) * 0.4 +
                cornerBonus(grid) * 0.5 +
                smoothnessScore(grid) * 0.08
    }

    // ============ 主入口 ============

    data class AnalysisResult(
        val direction: Direction,
        val score: Double,
        val mergeScore: Int,
        val isMovable: Boolean,
        val allScores: Map<Direction, Double>
    )

    /**
     * 分析棋盘，返回最佳方向
     * @param grid 4x4 棋盘，0 表示空格
     */
    fun analyze(grid: Array<IntArray>): AnalysisResult? {
        val results = Direction.entries.map { dir ->
            val (nextGrid, mergeScore, moved) = simulate(grid, dir)
            if (!moved) {
                dir to (Double.NEGATIVE_INFINITY to 0)
            } else {
                val evalScore = evaluate(nextGrid)
                val total = evalScore + mergeScore * 0.5
                dir to (total to mergeScore)
            }
        }

        val best = results.maxByOrNull { it.second.first } ?: return null
        if (best.second.first == Double.NEGATIVE_INFINITY) return null

        val allScores = results.associate { it.first to it.second.first }

        return AnalysisResult(
            direction = best.first,
            score = best.second.first,
            mergeScore = best.second.second,
            isMovable = true,
            allScores = allScores
        )
    }
}
