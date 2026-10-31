package com.example.binancephonescanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One record per trading day (one auto-buy cycle), kept for good in a small file.
 * A record is written when the cycle ends: target reached, trailing line, end of the window,
 * or a manual liquidation.
 */
object DailyTrades {
    private const val FILE_NAME = "trade_days.json"
    private val lock = Any()

    class SymbolResult(
        val symbol: String,
        val entryPrice: Double,
        val exitPrice: Double,
        /** Price change from the entry to the exit, in percent. */
        val pnlPct: Double,
        /** Real profit in USDT (after commission) from Binance's fills; only valid if [exact]. */
        val pnlUsdt: Double,
        /** True when the exit price and profit come from Binance's fills, false when estimated. */
        val exact: Boolean,
        /** How it ended: individual target, manual sell, stop-loss, or end-of-day liquidation. */
        val kind: String,
        val entryTime: Long
    ) {
        val win: Boolean get() = if (exact) pnlUsdt > 0.0 else pnlPct > 0.0
    }

    class DayRecord(
        /** Start of the window (epoch ms, Korea time): its date is the date of the record. */
        val sessionKey: Long,
        val startedAt: Long,
        val endedAt: Long,
        val startEquity: Double,
        val endEquity: Double,
        val peakPct: Double,
        val reason: String,
        val symbols: List<SymbolResult>
    ) {
        val pnlUsdt: Double get() = endEquity - startEquity
        val pnlPct: Double get() = if (startEquity > 0.0) pnlUsdt / startEquity * 100.0 else 0.0
    }

    class Stats(
        val days: Int,
        val winDays: Int,
        val totalUsdt: Double,
        /** Compounded return over all days, in percent. */
        val compoundPct: Double,
        val avgPct: Double,
        val best: DayRecord?,
        val worst: DayRecord?,
        val trades: Int,
        val winTrades: Int
    )

    /** All records, newest day first. */
    fun load(context: Context): List<DayRecord> = synchronized(lock) {
        readAll(context).sortedByDescending { it.sessionKey }
    }

    /** Saves a record; a second record for the same day replaces the first. */
    fun save(context: Context, record: DayRecord) {
        synchronized(lock) {
            val list = readAll(context).filter { it.sessionKey != record.sessionKey } + record
            val array = JSONArray()
            list.forEach { array.put(toJson(it)) }
            val file = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(array.toString())
                tmp.delete()
            }
        }
    }

    fun stats(records: List<DayRecord>): Stats {
        var growth = 1.0
        var total = 0.0
        var win = 0
        var trades = 0
        var winTrades = 0
        for (r in records) {
            growth *= 1.0 + r.pnlPct / 100.0
            total += r.pnlUsdt
            if (r.pnlUsdt > 0.0) win++
            for (s in r.symbols) {
                trades++
                if (s.win) winTrades++
            }
        }
        val avg = if (records.isEmpty()) 0.0 else records.sumOf { it.pnlPct } / records.size
        return Stats(
            records.size, win, total, (growth - 1.0) * 100.0, avg,
            records.maxByOrNull { it.pnlPct }, records.minByOrNull { it.pnlPct }, trades, winTrades
        )
    }

    private fun readAll(context: Context): List<DayRecord> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            val list = ArrayList<DayRecord>()
            for (i in 0 until array.length()) {
                try {
                    list.add(fromJson(array.getJSONObject(i)))
                } catch (_: Exception) {
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun toJson(r: DayRecord): JSONObject = JSONObject().apply {
        put("sessionKey", r.sessionKey)
        put("startedAt", r.startedAt)
        put("endedAt", r.endedAt)
        put("startEquity", r.startEquity)
        put("endEquity", r.endEquity)
        put("peakPct", r.peakPct)
        put("reason", r.reason)
        val array = JSONArray()
        for (s in r.symbols) {
            array.put(JSONObject().apply {
                put("symbol", s.symbol)
                put("entryPrice", s.entryPrice)
                put("exitPrice", s.exitPrice)
                put("pnlPct", s.pnlPct)
                put("pnlUsdt", s.pnlUsdt)
                put("exact", s.exact)
                put("kind", s.kind)
                put("entryTime", s.entryTime)
            })
        }
        put("symbols", array)
    }

    private fun fromJson(o: JSONObject): DayRecord {
        val array = o.optJSONArray("symbols") ?: JSONArray()
        val symbols = ArrayList<SymbolResult>()
        for (i in 0 until array.length()) {
            val s = array.getJSONObject(i)
            symbols.add(
                SymbolResult(
                    s.optString("symbol"), s.optDouble("entryPrice", 0.0), s.optDouble("exitPrice", 0.0),
                    s.optDouble("pnlPct", 0.0), s.optDouble("pnlUsdt", 0.0), s.optBoolean("exact", false),
                    s.optString("kind"), s.optLong("entryTime", 0L)
                )
            )
        }
        return DayRecord(
            o.optLong("sessionKey", 0L), o.optLong("startedAt", 0L), o.optLong("endedAt", 0L),
            o.optDouble("startEquity", 0.0), o.optDouble("endEquity", 0.0), o.optDouble("peakPct", 0.0),
            o.optString("reason"), symbols
        )
    }
}
