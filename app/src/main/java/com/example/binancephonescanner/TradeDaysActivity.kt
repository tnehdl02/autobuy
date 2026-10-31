package com.example.binancephonescanner

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Daily trading records (one per auto-buy cycle), with total statistics on top.
 * Opened from the gear button on the main screen.
 */
class TradeDaysActivity : ComponentActivity() {
    private val kst: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
    private val dayFormat by lazy { SimpleDateFormat("yyyy-MM-dd (E)", Locale.KOREA).apply { timeZone = kst } }
    private val timeFormat by lazy { SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).apply { timeZone = kst } }

    private val green = Color.rgb(120, 230, 90)
    private val red = Color.rgb(255, 99, 99)
    private val muted = Color.rgb(174, 194, 211)

    /** Days the user opened; every day starts closed, and a screen rebuild keeps what was opened. */
    private val openDays = HashSet<Long>()
    private val openSamples = HashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
    }

    private fun buildScreen() {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 13, 28), Color.rgb(15, 31, 49), Color.rgb(18, 20, 38))
            )
        }
        setContentView(screen)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(8))
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(220, 236, 248))
            background = rounded(Color.argb(110, 40, 64, 90), dp(14), Color.argb(110, 111, 161, 184))
            setOnClickListener { finish() }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(46), dp(46)))
        top.addView(
            text("일자별 매매 기록", 20f, Color.rgb(236, 249, 255), true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) }
        )
        screen.addView(top, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(40))
        }
        scroll.addView(column)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val records = DailyTrades.load(this)
        if (records.isEmpty()) {
            val empty = card()
            empty.addView(text("아직 저장된 기록이 없습니다.", 15f, Color.WHITE, true))
            empty.addView(
                text("하루 사이클(운용 시작~종료)이 끝나면 그날의 매매가 자동으로 저장됩니다. 매수한 종목이 없는 날은 저장하지 않습니다.", 13f, muted, false),
                margin(0, 8, 0, 0)
            )
            column.addView(empty)
            return
        }

        column.addView(statsCard(DailyTrades.stats(records)), margin(0, 0, 0, 12))
        for (record in records) {
            column.addView(dayCard(record, expanded = openDays.contains(record.sessionKey)), margin(0, 0, 0, 10))
        }
    }

    // ---- Statistics -------------------------------------------------------------------------

    private fun pctText(value: Double): String =
        (if (value >= 0) "+" else "") + String.format(Locale.US, "%.2f", value) + "%"

    private fun usdtText(value: Double): String =
        (if (value >= 0) "+" else "") + String.format(Locale.US, "%.2f", value) + " USDT"

    private fun colorOf(value: Double): Int = if (value >= 0) green else red

    private fun statLine(label: String, value: String, color: Int = Color.WHITE): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(text(label, 13f, muted, false), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(mono(value, 14f, color, true))
        return row
    }

    private fun statsCard(st: DailyTrades.Stats): LinearLayout {
        val card = card()
        card.addView(text("STATISTICS", 17f, Color.WHITE, true), margin(0, 0, 0, 6))
        val dayRate = if (st.days > 0) st.winDays * 100.0 / st.days else 0.0
        val tradeRate = if (st.trades > 0) st.winTrades * 100.0 / st.trades else 0.0
        card.addView(statLine("누적 손익", usdtText(st.totalUsdt), colorOf(st.totalUsdt)))
        card.addView(statLine("누적 수익률 (복리)", pctText(st.compoundPct), colorOf(st.compoundPct)))
        card.addView(statLine("일 평균 수익률", pctText(st.avgPct), colorOf(st.avgPct)))
        card.addView(
            statLine(
                "일 승률",
                "${st.winDays}승 ${st.days - st.winDays}패 · ${String.format(Locale.US, "%.0f", dayRate)}%  (${st.days}일)"
            )
        )
        card.addView(
            statLine(
                "종목 승률",
                "${st.winTrades}승 ${st.trades - st.winTrades}패 · ${String.format(Locale.US, "%.0f", tradeRate)}%  (${st.trades}건)"
            )
        )
        st.best?.let { card.addView(statLine("최고의 날", "${dayLabel(it)}  ${pctText(it.pnlPct)}", colorOf(it.pnlPct))) }
        st.worst?.let { card.addView(statLine("최악의 날", "${dayLabel(it)}  ${pctText(it.pnlPct)}", colorOf(it.pnlPct))) }
        return card
    }

    // ---- One day ----------------------------------------------------------------------------

    private fun dayLabel(r: DailyTrades.DayRecord): String =
        dayFormat.format(Date(if (r.sessionKey > 0L) r.sessionKey else r.startedAt)).substring(5, 10)

    private fun dayCard(r: DailyTrades.DayRecord, expanded: Boolean): LinearLayout {
        val card = card()
        val wins = r.symbols.count { it.win }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(dayFormat.format(Date(if (r.sessionKey > 0L) r.sessionKey else r.startedAt)), 15f, Color.WHITE, true))
        left.addView(
            text(
                "종목 ${r.symbols.size}개 · ${wins}승 ${r.symbols.size - wins}패 · ${r.reason}",
                12f, muted, false
            ),
            margin(0, 2, 0, 0)
        )
        header.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(mono(pctText(r.pnlPct), 18f, colorOf(r.pnlPct), true))
        right.addView(mono(usdtText(r.pnlUsdt), 12f, colorOf(r.pnlUsdt), false))
        header.addView(right)
        card.addView(header)

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        detail.addView(
            text(
                "원금 ${String.format(Locale.US, "%.2f", r.startEquity)} → 종료 ${String.format(Locale.US, "%.2f", r.endEquity)} USDT" +
                    " · 최고 수익률 ${pctText(r.peakPct)}\n" +
                    "${timeFormat.format(Date(r.startedAt))} ~ ${timeFormat.format(Date(r.endedAt))}",
                12f, muted, false
            ),
            margin(0, 10, 0, 6)
        )
        for (s in r.symbols.sortedByDescending { it.pnlPct }) detail.addView(symbolRow(s))

        // 10-minute return record: a button that opens the list.
        val samplePanel = sampleList(r)
        samplePanel.visibility = if (openSamples.contains(r.sessionKey)) View.VISIBLE else View.GONE
        val sampleButton = TextView(this).apply {
            text = sampleButtonText(r, samplePanel.visibility == View.VISIBLE)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = rounded(Color.argb(130, 38, 116, 180), dp(12), Color.argb(120, 150, 200, 230))
            setOnClickListener {
                val show = samplePanel.visibility != View.VISIBLE
                samplePanel.visibility = if (show) View.VISIBLE else View.GONE
                if (show) openSamples.add(r.sessionKey) else openSamples.remove(r.sessionKey)
                text = sampleButtonText(r, show)
            }
        }
        detail.addView(sampleButton, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(10) })
        detail.addView(samplePanel, margin(0, 8, 0, 0))
        card.addView(detail)

        card.setOnClickListener {
            val show = detail.visibility != View.VISIBLE
            detail.visibility = if (show) View.VISIBLE else View.GONE
            if (show) openDays.add(r.sessionKey) else openDays.remove(r.sessionKey)
        }
        return card
    }

    private fun sampleButtonText(r: DailyTrades.DayRecord, open: Boolean): String =
        "10분 수익률 기록 (${r.samples.size}개) " + if (open) "▲" else "▼"

    /** The 10-minute records of one day: time and total return, two per row. */
    private fun sampleList(r: DailyTrades.DayRecord): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (r.samples.isEmpty()) {
            box.addView(text("이 날은 10분 수익률 기록이 없습니다. (메인 화면의 기록 버튼을 켜 두면 남습니다)", 12f, muted, false))
            return box
        }
        val best = r.samples.maxByOrNull { it.pct }
        val worst = r.samples.minByOrNull { it.pct }
        if (best != null && worst != null) {
            box.addView(
                text(
                    "최고 ${pctText(best.pct)} (${clockText(best.time)}) · 최저 ${pctText(worst.pct)} (${clockText(worst.time)})",
                    12f, muted, false
                ),
                margin(0, 0, 0, 6)
            )
        }
        val sorted = r.samples.sortedBy { it.time }
        var i = 0
        while (i < sorted.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            for (k in 0..1) {
                val m = sorted.getOrNull(i + k)
                val cell = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                if (m != null) {
                    cell.addView(mono(clockText(m.time), 12.5f, muted, false), LinearLayout.LayoutParams(dp(64), -2))
                    cell.addView(mono(pctText(m.pct), 13f, colorOf(m.pct), true))
                }
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            box.addView(row)
            i += 2
        }
        return box
    }

    private fun clockText(time: Long): String =
        SimpleDateFormat("HH:mm", Locale.KOREA).apply { timeZone = kst }.format(Date(time))

    private fun trim(value: Double): String =
        java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

    private fun symbolRow(s: DailyTrades.SymbolResult): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(View(this).apply { setBackgroundColor(Color.argb(30, 255, 255, 255)) }, LinearLayout.LayoutParams(-1, 1))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(mono(s.symbol.removeSuffix("USDT") + "/USDT", 14f, Color.WHITE, true))
        left.addView(
            mono(
                "진입 ${trim(s.entryPrice)} → 청산 ${trim(s.exitPrice)}\n${s.kind}" + if (s.exact) "" else " · 추정",
                11.5f, muted, false
            )
        )
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(mono(pctText(s.pnlPct), 15f, colorOf(s.pnlPct), true))
        right.addView(
            mono(if (s.exact) usdtText(s.pnlUsdt) else "--", 11.5f, if (s.exact) colorOf(s.pnlUsdt) else muted, false)
        )
        row.addView(right)
        wrapper.addView(row)
        return wrapper
    }

    // ---- View helpers -----------------------------------------------------------------------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(Color.argb(125, 25, 42, 62), dp(20), Color.argb(110, 111, 161, 184))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1.02f)
    }

    private fun mono(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("monospace", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun margin(left: Int, top: Int, right: Int, bottom: Int) =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(left), dp(top), dp(right), dp(bottom)) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
