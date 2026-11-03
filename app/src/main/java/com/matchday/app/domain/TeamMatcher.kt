package com.matchday.app.domain

import java.text.Normalizer

/**
 * Matches team names across data providers (e.g. "Inter Milan" vs "FC Internazionale Milano").
 *
 * Rules:
 *  - accents, punctuation, legal suffixes ("FC", "SC"...) and pure numbers are ignored;
 *  - generic words ("real", "city") count less;
 *  - when BOTH names contain words the other lacks ("Manchester City" vs "Manchester United")
 *    the score is halved, so they never match;
 *  - among candidates, ties are broken by overall token overlap ("Inter Milan" prefers
 *    "Internazionale Milano" to "AC Milan").
 */
object TeamMatcher {
    private val stop = setOf(
        "fc", "cf", "afc", "sc", "ac", "ssc", "rc", "cd", "ud", "sd", "fk", "sk", "bk", "kv", "nk",
        "if", "sv", "fsv", "club", "de", "del", "la", "le", "the", "calcio", "football", "futbol",
        "clube", "sad", "rb", "rasenballsport", "and", "vfl", "vfb", "tsg", "spvgg", "hsc", "bsc",
        "ogc", "losc", "as", "us", "cp", "hellas", "sl",
    )
    private val generic = setOf(
        "real", "sporting", "united", "city", "athletic", "atletico", "olympique", "borussia",
        "racing", "dynamo", "dinamo", "saint", "st", "royal", "union", "town", "rovers",
        "wanderers", "hotspur", "albion",
    )
    private val tokenAlias = mapOf(
        "internazionale" to "inter", "milano" to "milan", "munchen" to "munich",
        "lyonnais" to "lyon", "praha" to "prague", "wien" to "vienna", "koln" to "cologne",
        "sevilla" to "seville", "lisboa" to "lisbon", "brugge" to "bruges",
        "monchengladbach" to "gladbach", "utd" to "united", "man" to "manchester",
        "olympiakos" to "olympiacos", "wolves" to "wolverhampton", "spurs" to "tottenham",
    )
    /** Applied to the cleaned token string. */
    private val fullAlias = mapOf(
        "psg" to "paris saint germain",
        "sporting lisbon" to "sporting portugal",
        "crvena zvezda" to "red star belgrade",
    )

    fun normalize(raw: String): String {
        val replaced = raw.lowercase()
            .replace("ø", "o").replace("æ", "ae").replace("ß", "ss")
            .replace("ł", "l").replace("đ", "d").replace("ı", "i")
        val noMarks = Normalizer.normalize(replaced, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return noMarks.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun tokens(raw: String): Set<String> {
        val cleaned = normalize(raw).split(' ')
            .map { tokenAlias[it] ?: it }
            .filter { it.isNotBlank() && it !in stop && !it.all(Char::isDigit) }
            .joinToString(" ")
        val aliased = fullAlias[cleaned] ?: cleaned
        return aliased.split(' ').filter { it.isNotBlank() }.toSet()
    }

    private fun weight(t: String) = if (t in generic) 0.3 else 1.0

    fun score(a: String, b: String): Double = score(tokens(a), tokens(b))

    private fun score(ta: Set<String>, tb: Set<String>): Double {
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        if (ta == tb) return 1.0
        val common = ta.intersect(tb).sumOf { weight(it) }
        if (common == 0.0) return 0.0
        val containment = common / minOf(ta.sumOf { weight(it) }, tb.sumOf { weight(it) })
        val bothHaveExtra = (ta - tb).isNotEmpty() && (tb - ta).isNotEmpty()
        return if (bothHaveExtra) containment * 0.5 else containment
    }

    private fun jaccard(ta: Set<String>, tb: Set<String>): Double {
        val union = ta.union(tb).size
        return if (union == 0) 0.0 else ta.intersect(tb).size.toDouble() / union
    }

    fun <T> best(name: String, candidates: List<T>, nameOf: (T) -> String?, threshold: Double = 0.6): T? {
        val t = tokens(name)
        var bestItem: T? = null
        var bestScore = 0.0
        var bestJ = 0.0
        for (c in candidates) {
            val n = nameOf(c) ?: continue
            val tc = tokens(n)
            val s = score(t, tc)
            val j = jaccard(t, tc)
            if (s > bestScore || (s == bestScore && j > bestJ)) {
                bestScore = s; bestJ = j; bestItem = c
            }
        }
        return if (bestScore >= threshold) bestItem else null
    }

    fun same(a: String, b: String) = score(a, b) >= 0.6
}
