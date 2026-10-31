package com.example.binancephonescanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The auto-buy logic. One "session" runs inside the daily time window (default 20:00 to
 * 08:00 Korea time):
 *  - at the start the account balance is the session's capital; every entry uses a fixed
 *    share of it (slotPct %)
 *  - a detected coin is bought with a market order and gets a stop-market order at the
 *    low of the detected candle
 *  - when the total profit reaches the target, or falls back from its peak by the trailing
 *    gap, everything is sold and the session is over for the day
 *  - at the end of the window everything that is still open is sold
 */
object TradeEngine {
    enum class Phase { ACTIVE, LIQUIDATING, ENDED }

    class TradeEntry(
        val symbol: String,
        val qty: Double,
        val entryPrice: Double,
        val stopPrice: Double,
        val algoId: Long,
        val time: Long
    ) {
        /** True once this symbol has been sold on its own (target or manual sell). */
        var closed = false
        var exitPrice = 0.0
        var exitReason = ""
        /** Last price seen by the monitor (0 = not known yet). */
        var lastPrice = 0.0
    }

    class TradeState {
        var sessionKey = 0L
        var startedAt = 0L
        var phase = Phase.ACTIVE
        var reason = ""
        var startEquity = 0.0
        var slotUsdt = 0.0
        var peakPct = 0.0
        /** True once the "protection line started" notice has been sent this session. */
        var trailAlerted = false
        /** Total return written down every 10 minutes while the record button is on. */
        val samples = ArrayList<DailyTrades.Sample>()
        /** The 10-minute slot (epoch ms / 600000) of the last sample, so each slot is written once. */
        var sampleBucket = 0L
        var lastPct = 0.0
        var updatedAt = 0L
        var hedge = false
        val entries = ArrayList<TradeEntry>()
    }

    /** Set by the service; shows a notification for trade events. */
    @Volatile var notifier: ((String, String) -> Unit)? = null

