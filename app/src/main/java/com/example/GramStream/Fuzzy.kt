package com.example.GramStream

/**
 * Small string-similarity helper for ranking channel search results against
 * a target title. Not a reimplementation of whatever the real app's
 * Fuzzy.kt does internally (that requires decompiling the method body, not
 * just the class name) - this is a standard, well-understood algorithm
 * (normalized token overlap + Levenshtein ratio) that plugs into the same
 * title_threshold / title_threshold_fuzzy config values.
 */
object Fuzzy {

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    /** Returns a 0.0-1.0 similarity score between two titles. */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0

        val dist = levenshtein(na, nb)
        val maxLen = maxOf(na.length, nb.length)
        return 1.0 - (dist.toDouble() / maxLen)
    }
}
