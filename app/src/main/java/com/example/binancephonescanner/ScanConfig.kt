package com.example.binancephonescanner

import java.util.Locale

/** 1 -> "1", 4.5 -> "4.5" */
fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

/**
 * The settings of one watch, chosen by the user:
 *  - interval       : candle size ("1m", "5m", "15m", "1h", "4h" or "1d")
 *  - minutes        : the minutes of every hour at which the scan runs (Korea time)
 *  - highPct        : the candle high must be at least this many % above the candle open
 *  - livePct        : the current price must be at least this many % above the candle open
 *  - volumeMultiple : the candle volume must be at least this many times the previous candle
 */
data class ScanConfig(
    val interval: String,
    val minutes: List<Int>,
    val highPct: Double,
    val livePct: Double,
    val volumeMultiple: Double,
    /** Hours of the day (0-23, Korea time) at which the scan runs. Empty = every hour. */
    val hours: List<Int> = emptyList()
) {
    val label: String get() = ScanOptions.INTERVALS[interval] ?: interval

    /** "매시 정각", "매시 50분", "매시 13분 · 28분 · 43분 · 58분" */
    fun scheduleText(): String {
        val sorted = minutes.sorted()
        if (sorted.isEmpty()) return "시간 미설정"
        if (hours.isNotEmpty()) {
            val h = hours.sorted().joinToString("·") { "${it}시" }
            val m = sorted.joinToString("·") { if (it == 0) "정각" else "${it}분" }
            return "매일 $h $m"
        }
        if (sorted.size == 1) return if (sorted[0] == 0) "매시 정각" else "매시 ${sorted[0]}분"
        return "매시 " + sorted.joinToString(" · ") { if (it == 0) "정각" else "${it}분" }
    }

    fun conditionText(): String =
        "• 현재 ${label}의 고가가 시가 대비 +${formatNumber(highPct)}% 이상\n" +
            "• 현재가가 시가 대비 +${formatNumber(livePct)}% 이상\n" +
            "• 거래량이 직전 완료봉의 ${formatNumber(volumeMultiple)}배 이상"
}

/** Choices and input parsing for the settings dialog. */
object ScanOptions {
    val INTERVALS: LinkedHashMap<String, String> = linkedMapOf(
        "1m" to "1분봉",
        "5m" to "5분봉",
        "15m" to "15분봉",
        "1h" to "1시간봉",
        "4h" to "4시간봉",
        "6h" to "6시간봉",
        "8h" to "8시간봉",
        "12h" to "12시간봉",
        "1d" to "1일봉"
    )

    /** Candles of 4 hours or more are scanned by hour of the day, not only by minute. */
    val HOURLY: Set<String> = setOf("4h", "6h", "8h", "12h", "1d")

    fun isHourly(interval: String): Boolean = interval in HOURLY

    /**
     * Hours (Korea time) that are 10 minutes before a candle closes, scanned at minute 50.
     * Binance candles open at 00:00 UTC (= 09:00 KST) and repeat.
     */
    fun closePreset(interval: String): Pair<List<Int>, Int> = when (interval) {
        "4h" -> listOf(0, 4, 8, 12, 16, 20) to 50
        "6h" -> listOf(2, 8, 14, 20) to 50
        "8h" -> listOf(0, 8, 16) to 50
        "12h" -> listOf(8, 20) to 50
        "1d" -> listOf(8) to 50
        else -> emptyList<Int>() to 50
    }

    /** "4, 8 12" -> [4, 8, 12]. Blank -> empty list (every hour). Null if invalid (not 0-23). */
    fun parseHours(text: String?): List<Int>? {
        if (text.isNullOrBlank()) return emptyList()
        val parts = text.split(',', ' ', ';', '，', '·').filter { it.isNotBlank() }
        val result = ArrayList<Int>()
        for (part in parts) {
            val hour = part.trim().removeSuffix("시").toIntOrNull() ?: return null
            if (hour !in 0..23) return null
            result.add(hour)
        }
        return result.distinct().sorted()
    }

    /**
     * "0, 15 30;45" -> [0, 15, 30, 45]. Returns null if the text is empty or has anything that
     * is not a number from 0 to 59.
     */
    fun parseMinutes(text: String?): List<Int>? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(',', ' ', ';', '，', '·').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val result = ArrayList<Int>()
        for (part in parts) {
            val minute = part.trim().toIntOrNull() ?: return null
            if (minute !in 0..59) return null
            result.add(minute)
        }
        return result.distinct().sorted()
    }
}