    private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.KOREA)
    private const val KEY_STATE = "trade_state"
    private const val KEY_LOG = "trade_log"

    private val lock = Any()
    private val lastLogAt = HashMap<String, Long>()
    private var rulesCache: Map<String, SymbolRules>? = null
    private var rulesCachedAt = 0L

    // ---- Saved state ------------------------------------------------------------------------

    fun loadState(context: Context): TradeState? {
        val raw = TradeSettings.prefs(context).getString(KEY_STATE, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val s = TradeState()
            s.sessionKey = o.optLong("sessionKey", 0L)
            s.startedAt = o.optLong("startedAt", 0L)
            s.phase = try {
                Phase.valueOf(o.optString("phase", "ACTIVE"))
            } catch (_: Exception) {
                Phase.ACTIVE
            }
            s.reason = o.optString("reason", "")
            s.startEquity = o.optDouble("startEquity", 0.0)
            s.slotUsdt = o.optDouble("slotUsdt", 0.0)
            s.peakPct = o.optDouble("peakPct", 0.0)
            s.trailAlerted = o.optBoolean("trailAlerted", false)
            s.sampleBucket = o.optLong("sampleBucket", 0L)
            val sampleArray = o.optJSONArray("samples") ?: JSONArray()
            for (i in 0 until sampleArray.length()) {
                val m = sampleArray.optJSONObject(i) ?: continue
                s.samples.add(DailyTrades.Sample(m.optLong("t", 0L), m.optDouble("p", 0.0)))
            }
            s.lastPct = o.optDouble("lastPct", 0.0)
            s.updatedAt = o.optLong("updatedAt", 0L)
            s.hedge = o.optBoolean("hedge", false)
            val array = o.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until array.length()) {
                val e = array.getJSONObject(i)
                val entry = TradeEntry(
                    e.optString("symbol"),
                    e.optDouble("qty", 0.0),
                    e.optDouble("entryPrice", 0.0),
                    e.optDouble("stopPrice", 0.0),
                    e.optLong("algoId", 0L),
                    e.optLong("time", 0L)
                )
                entry.closed = e.optBoolean("closed", false)
                entry.exitPrice = e.optDouble("exitPrice", 0.0)
                entry.exitReason = e.optString("exitReason", "")
                entry.lastPrice = e.optDouble("lastPrice", 0.0)
                s.entries.add(entry)
            }
            s
        } catch (_: Exception) {
            null
        }
    }

    private fun saveState(context: Context, s: TradeState) {
        val o = JSONObject()
        o.put("sessionKey", s.sessionKey)
        o.put("startedAt", s.startedAt)
        o.put("phase", s.phase.name)
        o.put("reason", s.reason)
        o.put("startEquity", s.startEquity)
        o.put("slotUsdt", s.slotUsdt)
        o.put("peakPct", s.peakPct)
        o.put("trailAlerted", s.trailAlerted)
        o.put("sampleBucket", s.sampleBucket)
        val sampleArray = JSONArray()
        for (m in s.samples) sampleArray.put(JSONObject().apply {
            put("t", m.time)
            put("p", m.pct)
        })
        o.put("samples", sampleArray)
        o.put("lastPct", s.lastPct)
        o.put("updatedAt", s.updatedAt)
        o.put("hedge", s.hedge)
        val array = JSONArray()
        for (e in s.entries) {
            val item = JSONObject()
            item.put("symbol", e.symbol)
            item.put("qty", e.qty)
            item.put("entryPrice", e.entryPrice)
            item.put("stopPrice", e.stopPrice)
            item.put("algoId", e.algoId)
            item.put("time", e.time)
            item.put("closed", e.closed)
            item.put("exitPrice", e.exitPrice)
            item.put("exitReason", e.exitReason)
            item.put("lastPrice", e.lastPrice)
            array.put(item)
        }
        o.put("entries", array)
        TradeSettings.prefs(context).edit().putString(KEY_STATE, o.toString()).apply()
    }

    private fun clearState(context: Context) {
        TradeSettings.prefs(context).edit().remove(KEY_STATE).apply()
    }

    // ---- Log & notices ----------------------------------------------------------------------

    fun log(context: Context, text: String) {
        val prefs = TradeSettings.prefs(context)
        synchronized(prefs) {
            val old = prefs.getString(KEY_LOG, "") ?: ""
            val line = ZonedDateTime.now(KST).format(LOG_TIME) + " | " + text.take(300)
            val lines = (old.lines().filter { it.isNotBlank() } + line).takeLast(40)
            prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
        }
    }

    /** Logs a repeating problem at most once per [intervalMs]. */
    private fun logOnce(context: Context, key: String, text: String, intervalMs: Long = 300_000L) {
        val now = System.currentTimeMillis()
        val last = lastLogAt[key] ?: 0L
        if (now - last < intervalMs) return
        lastLogAt[key] = now
        log(context, text)
    }

    /** Public wrapper so the service can log a repeating error without flooding the log. */
    fun logThrottled(context: Context, key: String, text: String) {
        synchronized(lock) { logOnce(context, key, text, 120_000L) }
    }

    /** Deletes the whole auto-buy log (the list at the bottom of the main screen). */
    fun clearLog(context: Context) {
        synchronized(lock) {
            TradeSettings.prefs(context).edit().remove(KEY_LOG).apply()
        }
    }

    fun logLines(context: Context): List<String> {
        val raw = TradeSettings.prefs(context).getString(KEY_LOG, "") ?: ""
        return raw.lines().filter { it.isNotBlank() }
    }

    private fun pushNotice(title: String, body: String) {
        try {
            notifier?.invoke(title, body)
        } catch (_: Exception) {
        }
    }

    private fun fmtPct(value: Double): String =
        (if (value >= 0) "+" else "") + String.format(Locale.US, "%.2f", value) + "%"

    private fun fmtNum(value: Double): String = String.format(Locale.US, "%.2f", value)

    // ---- Time window ------------------------------------------------------------------------

    /** Start (epoch millis) of the window that contains [now], or null when [now] is outside. */
    private fun windowStart(config: TradeConfig, now: ZonedDateTime): Long? {
        val minutes = now.hour * 60 + now.minute
        val s = config.startMin
        val e = config.endMin
        val today = now.truncatedTo(ChronoUnit.DAYS)
        if (s < e) {
            if (minutes >= s && minutes < e) {
                return today.plusMinutes(s.toLong()).toInstant().toEpochMilli()
            }
            return null
        }
        if (minutes >= s) return today.plusMinutes(s.toLong()).toInstant().toEpochMilli()
        if (minutes < e) return today.minusDays(1).plusMinutes(s.toLong()).toInstant().toEpochMilli()
        return null
    }

    private fun nextStartText(config: TradeConfig): String {
        val now = ZonedDateTime.now(KST)
        var next = now.truncatedTo(ChronoUnit.DAYS).plusMinutes(config.startMin.toLong())
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.KOREA))
    }

    // ---- API helpers ------------------------------------------------------------------------

    private fun apiClient(context: Context, config: TradeConfig): BinanceApi? {
        val key = TradeSettings.getKey(context)
        val secret = TradeSettings.getSecret(context)
        if (key.isNullOrBlank() || secret.isNullOrBlank()) return null
        return BinanceApi(config.baseUrl, key, secret)
    }

    private fun rulesFor(api: BinanceApi, symbol: String): SymbolRules? {
        val now = System.currentTimeMillis()
        var cache = rulesCache
        if (cache == null || now - rulesCachedAt > 6 * 3600_000L || !cache.containsKey(symbol)) {
            if (cache == null || now - rulesCachedAt > 60_000L) {
                cache = api.exchangeRules()
                rulesCache = cache
                rulesCachedAt = now
            }
        }
        return cache?.get(symbol)
    }

    private fun floorToStep(value: Double, step: BigDecimal): BigDecimal {
        val v = BigDecimal(value.toString())
        if (step.signum() <= 0) return v
        return v.divide(step, 0, RoundingMode.DOWN).multiply(step)
    }

    private fun plain(value: BigDecimal): String =
        if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()

    // ---- Session ----------------------------------------------------------------------------

    /**
     * Keeps the saved session in step with the clock: ends a session whose window is over,
     * and starts a new one when the window is open and none exists. Returns the current session.
     */
    private fun syncSession(context: Context, config: TradeConfig): TradeState? {
        val key = windowStart(config, ZonedDateTime.now(KST))
        var s = loadState(context)
        if (s != null && (key == null || s.sessionKey != key)) {
            if (s.phase == Phase.ACTIVE) {
                if (config.endLiquidate && s.entries.isNotEmpty()) {
                    s.phase = Phase.LIQUIDATING
                    s.reason = "종료 시각 도달"
                    saveState(context, s)
                    log(context, "종료 시각이 되어 전량 청산을 시작합니다")
                } else {
                    if (s.entries.isNotEmpty()) {
                        s.reason = "종료 시각 도달 (청산 안 함)"
                        recordDay(context, apiClient(context, config), s, null)
                    }
                    clearState(context)
                    s = null
                }
            } else if (s.phase == Phase.ENDED) {
                clearState(context)
                s = null
            }
        }
        if (s == null && key != null) s = startSession(context, config, key)
        return s
    }

    private fun startSession(context: Context, config: TradeConfig, key: Long): TradeState? {
        val api = apiClient(context, config)
        if (api == null) {
            logOnce(context, "nokey", "API 키가 없어 세션을 시작하지 못했습니다")
            return null
        }
        try {
            val balance = api.balanceUsdt()
            val equity = balance.equity
            if (equity <= 0.0) {
                logOnce(context, "noequity", "USDT 잔고가 0이라 세션을 시작하지 못했습니다")
                return null
            }
            val hedge = api.isHedgeMode()
            val s = TradeState()
            s.sessionKey = key
            s.startedAt = System.currentTimeMillis()
            s.phase = Phase.ACTIVE
            s.startEquity = equity
            s.slotUsdt = equity * config.slotPct / 100.0
            s.hedge = hedge
            saveState(context, s)
            val text = "세션 시작 · 원금 ${fmtNum(equity)} USDT · 종목당 ${fmtNum(s.slotUsdt)} USDT"
            log(context, text)
            pushNotice("자동매수 세션 시작", text)
            return s
        } catch (e: Exception) {
            logOnce(context, "start", "세션 시작 실패: ${e.message ?: e.javaClass.simpleName}")
            return null
        }
    }

    /** Called every few seconds by the service while auto-buy is on. */
    fun tick(context: Context) {
        synchronized(lock) {
            val config = TradeSettings.load(context)
            if (!config.autoBuy) return
            val s = syncSession(context, config) ?: return
            if (s.phase == Phase.LIQUIDATING) {
                runLiquidation(context, config, s)
            } else if (s.phase == Phase.ACTIVE && s.entries.isNotEmpty()) {
                monitor(context, config, s)
            }
        }
    }

    /** Checks the total profit against the target and the trailing line. */
    private fun monitor(context: Context, config: TradeConfig, s: TradeState) {
        val api = apiClient(context, config) ?: return
        checkSymbolTargets(context, config, s, api)
        val equity: Double = try {
            api.balanceUsdt().equity
        } catch (e: Exception) {
            logOnce(context, "monitor", "수익률 조회 실패: ${e.message ?: e.javaClass.simpleName}", 120_000L)
            return
        }
        if (s.startEquity <= 0.0) return
        val pct = (equity - s.startEquity) / s.startEquity * 100.0
        s.lastPct = pct
        s.updatedAt = System.currentTimeMillis()
        if (pct > s.peakPct) s.peakPct = pct
        if (config.recordPct) {
            val now = System.currentTimeMillis()
            val bucket = now / 600_000L
            if (bucket != s.sampleBucket) {
                s.sampleBucket = bucket
                s.samples.add(DailyTrades.Sample(now, pct))
            }
        }
        if (config.trailStartPct > 0.0 && !s.trailAlerted && s.peakPct >= config.trailStartPct) {
            s.trailAlerted = true
            val text = if (config.trailFixed) {
                "수익률 ${fmtPct(s.peakPct)} 도달 · 고정 청산선 ${fmtPct(config.trailStartPct - config.trailDropPct)} 아래로 내려오면 전량 청산합니다"
            } else {
                "수익률 ${fmtPct(s.peakPct)} 도달 · 현재 청산선 ${fmtPct(s.peakPct - config.trailDropPct)} (최고점이 오르면 청산선도 같이 올라갑니다)"
            }
            log(context, "보호선 작동 시작: $text")
            pushNotice("보호선 작동 시작", text)
        }
        saveState(context, s)

        if (pct >= config.targetPct) {
            beginLiquidation(context, config, s, "목표 수익률 도달 (${fmtPct(pct)})")
        } else if (config.trailStartPct > 0.0 && s.peakPct >= config.trailStartPct) {
            val line = if (config.trailFixed) config.trailStartPct - config.trailDropPct
            else s.peakPct - config.trailDropPct
            if (pct <= line) {
                val why = if (config.trailFixed) {
                    "보호선(고정): 최고 ${fmtPct(s.peakPct)} 후 청산선 ${fmtPct(line)} 이하로 하락 (${fmtPct(pct)})"
                } else {
                    "보호선: 최고 ${fmtPct(s.peakPct)}에서 ${fmtPct(pct)}로 하락"
                }
                beginLiquidation(context, config, s, why)
            }
        }
    }

    /**
     * Keeps the last price of every open symbol up to date and sells a symbol on its own when its
     * price is [TradeConfig.symbolTargetPct] % above its entry price.
     */
    private fun checkSymbolTargets(context: Context, config: TradeConfig, s: TradeState, api: BinanceApi) {
        val open = s.entries.filter { !it.closed }
        if (open.isEmpty()) return
        val prices: Map<String, Double> = try {
            api.lastPrices()
        } catch (e: Exception) {
            logOnce(context, "prices", "가격 조회 실패: ${e.message ?: e.javaClass.simpleName}", 120_000L)
            return
        }
        var changed = false
        for (e in open) {
            val price = prices[e.symbol] ?: continue
            if (price > 0.0) {
                e.lastPrice = price
                changed = true
            }
        }
        if (changed) saveState(context, s)
        if (config.symbolTargetPct <= 0.0) return
        for (e in open) {
            if (e.closed || e.lastPrice <= 0.0 || e.entryPrice <= 0.0) continue
            val gain = (e.lastPrice - e.entryPrice) / e.entryPrice * 100.0
            if (gain >= config.symbolTargetPct) {
                sellOne(context, config, s, e, "개별 목표 ${fmtPct(config.symbolTargetPct)} 도달", api)
            }
        }
    }

    /** Sells one symbol (reduce-only market order), removes its stop order, and marks it as closed. */
    private fun sellOne(
        context: Context,
        config: TradeConfig,
        s: TradeState,
        e: TradeEntry,
        reason: String,
        apiIn: BinanceApi? = null
    ): Boolean {
        val api = apiIn ?: apiClient(context, config)
        if (api == null) {
            log(context, "${e.symbol} 매도하려 했지만 API 키가 없습니다")
            return false
        }
        try {
            var amount = api.positionAmt(e.symbol)
            if (amount.signum() > 0) {
                api.marketOrder(e.symbol, "SELL", plain(amount), s.hedge, true)
                amount = api.positionAmt(e.symbol)
            }
            if (amount.signum() > 0) {
                logOnce(context, "sell_${e.symbol}", "${e.symbol} 매도 실패: 포지션이 남아 있습니다", 60_000L)
                return false
            }
            try {
                api.cancelAllStops(e.symbol)
            } catch (_: Exception) {
            }
            e.closed = true
            e.exitPrice = e.lastPrice
            e.exitReason = reason
            saveState(context, s)
            val gain = if (e.entryPrice > 0.0 && e.lastPrice > 0.0) (e.lastPrice - e.entryPrice) / e.entryPrice * 100.0 else 0.0
            val text = "${e.symbol} 매도 완료 · $reason · 진입 ${plain(BigDecimal(e.entryPrice.toString()))} → ${plain(BigDecimal(e.lastPrice.toString()))} (${fmtPct(gain)})"
            log(context, text)
            pushNotice("종목 매도 완료", text)
            return true
        } catch (ex: Exception) {
            logOnce(context, "sell_${e.symbol}", "${e.symbol} 매도 실패: ${ex.message ?: ex.javaClass.simpleName}", 60_000L)
            return false
        }
    }

    /** The "매도" button next to one symbol. */
    fun manualSell(context: Context, symbol: String) {
        synchronized(lock) {
            val config = TradeSettings.load(context)
            val s = loadState(context)
            val entry = s?.entries?.firstOrNull { it.symbol == symbol && !it.closed }
            if (s == null || entry == null || s.phase != Phase.ACTIVE) {
                log(context, "$symbol 수동 매도: 매도할 수 있는 상태가 아닙니다")
                return
            }
            sellOne(context, config, s, entry, "수동 매도")
        }
    }

    /** Saves the finished cycle as one daily record (summary + one result per symbol). */
    private fun recordDay(context: Context, api: BinanceApi?, s: TradeState, endEquityIn: Double?) {
        if (s.entries.isEmpty()) return
        try {
            var endEquity = endEquityIn ?: s.startEquity * (1.0 + s.lastPct / 100.0)
            if (endEquityIn == null && api != null) {
                try {
                    endEquity = api.balanceUsdt().equity
                } catch (_: Exception) {
                }
            }
            val results = s.entries.map { symbolResult(api, s, it) }
            DailyTrades.save(
                context,
                DailyTrades.DayRecord(
                    s.sessionKey, s.startedAt, System.currentTimeMillis(), s.startEquity,
                    endEquity, s.peakPct, s.reason, results, s.samples.toList()
                )
            )
            log(context, "오늘 기록을 일자별 매매 기록에 저장했습니다")
        } catch (ex: Exception) {
            log(context, "일자별 기록 저장 실패: ${ex.message ?: ex.javaClass.simpleName}")
        }
    }

    /** The result of one symbol: exact from Binance's fills when possible, else estimated. */
    private fun symbolResult(api: BinanceApi?, s: TradeState, e: TradeEntry): DailyTrades.SymbolResult {
        val kind = when {
            e.closed && e.exitReason.startsWith("개별 목표") -> "개별 목표 매도"
            e.closed -> "수동 매도"
            e.exitReason.isNotBlank() -> e.exitReason
            else -> "보유 유지"
        }
        var exit = if (e.exitPrice > 0.0) e.exitPrice else e.lastPrice
        var pnlUsdt = 0.0
        var exact = false
        if (api != null) {
            try {
                val from = e.time - 60_000L
                val fills = api.userTrades(e.symbol, from).filter { it.time >= from }
                val sells = fills.filter { it.side == "SELL" }
                val sold = sells.sumOf { it.qty }
                if (sold > 0.0) {
                    exit = sells.sumOf { it.price * it.qty } / sold
                    val fee = fills.filter { it.commissionAsset == "USDT" }.sumOf { it.commission }
                    pnlUsdt = fills.sumOf { it.realizedPnl } - fee
                    exact = true
                }
            } catch (_: Exception) {
            }
        }
        val pct = if (e.entryPrice > 0.0 && exit > 0.0) (exit - e.entryPrice) / e.entryPrice * 100.0 else 0.0
        return DailyTrades.SymbolResult(e.symbol, e.entryPrice, exit, pct, pnlUsdt, exact, kind, e.time)
    }

    private fun beginLiquidation(context: Context, config: TradeConfig, s: TradeState, reason: String) {
        s.phase = Phase.LIQUIDATING
        s.reason = reason
        saveState(context, s)
        log(context, "전량 청산 시작: $reason")
        runLiquidation(context, config, s)
    }

    /** Sells every position of this session and removes its stop orders. Retried until done. */
    private fun runLiquidation(context: Context, config: TradeConfig, s: TradeState) {
        val api = apiClient(context, config)
        if (api == null) {
            logOnce(context, "liq_nokey", "청산하려 했지만 API 키가 없습니다", 60_000L)
            return
        }
        var allFlat = true
        for (e in s.entries) {
            if (e.closed) continue
            try {
                var amount = api.positionAmt(e.symbol)
                // Already flat before we sold anything = the exchange's stop-loss closed it.
                if (e.exitReason.isBlank()) e.exitReason = if (amount.signum() > 0) "종료 청산" else "손절 체결"
                if (amount.signum() > 0) {
                    api.marketOrder(e.symbol, "SELL", plain(amount), s.hedge, true)
                    amount = api.positionAmt(e.symbol)
                }
                if (amount.signum() > 0) {
                    allFlat = false
                } else {
                    try {
                        api.cancelAllStops(e.symbol)
                    } catch (_: Exception) {
                    }
                }
            } catch (ex: Exception) {
                allFlat = false
                logOnce(context, "liq_${e.symbol}", "${e.symbol} 청산 실패: ${ex.message ?: ex.javaClass.simpleName}", 60_000L)
            }
        }
        if (!allFlat) {
            saveState(context, s)
            return
        }

        var finalText = ""
        var endEquity = s.startEquity * (1.0 + s.lastPct / 100.0)
        try {
            val equity = api.balanceUsdt().equity
            endEquity = equity
            if (s.startEquity > 0.0) {
                val pct = (equity - s.startEquity) / s.startEquity * 100.0
                s.lastPct = pct
                finalText = " · 최종 손익 ${fmtPct(pct)}"
            }
        } catch (_: Exception) {
        }
        recordDay(context, api, s, endEquity)
        s.phase = Phase.ENDED
        saveState(context, s)
        val text = "청산 완료: ${s.reason}$finalText"
        log(context, text)
        pushNotice("자동매수 청산 완료", text)
    }

    /** The "지금 전량 청산" button. Ends the session for this window. */
    fun manualLiquidate(context: Context) {
        synchronized(lock) {
            val config = TradeSettings.load(context)
            val s = loadState(context)
            if (s == null || s.phase == Phase.ENDED || s.entries.isEmpty()) {
                log(context, "수동 청산: 청산할 보유 종목이 없습니다")
                return
            }
            s.phase = Phase.LIQUIDATING
            s.reason = "수동 전량 청산"
            saveState(context, s)
            log(context, "수동 전량 청산 시작")
            runLiquidation(context, config, s)
        }
    }

    // ---- Detections -------------------------------------------------------------------------

    /**
     * A coin was detected by the scan. [sendAlert] posts the normal alert notification.
     * While a session is running, a coin that is already held or bought earlier in the
     * session, or any coin once all slots are used, is ignored silently (no alert).
     */
    fun handleDetection(
        context: Context,
        symbol: String,
        price: Double,
        lowPrice: Double,
        sendAlert: () -> Unit
    ) {
        var alerted = false
        try {
            synchronized(lock) {
                val config = TradeSettings.load(context)
                if (config.autoBuy) {
                    val s = syncSession(context, config)
                    if (s != null && s.phase == Phase.ACTIVE) {
                        val taken = s.entries.any { it.symbol == symbol }
                        if (taken || s.entries.size >= config.maxSlots) return
                        sendAlert()
                        alerted = true
                        enter(context, config, s, symbol, price, lowPrice)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            logOnce(context, "enter_$symbol", "$symbol 매수 실패: ${e.message ?: e.javaClass.simpleName}", 30_000L)
        }
        if (!alerted) sendAlert()
    }

    private fun enter(
        context: Context,
        config: TradeConfig,
        s: TradeState,
        symbol: String,
        price: Double,
        lowPrice: Double
    ) {
        val api = apiClient(context, config) ?: throw IllegalStateException("API 키가 설정되지 않았습니다")
        val rules = rulesFor(api, symbol) ?: throw IllegalStateException("거래 규칙을 찾을 수 없습니다")

        val stop = floorToStep(lowPrice, rules.tick)
        val stopValue = stop.toDouble()
        if (stopValue <= 0.0 || price <= stopValue * 1.0005) {
            log(context, "$symbol 건너뜀: 현재가가 손절선과 너무 가깝습니다")
            return
        }
        val quantity = floorToStep(s.slotUsdt * config.leverage / price, rules.step)
        if (quantity.signum() <= 0 || quantity < rules.minQty ||
            quantity.toDouble() * price < rules.minNotional * 1.02) {
            log(context, "$symbol 건너뜀: 투입 금액이 최소 주문 수량에 못 미칩니다")
            return
        }

        api.setLeverage(symbol, config.leverage)
        try {
            api.setCrossMargin(symbol)
        } catch (e: BinanceApiException) {
            if (e.code != -4046) {
                logOnce(context, "margin_$symbol", "$symbol 교차 마진 설정 실패: ${e.message}", 600_000L)
            }
        }

        val order = api.marketOrder(symbol, "BUY", plain(quantity), s.hedge, false)
        var filled = order.executedQty
        if (filled.signum() <= 0) {
            try {
                Thread.sleep(600)
            } catch (_: InterruptedException) {
            }
            filled = api.positionAmt(symbol)
        }
        if (filled.signum() <= 0) throw IllegalStateException("체결 수량을 확인하지 못했습니다")
        val entryPrice = if (order.avgPrice.signum() > 0) order.avgPrice.toDouble() else price

        var algoId = 0L
        var stopError = ""
        for (attempt in 1..3) {
            try {
                algoId = api.placeStop(symbol, plain(stop), s.hedge)
                if (algoId != 0L) break
            } catch (e: Exception) {
                stopError = e.message ?: e.javaClass.simpleName
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                }
            }
        }
        if (algoId == 0L) {
            // No stop order could be placed: close the position at once instead of leaving it unprotected.
            var closed = false
            try {
                api.marketOrder(symbol, "SELL", plain(filled), s.hedge, true)
                closed = true
            } catch (_: Exception) {
            }
            val text = if (closed) {
                "$symbol 손절 주문 실패($stopError) → 즉시 청산했습니다"
            } else {
                "긴급: $symbol 손절 주문 실패($stopError)이고 청산도 실패했습니다. 바이낸스에서 직접 확인하세요"
            }
            s.entries.add(TradeEntry(symbol, filled.toDouble(), entryPrice, stopValue, 0L, System.currentTimeMillis()))
            saveState(context, s)
            log(context, text)
            pushNotice("자동매수 경고", text)
            return
        }

        s.entries.add(TradeEntry(symbol, filled.toDouble(), entryPrice, stopValue, algoId, System.currentTimeMillis()))
        saveState(context, s)
        val text = "$symbol 매수 체결 · 수량 ${plain(filled)} · 진입 ${plain(BigDecimal(entryPrice.toString()))} · 손절 ${plain(stop)} · ${s.entries.size}/${config.maxSlots}"
        log(context, text)
        pushNotice("자동매수 체결: $symbol", text)
    }

    // ---- Status for the screen --------------------------------------------------------------

    fun statusText(context: Context): String {
        val config = TradeSettings.load(context)
        val s = loadState(context)
        val head = StringBuilder()
        if (!config.autoBuy) {
            head.append("자동매수 꺼짐")
        } else if (!TradeSettings.hasKeys(context)) {
            head.append("자동매수 켜짐 · API 키를 먼저 저장하세요")
        } else if (s == null) {
            val inWindow = windowStart(config, ZonedDateTime.now(KST)) != null
            head.append(if (inWindow) "세션 시작 대기 중" else "대기 중 · 다음 세션 ${nextStartText(config)}")
        } else {
            when (s.phase) {
                Phase.ACTIVE -> head.append(
                    "진행 중 · 진입 ${s.entries.size}/${config.maxSlots}\n" +
                        "원금 ${fmtNum(s.startEquity)} · 종목당 ${fmtNum(s.slotUsdt)} USDT\n" +
                        "총 손익 ${fmtPct(s.lastPct)} (최고 ${fmtPct(s.peakPct)})"
                )
                Phase.LIQUIDATING -> head.append("전량 청산 진행 중 · ${s.reason}")
                Phase.ENDED -> head.append(
                    "오늘 종료 · ${s.reason}\n최종 손익 ${fmtPct(s.lastPct)} · 다음 세션 ${nextStartText(config)}"
                )
            }
        }
        head.append("\n운용 시간 ${config.windowText()} (한국 시간)")
        if (s != null && s.entries.isNotEmpty()) {
            head.append("\n\n매수 종목")
            for (e in s.entries) {
                head.append("\n• ${e.symbol}  진입 ${plain(BigDecimal(e.entryPrice.toString()))}  손절 ${plain(BigDecimal(e.stopPrice.toString()))}")
            }
        }
        return head.toString()
    }

    /** What the screen shows while auto-buy is running: profit, target and trailing line. */
    class Progress(
        val phase: Phase,
        val reason: String,
        val maxSlots: Int,
        val startEquity: Double,
        val slotUsdt: Double,
        val pct: Double,
        val peakPct: Double,
        val updatedAt: Long,
        val targetPct: Double,
        val trailStartPct: Double,
        val trailDropPct: Double,
        val symbolTargetPct: Double,
        val trailFixed: Boolean,
        val entries: List<TradeEntry>
    ) {
        /** 0 = not used, 1 = waiting (peak below the start), 2 = armed (working). */
        val trailMode: Int
            get() = when {
                trailStartPct <= 0.0 -> 0
                peakPct >= trailStartPct -> 2
                else -> 1
            }

        /** When armed: the total profit at or below which everything is sold. */
        val trailLine: Double get() = if (trailFixed) trailStartPct - trailDropPct else peakPct - trailDropPct
    }

    fun progress(context: Context): Progress? {
        val config = TradeSettings.load(context)
        if (!config.autoBuy) return null
        val s = loadState(context) ?: return null
        return Progress(
            s.phase, s.reason, config.maxSlots, s.startEquity, s.slotUsdt, s.lastPct, s.peakPct,
            s.updatedAt, config.targetPct, config.trailStartPct, config.trailDropPct,
            config.symbolTargetPct, config.trailFixed, s.entries.toList()
        )
    }

    fun hasOpenSession(context: Context): Boolean {
        val s = loadState(context) ?: return false
        return s.phase != Phase.ENDED && s.entries.isNotEmpty()
    }
}
