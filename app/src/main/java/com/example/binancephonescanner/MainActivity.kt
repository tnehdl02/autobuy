package com.example.binancephonescanner

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Main screen of the auto-buy app, top to bottom:
 *  1. the scan conditions (editable until START SCANNER, then fixed),
 *  2. the auto-buy switch and its status,
 *  3. a short auto-buy log (time + what happened; a red dot marks the latest purchases).
 * The scan results and the scan log are only in the history screen ("기록 확인").
 * The gear button (top right) holds the auto-buy settings, the API test and the background setting.
 */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences(ScannerService.PREFS, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private val kst: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
    private val logTimeFormat by lazy { SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).apply { timeZone = kst } }
    private val logDayFormat by lazy { SimpleDateFormat("MM-dd", Locale.KOREA).apply { timeZone = kst } }

    private val green = Color.rgb(120, 230, 90)
    private val redDot = Color.rgb(255, 69, 69)
    private val muted = Color.rgb(174, 194, 211)

    private lateinit var statusText: TextView

    /** The views of the conditions card. */
    private class ConditionPage {
        lateinit var stateText: TextView
        val chips = LinkedHashMap<String, TextView>()
        var interval = "15m"
        lateinit var volumeField: EditText
        lateinit var highField: EditText
        lateinit var liveField: EditText
        lateinit var minutesField: EditText
        lateinit var hoursField: EditText
        lateinit var hoursCard: LinearLayout
        lateinit var presetRow: LinearLayout
        lateinit var fieldsContainer: LinearLayout
        lateinit var toggleButton: Button
        lateinit var infoText: TextView
        lateinit var progressBar: ProgressBar
        var locked = false
    }

    private val page = ConditionPage()
    private lateinit var autoStateText: TextView
    private lateinit var progressPanel: LinearLayout
    private lateinit var pctText: TextView
    private lateinit var peakText: TextView
    private lateinit var recordButton: Button
    private lateinit var targetBar: ProgressBar
    private lateinit var targetText: TextView
    private lateinit var trailText: TextView
    private lateinit var symbolTargetText: TextView
    private lateinit var entriesBox: LinearLayout

    private class EntryRow(val pnl: TextView, val sub: TextView, val symbol: String)

    private val entryRows = HashMap<String, EntryRow>()
    private var entriesStructure = ""
    private lateinit var updatedText: TextView
    private lateinit var autoToggle: Button
    private lateinit var liquidateButton: Button
    private lateinit var logContainer: LinearLayout
    private var renderedLogSignature = "__NOT_RENDERED__"

    private val refreshTask = object : Runnable {
        override fun run() {
            safeRefreshData()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            WatchStore.ensureLoaded(this)
            requestNotificationPermission()
            buildUi()
            resumeAutoBuyIfNeeded()
            safeRefreshData()
        } catch (e: Throwable) {
            showStartupError(e)
        }
    }

    private fun showStartupError(error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            setBackgroundColor(Color.rgb(7, 13, 28))
        }
        val title = TextView(this).apply {
            text = "앱 화면 초기화 실패"
            textSize = 22f
            setTextColor(Color.rgb(255, 120, 120))
            typeface = Typeface.DEFAULT_BOLD
        }
        val details = TextView(this).apply {
            text = "화면을 만드는 중 오류가 발생했습니다.\n\n오류: $message\n\n앱을 종료하지 말고 이 오류 내용을 캡처해 주세요."
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(20))
        }
        screen.addView(title)
        screen.addView(details)
        setContentView(screen)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun watch(): Watch = Watch.values().first()

    /** When auto-buy was left on, the app brings the service back up every time it is opened. */
    private fun resumeAutoBuyIfNeeded() {
        val config = TradeSettings.load(this)
        if (config.autoBuy && TradeSettings.hasKeys(this)) {
            sendService(ScannerService.ACTION_START, watch().id)
        }
    }

    // ---- Screen ---------------------------------------------------------------------------

    private fun buildUi() {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackground(GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 13, 28), Color.rgb(15, 31, 49), Color.rgb(18, 20, 38))
            ))
        }
        // Attach early: if a later view fails to initialize, the activity won't stay blank.
        setContentView(screen)

        // Top bar: title + status on the left, gear button on the right.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(28), dp(16), dp(8))
        }
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(text("BINANCE AUTO BUY", 20f, Color.rgb(236, 249, 255), true))
        statusText = text("상태 확인 중…", 13f, Color.WHITE, true)
        titleColumn.addView(statusText, margin(0, 2, 0, 0))
        top.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(220, 236, 248))
            background = rounded(Color.argb(110, 40, 64, 90), dp(14), Color.argb(110, 111, 161, 184))
            setOnClickListener { showSettingsMenu() }
        }
        top.addView(gear, LinearLayout.LayoutParams(dp(46), dp(46)))
        screen.addView(top, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(40))
        }
        scroll.addView(column)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        column.addView(buildConditionsCard(), margin(0, 0, 0, 12))
        column.addView(buildAutoCard(), margin(0, 0, 0, 12))
        column.addView(buildLogCard())
    }

    private fun buildConditionsCard(): LinearLayout {
        val settings = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text("SETTINGS: CONDITIONS", 17f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        page.stateText = text("○ 대기", 12f, Color.rgb(255, 190, 110), true)
        head.addView(page.stateText)
        settings.addView(head, margin(0, 0, 0, 10))

        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.fieldsContainer = fields
        settings.addView(fields)

        // Timeframe chips
        val timeframeBox = miniBox()
        timeframeBox.addView(text("TIMEFRAME", 11f, muted, true))
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((key, _) in ScanOptions.INTERVALS) {
            val chip = chip(key)
            chip.setOnClickListener {
                if (page.locked) {
                    lockedToast()
                } else {
                    page.interval = key
                    styleChips()
                    applyIntervalDefaults()
                }
            }
            page.chips[key] = chip
            chipRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        chipScroll.addView(chipRow)
        timeframeBox.addView(chipScroll, margin(0, 6, 0, 0))
        fields.addView(timeframeBox, margin(0, 0, 0, 8))

        page.volumeField = inputBox(decimal = true, hintText = "4.5")
        page.highField = inputBox(decimal = true, hintText = "5")
        page.liveField = inputBox(decimal = true, hintText = "4")
        page.minutesField = inputBox(decimal = false, hintText = "50")
        page.hoursField = inputBox(decimal = false, hintText = "4, 8, 12")

        val rowA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowA.addView(miniCard("VOLUME SPIKE (직전 봉 대비)", page.volumeField, "배"), LinearLayout.LayoutParams(0, -2, 1f))
        rowA.addView(miniCard("고가 / 시가", page.highField, "%"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        fields.addView(rowA, margin(0, 0, 0, 8))

        val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowB.addView(miniCard("현재가 / 시가", page.liveField, "%"), LinearLayout.LayoutParams(0, -2, 1f))
        rowB.addView(miniCard("검사 시각 (매시 N분)", page.minutesField, "분"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        fields.addView(rowB, margin(0, 0, 0, 8))

        page.hoursCard = miniCard("검사 시각 (시, 한국 시간 · 비우면 매시간)", page.hoursField, "시")
        fields.addView(page.hoursCard, margin(0, 0, 0, 6))
        page.presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val closePreset = chip("마감 10분 전")
        closePreset.background = rounded(Color.argb(80, 255, 255, 255), dp(20))
        closePreset.setOnClickListener {
            if (page.locked) {
                lockedToast()
            } else {
                val preset = ScanOptions.closePreset(page.interval)
                page.hoursField.setText(preset.first.joinToString(", "))
                page.minutesField.setText(preset.second.toString())
            }
        }
        val everyHour = chip("매시간")
        everyHour.background = rounded(Color.argb(80, 255, 255, 255), dp(20))
        everyHour.setOnClickListener {
            if (page.locked) lockedToast() else page.hoursField.setText("")
        }
        page.presetRow.addView(closePreset, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        page.presetRow.addView(everyHour)
        fields.addView(page.presetRow, margin(0, 0, 0, 8))

        fillFields(watch().config)

        page.toggleButton = Button(this).apply {
            text = "▶  START SCANNER"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            stateListAnimator = null
            setOnClickListener { onWatchToggle() }
        }
        settings.addView(page.toggleButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(4) })

        page.progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(34, 197, 145))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 65, 83))
        }
        settings.addView(page.progressBar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10) })
        page.infoText = text("", 11.5f, muted, false)
        settings.addView(page.infoText, margin(0, 6, 0, 0))
        return settings
    }

    private fun buildAutoCard(): LinearLayout {
        val autoCard = card()
        autoCard.addView(text("AUTO BUY", 17f, Color.WHITE, true))
        autoStateText = text("", 12.5f, Color.rgb(197, 214, 229), false)
        autoCard.addView(autoStateText, margin(0, 8, 0, 8))

        // Progress panel: shown while there are purchases in the running session.
        progressPanel = miniBox()
        progressPanel.visibility = View.GONE
        val pctRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val pctColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pctColumn.addView(text("현재 수익률 (원금 대비)", 11f, muted, true))
        pctText = mono("+0.00%", 30f, green, true, singleLine = true)
        pctColumn.addView(pctText)
        pctRow.addView(pctColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val peakColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        recordButton = Button(this).apply {
            textSize = 11.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            stateListAnimator = null
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { toggleRecord() }
        }
        peakColumn.addView(recordButton, LinearLayout.LayoutParams(-2, dp(30)).apply { bottomMargin = dp(6) })
        peakText = mono("", 13f, muted, false, singleLine = true)
        peakColumn.addView(peakText)
        pctRow.addView(peakColumn)
        progressPanel.addView(pctRow)
        targetBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(34, 197, 145))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 65, 83))
        }
        progressPanel.addView(targetBar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10) })
        targetText = text("", 12f, muted, false)
        progressPanel.addView(targetText, margin(0, 6, 0, 0))
        trailText = text("", 13f, Color.WHITE, true)
        progressPanel.addView(trailText, margin(0, 8, 0, 0))
        symbolTargetText = text("", 12.5f, muted, false)
        progressPanel.addView(symbolTargetText, margin(0, 6, 0, 0))
        entriesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        progressPanel.addView(entriesBox, margin(0, 10, 0, 0))
        updatedText = text("", 11f, muted, false)
        progressPanel.addView(updatedText, margin(0, 8, 0, 0))
        autoCard.addView(progressPanel, margin(0, 0, 0, 12))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        autoToggle = button("자동매수 켜기", Color.rgb(0, 200, 137))
        autoToggle.setOnClickListener { onAutoToggle() }
        liquidateButton = button("전량 청산", Color.rgb(150, 60, 70))
        liquidateButton.setOnClickListener { confirmLiquidate() }
        row.addView(autoToggle, LinearLayout.LayoutParams(0, dp(48), 3f))
        row.addView(liquidateButton, LinearLayout.LayoutParams(0, dp(48), 2f).apply { marginStart = dp(8) })
        autoCard.addView(row)
        return autoCard
    }

    private fun buildLogCard(): LinearLayout {
        val logCard = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text("AUTO BUY LOG", 17f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        val historyButton = Button(this).apply {
            text = "기록 확인"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(130, 38, 116, 180), dp(12), Color.argb(120, 150, 200, 230))
            stateListAnimator = null
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, HistoryActivity::class.java).apply {
                    putExtra(ScannerService.EXTRA_WATCH_ID, watch().id)
                })
            }
        }
        head.addView(historyButton, LinearLayout.LayoutParams(-2, dp(38)))
        logCard.addView(head, margin(0, 0, 0, 8))
        logContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logCard.addView(logContainer)
        return logCard
    }

    // ---- Condition fields ------------------------------------------------------------------

    private fun chip(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(7), dp(14), dp(7))
    }

    private fun styleChips() {
        for ((key, view) in page.chips) {
            val selected = key == page.interval
            view.background = rounded(
                if (selected) Color.argb(210, 110, 160, 195) else Color.argb(60, 255, 255, 255), dp(20)
            )
            view.setTextColor(if (selected) Color.rgb(8, 20, 34) else Color.WHITE)
            view.typeface = Typeface.create("sans-serif", if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun updateHoursVisibility() {
        val hourly = ScanOptions.isHourly(page.interval)
        page.hoursCard.visibility = if (hourly) View.VISIBLE else View.GONE
        page.presetRow.visibility = if (hourly) View.VISIBLE else View.GONE
    }

    /** When the candle size changes: 4h or longer gets the "10 minutes before close" schedule. */
    private fun applyIntervalDefaults() {
        updateHoursVisibility()
        if (ScanOptions.isHourly(page.interval)) {
            val preset = ScanOptions.closePreset(page.interval)
            page.hoursField.setText(preset.first.joinToString(", "))
            page.minutesField.setText(preset.second.toString())
        } else {
            page.hoursField.setText("")
        }
    }

    private fun fillFields(config: ScanConfig) {
        page.interval = config.interval
        page.volumeField.setText(formatNumber(config.volumeMultiple))
        page.highField.setText(formatNumber(config.highPct))
        page.liveField.setText(formatNumber(config.livePct))
        page.minutesField.setText(config.minutes.joinToString(", "))
        page.hoursField.setText(config.hours.joinToString(", "))
        styleChips()
        updateHoursVisibility()
    }

    /** Locks (or unlocks) the condition fields: while the scanner runs they cannot change. */
    private fun setLocked(locked: Boolean) {
        page.locked = locked
        listOf(page.volumeField, page.highField, page.liveField, page.minutesField, page.hoursField)
            .forEach { it.isEnabled = !locked }
        page.fieldsContainer.alpha = if (locked) 0.6f else 1f
    }

    private fun lockedToast() {
        Toast.makeText(this, "감시 중에는 조건이 고정됩니다. 바꾸려면 먼저 중지하세요.", Toast.LENGTH_SHORT).show()
    }

    /** Reads and checks the fields. Null (with a message) if something is wrong. */
    private fun readConfig(): ScanConfig? {
        val high = page.highField.text.toString().trim().toDoubleOrNull()
        val live = page.liveField.text.toString().trim().toDoubleOrNull()
        val volume = page.volumeField.text.toString().trim().toDoubleOrNull()
        val minutes = ScanOptions.parseMinutes(page.minutesField.text.toString())
        val hourly = ScanOptions.isHourly(page.interval)
        val hours = if (hourly) ScanOptions.parseHours(page.hoursField.text.toString()) else emptyList()
        val problem = when {
            volume == null || volume <= 0.0 -> "거래량 배수는 0보다 큰 숫자로 입력하세요."
            high == null || high < 0.0 -> "고가 상승률은 0 이상의 숫자로 입력하세요."
            live == null || live < 0.0 -> "현재가 상승률은 0 이상의 숫자로 입력하세요."
            minutes == null -> "검사 분은 0~59 사이의 숫자를 쉼표로 구분해 입력하세요."
            hours == null -> "검사 시는 0~23 사이의 숫자를 쉼표로 구분해 입력하세요."
            else -> null
        }
        if (problem != null || high == null || live == null || volume == null || minutes == null || hours == null) {
            Toast.makeText(this, problem ?: "입력값을 확인하세요.", Toast.LENGTH_LONG).show()
            return null
        }
        return ScanConfig(page.interval, minutes, high, live, volume, hours)
    }

    /** START SCANNER: saves the conditions (they are then fixed) and starts. STOP: stops and unlocks. */
    private fun onWatchToggle() {
        val w = watch()
        val enabled = prefs.getBoolean(w.key("enabled"), false)
        if (enabled && TradeSettings.load(this).autoBuy) {
            Toast.makeText(this, "자동매수가 켜져 있는 동안은 감시를 끌 수 없습니다. 먼저 자동매수를 끄세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (!enabled) {
            val config = readConfig() ?: return
            WatchStore.updateConfig(this, w.id, config)
            setLocked(true)
        }
        sendService(if (enabled) ScannerService.ACTION_STOP else ScannerService.ACTION_START, w.id)
        page.stateText.text = if (enabled) "◷ 중지 요청 중…" else "◷ 시작 요청 중…"
        page.stateText.setTextColor(Color.rgb(255, 214, 102))
        afterAction()
    }

    // ---- Refresh --------------------------------------------------------------------------

    private fun safeRefreshData() {
        try {
            refreshData()
        } catch (e: Throwable) {
            if (::statusText.isInitialized) {
                statusText.text = "화면 갱신 오류: ${e.message ?: e.javaClass.simpleName}"
                statusText.setTextColor(Color.rgb(255, 120, 120))
            }
        }
    }

    private fun shortTime(value: String): String =
        if (value.length >= 19 && value[4] == '-') value.substring(5, 19) else value

    private fun refreshData() {
        val w = watch()
        val config = TradeSettings.load(this)
        val watchOn = prefs.getBoolean(w.key("enabled"), false)

        statusText.text = when {
            config.autoBuy -> "● 자동매수 켜짐 · 감시 실행 중"
            watchOn -> "● 감시만 실행 중 (자동매수 꺼짐)"
            else -> "○ 꺼짐"
        }
        statusText.setTextColor(if (config.autoBuy || watchOn) Color.rgb(80, 238, 180) else Color.rgb(255, 190, 110))

        // Conditions: while the watch runs, the fields show the fixed conditions.
        val sc = w.config
        if (watchOn) fillFields(sc)
        if (watchOn != page.locked) setLocked(watchOn)
        page.stateText.text = if (watchOn) "● 실행 중 · 조건 고정" else "○ 대기 중 · 수정 가능"
        page.stateText.setTextColor(if (watchOn) Color.rgb(80, 238, 180) else Color.rgb(255, 190, 110))
        page.toggleButton.text = if (watchOn) "■  STOP SCANNER" else "▶  START SCANNER"
        page.toggleButton.background = if (watchOn) {
            rounded(Color.argb(190, 150, 60, 70), dp(16), Color.argb(150, 255, 150, 160))
        } else {
            rounded(Color.argb(150, 110, 130, 150), dp(16), Color.argb(150, 200, 225, 245))
        }
        page.toggleButton.alpha = if (watchOn && config.autoBuy) 0.5f else 1f

        val done = prefs.getInt(w.key("scan_progress_done"), 0)
        val total = prefs.getInt(w.key("scan_progress_total"), 0)
        val percent = prefs.getInt(w.key("scan_progress_percent"), 0).coerceIn(0, 100)
        val scanning = prefs.getBoolean(w.key("scan_in_progress"), false)
        page.progressBar.progress = if (watchOn) percent else 0
        val last = prefs.getString(w.key("last_scan_time"), "") ?: ""
        val matchesCount = prefs.getInt(w.key("last_match_count"), 0)
        val next = prefs.getString(w.key("next_scan"), "") ?: ""
        page.infoText.text = when {
            !watchOn -> "시작하면 위 조건으로 고정되어 감시합니다.\n검사 시각: ${sc.scheduleText()} (한국 시간) · 마감 전 봉 기준"
            scanning && total > 0 -> "검사 중  $done / $total · $percent%"
            else -> "검사 시각: ${sc.scheduleText()}\n최근 ${if (last.isBlank()) "없음" else shortTime(last)} · 감지 ${matchesCount}개\n다음 ${shortTime(next)}"
        }

        // Auto-buy
        val progress = TradeEngine.progress(this)
        if (progress != null && progress.entries.isNotEmpty()) {
            autoStateText.text = progressHeadline(progress, config)
            showProgress(progress)
            progressPanel.visibility = View.VISIBLE
        } else {
            autoStateText.text = TradeEngine.statusText(this)
            progressPanel.visibility = View.GONE
        }
        autoToggle.text = if (config.autoBuy) "자동매수 끄기" else "자동매수 켜기"
        autoToggle.background = rounded(
            if (config.autoBuy) Color.argb(190, 68, 82, 105) else Color.rgb(0, 170, 125), dp(14)
        )
        liquidateButton.alpha = if (TradeEngine.hasOpenSession(this)) 1f else 0.5f

        refreshLog()
    }

    /** The "기록" button: starts or stops writing the total return down every 10 minutes. */
    private fun toggleRecord() {
        val now = !TradeSettings.load(this).recordPct
        TradeSettings.setRecordPct(this, now)
        Toast.makeText(
            this,
            if (now) "10분마다 수익률을 기록합니다. 일자별 매매 기록에서 볼 수 있습니다." else "수익률 기록을 멈췄습니다.",
            Toast.LENGTH_SHORT
        ).show()
        safeRefreshData()
    }

    private fun pctLabel(value: Double): String =
        (if (value >= 0) "+" else "") + String.format(Locale.US, "%.2f", value) + "%"

    private fun progressHeadline(p: TradeEngine.Progress, config: TradeConfig): String {
        val head = when (p.phase) {
            TradeEngine.Phase.ACTIVE -> "진행 중 · 진입 ${p.entries.size}/${p.maxSlots} (보유 ${p.entries.count { !it.closed }}) · 종목당 ${String.format(Locale.US, "%.2f", p.slotUsdt)} USDT"
            TradeEngine.Phase.LIQUIDATING -> "전량 청산 진행 중 · ${p.reason}"
            TradeEngine.Phase.ENDED -> "오늘 종료 · ${p.reason}"
        }
        return head + "\n원금 ${String.format(Locale.US, "%.2f", p.startEquity)} USDT · 운용 시간 ${config.windowText()}"
    }

    private fun showProgress(p: TradeEngine.Progress) {
        pctText.text = pctLabel(p.pct)
        pctText.setTextColor(if (p.pct >= 0) green else Color.rgb(255, 99, 99))
        peakText.text = "최고 ${pctLabel(p.peakPct)}"
        val recording = TradeSettings.load(this).recordPct
        recordButton.text = if (recording) "● 기록 중 (10분마다)" else "○ 기록"
        recordButton.background = if (recording) {
            rounded(Color.argb(190, 30, 130, 100), dp(10), Color.argb(150, 120, 230, 190))
        } else {
            rounded(Color.argb(130, 60, 80, 100), dp(10), Color.argb(120, 150, 180, 200))
        }

        // Target
        val target = p.targetPct
        targetBar.progress = if (target > 0.0) (p.pct / target * 100.0).toInt().coerceIn(0, 100) else 0
        val remain = target - p.pct
        targetText.text = if (remain > 0.0) {
            "목표 ${pctLabel(target)} · 남은 ${String.format(Locale.US, "%.2f", remain)}%p"
        } else {
            "목표 ${pctLabel(target)} 도달"
        }

        // Trailing (protection) line
        when (p.trailMode) {
            0 -> {
                trailText.text = "보호선  사용 안 함"
                trailText.setTextColor(muted)
            }
            1 -> {
                trailText.text = "보호선  ○ 대기 중 · 최고 ${pctLabel(p.peakPct)} / 시작 ${pctLabel(p.trailStartPct)}\n" +
                    "       (최고 수익이 ${pctLabel(p.trailStartPct)}를 넘으면 작동)"
                trailText.setTextColor(Color.rgb(255, 190, 110))
            }
            else -> {
                val room = p.pct - p.trailLine
                trailText.text = "보호선  ● 작동 중 · 청산선 ${pctLabel(p.trailLine)}\n" +
                    "       현재 여유 ${String.format(Locale.US, "%.2f", room)}%p (하락 폭 ${String.format(Locale.US, "%.2f", p.trailDropPct)}%p, ${if (p.trailFixed) "고정" else "최고점 추종"})"
                trailText.setTextColor(if (room <= p.trailDropPct * 0.3) Color.rgb(255, 99, 99) else green)
            }
        }

        // Per-symbol sell target
        symbolTargetText.text = if (p.symbolTargetPct > 0.0) {
            "개별 매도  각 종목이 진입가 대비 ${pctLabel(p.symbolTargetPct)}에 닿으면 그 종목만 매도"
        } else {
            "개별 매도  사용 안 함 (⚙ 설정에서 입력)"
        }
        symbolTargetText.setTextColor(if (p.symbolTargetPct > 0.0) Color.WHITE else muted)

        // Purchases: one row per symbol with its own profit
        val structure = p.entries.joinToString("|") { "${it.symbol}:${it.closed}" }
        if (structure != entriesStructure) {
            entriesStructure = structure
            rebuildEntryRows(p)
        }
        for (e in p.entries) entryRows[e.symbol]?.let { updateEntryRow(it, e, p) }
        updatedText.text = if (p.updatedAt > 0L) {
            "수익률 갱신 ${logTimeFormat.format(java.util.Date(p.updatedAt)).substring(6)} · 약 5초마다 확인"
        } else {
            "수익률 확인 대기 중"
        }
    }

    /** Price change of one symbol since its entry (after a sale: until the sale). Null if unknown. */
    private fun entryGain(e: TradeEngine.TradeEntry): Double? {
        val price = if (e.closed && e.exitPrice > 0.0) e.exitPrice else e.lastPrice
        if (price <= 0.0 || e.entryPrice <= 0.0) return null
        return (price - e.entryPrice) / e.entryPrice * 100.0
    }

    private fun rebuildEntryRows(p: TradeEngine.Progress) {
        entriesBox.removeAllViews()
        entryRows.clear()
        for (e in p.entries) {
            val name = e.symbol.removeSuffix("USDT") + "/USDT"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(mono(if (e.closed) "✓ $name" else name, 14f, if (e.closed) muted else Color.WHITE, true, singleLine = true))
            val sub = mono("", 11.5f, muted, false, singleLine = false)
            left.addView(sub)
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            val pnl = mono("--", 16f, muted, true, singleLine = true).apply { gravity = Gravity.END }
            row.addView(pnl, LinearLayout.LayoutParams(dp(84), -2))
            if (!e.closed) {
                val sell = Button(this).apply {
                    text = "매도"
                    textSize = 12f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    background = rounded(Color.argb(190, 150, 60, 70), dp(10))
                    stateListAnimator = null
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { confirmSellOne(e.symbol, name) }
                }
                row.addView(sell, LinearLayout.LayoutParams(dp(52), dp(34)).apply { marginStart = dp(6) })
            }
            entriesBox.addView(row)
            entriesBox.addView(View(this).apply { setBackgroundColor(Color.argb(30, 255, 255, 255)) }, LinearLayout.LayoutParams(-1, 1))
            entryRows[e.symbol] = EntryRow(pnl, sub, e.symbol)
        }
    }

    private fun updateEntryRow(row: EntryRow, e: TradeEngine.TradeEntry, p: TradeEngine.Progress) {
        val gain = entryGain(e)
        row.pnl.text = if (gain == null) "--" else pctLabel(gain)
        row.pnl.setTextColor(
            when {
                gain == null -> muted
                e.closed -> Color.rgb(110, 220, 200)
                gain >= 0 -> green
                else -> Color.rgb(255, 99, 99)
            }
        )
        row.sub.text = if (e.closed) {
            "매도 완료 · ${e.exitReason}"
        } else {
            var line = "진입 ${trimPrice(e.entryPrice)} · 손절 ${trimPrice(e.stopPrice)}"
            if (p.symbolTargetPct > 0.0 && gain != null) {
                val left = p.symbolTargetPct - gain
                line += "\n개별 목표 ${pctLabel(p.symbolTargetPct)}까지 " +
                    (if (left > 0.0) "${String.format(Locale.US, "%.2f", left)}%p" else "도달")
            }
            line
        }
    }

    private fun confirmSellOne(symbol: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("$name 매도")
            .setMessage("이 종목만 지금 시장가로 매도합니다.\n거래소에 걸린 이 종목의 손절 주문도 함께 취소되고, 오늘 세션에서 다시 매수하지 않습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("매도") { _, _ ->
                sendService(ScannerService.ACTION_SELL_ONE, null, symbol)
                Toast.makeText(this, "$name 매도를 요청했습니다.", Toast.LENGTH_SHORT).show()
                afterAction()
            }
            .show()
    }

    private fun trimPrice(value: Double): String =
        java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

    // ---- Auto-buy log (short) ----------------------------------------------------------------

    private val buyPattern = Regex("^(\\S+) 매수 체결 · 수량 \\S+ · 진입 (\\S+) · 손절 (\\S+) · (\\d+/\\d+)")

    private fun logMillis(timePart: String): Long =
        try { logTimeFormat.parse(timePart)?.time ?: 0L } catch (_: Exception) { 0L }

    private fun refreshLog() {
        val lines = TradeEngine.logLines(this).takeLast(30).reversed()
        val signature = lines.joinToString("\n")
        if (signature == renderedLogSignature) return
        renderedLogSignature = signature
        logContainer.removeAllViews()
        if (lines.isEmpty()) {
            logContainer.addView(text("아직 자동매수 기록이 없습니다.", 13f, muted, false))
            return
        }
        val parsed = lines.map { line ->
            val parts = line.split(" | ", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else "" to line
        }
        // Red dot: the latest purchases (buys within 5 minutes of the newest buy).
        val newestBuy = parsed.firstOrNull { it.second.contains("매수 체결") }?.let { logMillis(it.first) } ?: 0L
        val today = logDayFormat.format(java.util.Date())
        for ((timePart, message) in parsed) {
            val isBuy = message.contains("매수 체결")
            val dot = isBuy && newestBuy > 0L && newestBuy - logMillis(timePart) <= 5 * 60_000L
            val timeLabel = when {
                timePart.length < 14 -> timePart
                timePart.startsWith(today) -> timePart.substring(6)
                else -> timePart.substring(0, 11)
            }
            logContainer.addView(logRow(timeLabel, message, dot))
        }
    }

    private fun logRow(timeLabel: String, message: String, dot: Boolean): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        val dotView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(redDot)
            }
            visibility = if (dot) View.VISIBLE else View.INVISIBLE
        }
        row.addView(dotView, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) })
        row.addView(mono(timeLabel, 12.5f, Color.rgb(150, 168, 184), false, singleLine = true), LinearLayout.LayoutParams(dp(92), -2))

        val buy = buyPattern.find(message)
        val shown: String
        val color: Int
        if (buy != null) {
            val name = buy.groupValues[1].removeSuffix("USDT") + "/USDT"
            shown = "$name  매수 @${buy.groupValues[2]}\n손절 ${buy.groupValues[3]} · ${buy.groupValues[4]}"
            color = green
        } else {
            shown = message
            color = when {
                message.contains("긴급") || message.contains("실패") || message.contains("오류") -> Color.rgb(255, 99, 99)
                message.contains("매도 완료") -> Color.rgb(110, 220, 200)
                message.contains("청산") || message.contains("종료") -> Color.rgb(255, 190, 110)
                message.contains("건너뜀") -> Color.rgb(150, 168, 184)
                else -> Color.WHITE
            }
        }
        row.addView(mono(shown, 13f, color, buy != null, singleLine = false), LinearLayout.LayoutParams(0, -2, 1f))
        wrapper.addView(row)
        wrapper.addView(View(this).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) }, LinearLayout.LayoutParams(-1, 1))
        return wrapper
    }

    private fun mono(value: String, size: Float, color: Int, bold: Boolean, singleLine: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("monospace", if (bold) Typeface.BOLD else Typeface.NORMAL)
        if (singleLine) setSingleLine(true)
    }

    // ---- Actions --------------------------------------------------------------------------

    private fun sendService(action: String, watchId: String?, symbol: String? = null) {
        try {
            val intent = Intent(this, ScannerService::class.java).apply {
                this.action = action
                if (watchId != null) putExtra(ScannerService.EXTRA_WATCH_ID, watchId)
                if (symbol != null) putExtra(ScannerService.EXTRA_SYMBOL, symbol)
            }
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Toast.makeText(this, "요청 실패: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun afterAction() {
        handler.postDelayed({ safeRefreshData() }, 1000)
        handler.postDelayed({ safeRefreshData() }, 4000)
    }

    /** The gear button: auto-buy settings, daily trade records, API test, background setting. */
    private fun showSettingsMenu() {
        val items = arrayOf("자동매수 설정 (API 키 · 시간 · 금액)", "일자별 매매 기록", "API 연결 테스트", "백그라운드 유지 설정")
        AlertDialog.Builder(this)
            .setTitle("설정")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showTradeSettingsDialog()
                    1 -> startActivity(Intent(this, TradeDaysActivity::class.java))
                    2 -> testConnection()
                    3 -> openBatterySettings()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun onAutoToggle() {
        val config = TradeSettings.load(this)
        if (config.autoBuy) {
            TradeSettings.setAutoBuy(this, false)
            sendService(ScannerService.ACTION_START, null)
            if (TradeEngine.hasOpenSession(this)) {
                Toast.makeText(
                    this,
                    "자동매수를 껐습니다. 이미 산 종목은 그대로 남고, 손절 주문은 거래소에 걸려 있습니다. 필요하면 '지금 전량 청산'을 누르세요.",
                    Toast.LENGTH_LONG
                ).show()
            }
            afterAction()
            safeRefreshData()
            return
        }
        if (!TradeSettings.hasKeys(this)) {
            Toast.makeText(this, "먼저 자동매수 설정에서 API 키를 저장하세요.", Toast.LENGTH_LONG).show()
            showTradeSettingsDialog()
            return
        }
        val testNet = config.baseUrl != TradeSettings.DEFAULT_BASE_URL
        val message = "실제 주문이 나가는 기능입니다.\n\n" +
            "• 운용 시간: ${config.windowText()} (한국 시간)\n" +
            "• 종목당: 원금의 ${formatNumber(config.slotPct)}% · 레버리지 ${config.leverage}배 · 최대 ${config.maxSlots}종목\n" +
            "• 목표 수익률: +${formatNumber(config.targetPct)}%\n" +
            "• 보호선: " + (if (config.trailStartPct > 0.0) (if (config.trailFixed) "+${formatNumber(config.trailStartPct)}% 넘은 뒤 +${formatNumber(config.trailStartPct - config.trailDropPct)}% 이하로 내려오면 청산 (고정)" else "+${formatNumber(config.trailStartPct)}% 넘은 뒤 최고점에서 ${formatNumber(config.trailDropPct)}%p 하락 시 청산") else "사용 안 함") + "\n" +
            "• 개별 종목 매도: " + (if (config.symbolTargetPct > 0.0) "각 종목이 진입가 대비 +${formatNumber(config.symbolTargetPct)}%에 닿으면 그 종목만 매도" else "사용 안 함") + "\n" +
            "• 종료 시각 전량 청산: ${if (config.endLiquidate) "예" else "아니오"}\n" +
            "• API 주소: ${config.baseUrl}" + (if (testNet) "  (기본 주소가 아님)" else "") + "\n\n" +
            "운용 시간 안에 켜면 지금의 USDT 잔고를 원금으로 잡고 바로 시작합니다. 켜 두면 매일 정해진 시각에 자동으로 시작합니다."
        AlertDialog.Builder(this)
            .setTitle("자동매수를 켤까요?")
            .setMessage(message)
            .setNegativeButton("취소", null)
            .setPositiveButton("켜기") { _, _ ->
                // Conditions that were typed but not started yet are saved (and then fixed).
                if (!page.locked) {
                    val cfg = readConfig() ?: return@setPositiveButton
                    WatchStore.updateConfig(this, watch().id, cfg)
                    setLocked(true)
                }
                TradeSettings.setAutoBuy(this, true)
                sendService(ScannerService.ACTION_START, watch().id)
                afterAction()
                safeRefreshData()
            }
            .show()
    }

    private fun confirmLiquidate() {
        if (!TradeEngine.hasOpenSession(this)) {
            Toast.makeText(this, "청산할 보유 종목이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("전량 청산")
            .setMessage("이번 세션에서 산 모든 종목을 시장가로 팔고 손절 주문을 취소합니다. 이번 운용 시간에는 더 이상 매수하지 않습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("청산") { _, _ ->
                sendService(ScannerService.ACTION_LIQUIDATE, null)
                afterAction()
            }
            .show()
    }

    private fun testConnection() {
        val config = TradeSettings.load(this)
        val key = TradeSettings.getKey(this)
        val secret = TradeSettings.getSecret(this)
        if (key.isNullOrBlank() || secret.isNullOrBlank()) {
            Toast.makeText(this, "먼저 자동매수 설정에서 API 키를 저장하세요.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "연결 확인 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val message = try {
                val api = BinanceApi(config.baseUrl, key, secret)
                api.syncTime()
                val balance = api.balanceUsdt()
                val hedge = api.isHedgeMode()
                "연결 성공\n\n" +
                    "USDT 지갑 잔고: ${"%.4f".format(Locale.US, balance.wallet)}\n" +
                    "미실현 손익: ${"%.4f".format(Locale.US, balance.unrealized)}\n" +
                    "사용 가능: ${"%.4f".format(Locale.US, balance.available)}\n" +
                    "포지션 모드: ${if (hedge) "헤지 모드" else "단방향 모드"}\n" +
                    "주소: ${config.baseUrl}"
            } catch (e: Exception) {
                "연결 실패: ${e.message ?: e.javaClass.simpleName}\n\nAPI 키의 선물 거래 권한과 IP 제한 설정을 확인하세요."
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("API 연결 테스트")
                    .setMessage(message)
                    .setPositiveButton("확인", null)
                    .show()
            }
        }.start()
    }

    private fun openBatterySettings() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "설정 > 앱 > Binance Auto Buy > 배터리에서 제한 없음을 선택하세요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copySymbol(symbol: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("symbol", symbol))
            Toast.makeText(this, "$symbol 복사됨", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "복사하지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Dialogs --------------------------------------------------------------------------

    private fun caption(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun numberField(initial: String, hintText: String): EditText = EditText(this).apply {
        setText(initial)
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)
    }

    private fun textField(initial: String, hintText: String, password: Boolean): EditText = EditText(this).apply {
        setText(initial)
        hint = hintText
        inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        setSingleLine(true)
    }

    /** "20:00", "8:30" or "20" -> minutes of the day; null when the text is not a time. */
    private fun parseTime(value: String): Int? {
        val t = value.trim()
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size > 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = if (parts.size == 2) (parts[1].trim().toIntOrNull() ?: return null) else 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun showTradeSettingsDialog() {
        val current = TradeSettings.load(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }

        val saved = TradeSettings.maskedKey(this)
        val keyField = textField("", if (saved != null) "저장된 키 $saved (바꿀 때만 입력)" else "API Key", false)
        val secretField = textField("", if (saved != null) "저장됨 (바꿀 때만 입력)" else "API Secret", true)
        column.addView(caption("바이낸스 API Key  (선물 거래 권한만, 출금 권한은 끄세요)"))
        column.addView(keyField)
        column.addView(caption("바이낸스 API Secret"))
        column.addView(secretField)

        val urlField = textField(current.baseUrl, TradeSettings.DEFAULT_BASE_URL, false)
        column.addView(caption("API 주소 (테스트 환경을 쓸 때만 바꾸세요)"))
        column.addView(urlField)

        val startField = textField(minText(current.startMin), "예: 20:00", false)
        column.addView(caption("자동매수 시작 시각 (한국 시간, 이때부터 매수하며 매일 자동 시작)"))
        column.addView(startField)

        val endField = textField(minText(current.endMin), "예: 08:00", false)
        column.addView(caption("종료 시각 (한국 시간)"))
        column.addView(endField)

        val endBox = CheckBox(this).apply {
            text = "종료 시각에 남은 종목 전량 청산"
            isChecked = current.endLiquidate
        }
        column.addView(endBox)

        val slotField = numberField(formatNumber(current.slotPct), "예: 9.5")
        column.addView(caption("종목당 투입 비율 (시작 원금 대비 %, 증거금 기준)"))
        column.addView(slotField)

        val levField = numberField(current.leverage.toString(), "예: 1")
        column.addView(caption("레버리지 (배)"))
        column.addView(levField)

        val maxField = numberField(current.maxSlots.toString(), "예: 10")
        column.addView(caption("최대 매수 종목 수"))
        column.addView(maxField)

        val targetField = numberField(formatNumber(current.targetPct), "예: 5")
        column.addView(caption("목표 수익률 n (원금 대비 총 수익 %, 도달하면 전량 청산)"))
        column.addView(targetField)

        val trailStartField = numberField(formatNumber(current.trailStartPct), "예: 3  (0이면 사용 안 함)")
        column.addView(caption("보호선 시작 (총 수익 %가 이 값을 넘으면 보호선 작동, 0이면 사용 안 함)"))
        column.addView(trailStartField)

        val trailDropField = numberField(formatNumber(current.trailDropPct), "예: 1")
        column.addView(caption("보호선 하락 폭 (%p. 고정이면 시작점에서, 아니면 최고 수익에서 이만큼 내려오면 전량 청산)"))
        column.addView(trailDropField)

        val trailFixedBox = CheckBox(this).apply {
            text = "청산선을 보호선 시작점에 고정 (예: 시작 5, 폭 4 → 최고점이 올라도 +1%에서 청산). 끄면 최고점을 따라 청산선도 올라감"
            isChecked = current.trailFixed
        }
        column.addView(trailFixedBox)

        val symbolTargetField = numberField(formatNumber(current.symbolTargetPct), "예: 3  (0이면 사용 안 함)")
        column.addView(caption("개별 종목 목표 (진입가 대비 가격 상승 %. 예: 3 → 각 종목이 +3%에 닿으면 그 종목만 매도, 0이면 사용 안 함)"))
        column.addView(symbolTargetField)

        column.addView(TextView(this).apply {
            text = "API 키는 이 폰의 보안 저장소(Keystore)로 암호화해서 저장합니다. 변경한 값은 이미 시작한 세션에는 적용되지 않고, 다음 세션부터 적용됩니다(종목당 금액은 세션 시작 때 정해집니다)."
            textSize = 12f
            setPadding(0, dp(12), 0, dp(6))
        })

        val scroll = ScrollView(this).apply { addView(column) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("자동매수 설정")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            fun fail(message: String) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            val slot = slotField.text.toString().trim().toDoubleOrNull()
            if (slot == null || slot <= 0.0) { fail("종목당 비율은 0보다 큰 숫자로 입력하세요."); return@setOnClickListener }
            val leverage = levField.text.toString().trim().toIntOrNull()
            if (leverage == null || leverage !in 1..20) { fail("레버리지는 1~20 사이의 정수로 입력하세요."); return@setOnClickListener }
            val maxSlots = maxField.text.toString().trim().toIntOrNull()
            if (maxSlots == null || maxSlots !in 1..20) { fail("최대 종목 수는 1~20 사이의 정수로 입력하세요."); return@setOnClickListener }
            if (slot * maxSlots > 100.0) { fail("종목당 비율 × 최대 종목 수가 100%를 넘을 수 없습니다."); return@setOnClickListener }
            val target = targetField.text.toString().trim().toDoubleOrNull()
            if (target == null || target <= 0.0) { fail("목표 수익률은 0보다 큰 숫자로 입력하세요."); return@setOnClickListener }
            val trailStart = trailStartField.text.toString().trim().toDoubleOrNull()
            if (trailStart == null || trailStart < 0.0) { fail("보호선 시작은 0 이상의 숫자로 입력하세요. (0이면 사용 안 함)"); return@setOnClickListener }
            val trailDrop = trailDropField.text.toString().trim().toDoubleOrNull()
            if (trailDrop == null || trailDrop <= 0.0) { fail("보호선 하락 폭은 0보다 큰 숫자로 입력하세요."); return@setOnClickListener }
            val symbolTarget = symbolTargetField.text.toString().trim().toDoubleOrNull()
            if (symbolTarget == null || symbolTarget < 0.0) { fail("개별 종목 목표는 0 이상의 숫자로 입력하세요. (0이면 사용 안 함)"); return@setOnClickListener }
            if (trailStart > 0.0 && trailStart >= target) { fail("보호선 시작은 목표 수익률보다 작아야 합니다."); return@setOnClickListener }
            val startMin = parseTime(startField.text.toString())
            val endMin = parseTime(endField.text.toString())
            if (startMin == null || endMin == null) { fail("시각은 20:00 처럼 입력하세요."); return@setOnClickListener }
            if (startMin == endMin) { fail("시작 시각과 종료 시각이 같을 수 없습니다."); return@setOnClickListener }
            var baseUrl = urlField.text.toString().trim().trimEnd('/')
            if (baseUrl.isEmpty()) baseUrl = TradeSettings.DEFAULT_BASE_URL
            if (!baseUrl.startsWith("https://")) { fail("API 주소는 https:// 로 시작해야 합니다."); return@setOnClickListener }
            val newKey = keyField.text.toString().trim()
            val newSecret = secretField.text.toString().trim()
            if (newKey.isNotEmpty() != newSecret.isNotEmpty()) { fail("API Key와 Secret은 함께 입력하세요."); return@setOnClickListener }

            if (newKey.isNotEmpty()) {
                try {
                    TradeSettings.setKeys(this, newKey, newSecret)
                } catch (e: Exception) {
                    fail("API 키를 저장하지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
                    return@setOnClickListener
                }
            }
            TradeSettings.save(
                this,
                TradeConfig(
                    autoBuy = current.autoBuy,
                    baseUrl = baseUrl,
                    startMin = startMin,
                    endMin = endMin,
                    endLiquidate = endBox.isChecked,
                    slotPct = slot,
                    leverage = leverage,
                    maxSlots = maxSlots,
                    targetPct = target,
                    trailStartPct = trailStart,
                    trailDropPct = trailDrop,
                    symbolTargetPct = symbolTarget,
                    trailFixed = trailFixedBox.isChecked
                )
            )
            Toast.makeText(this, "저장했습니다.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            handler.postDelayed({ safeRefreshData() }, 500)
        }
    }

    // ---- View helpers ---------------------------------------------------------------------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.argb(125, 25, 42, 62), dp(20), Color.argb(110, 111, 161, 184))
    }

    private fun miniBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Color.argb(70, 20, 36, 56), dp(14), Color.argb(90, 111, 161, 184))
    }

    private fun inputBox(decimal: Boolean, hintText: String): EditText = EditText(this).apply {
        hint = hintText
        inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        textSize = 17f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.argb(110, 255, 255, 255))
        background = null
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun miniCard(caption: String, edit: EditText, suffix: String): LinearLayout {
        val box = miniBox()
        box.addView(text(caption, 11f, muted, true))
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(text(suffix, 14f, muted, false))
        box.addView(line)
        return box
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1.02f)
    }

    private fun button(label: String, color: Int): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(color, dp(13))
        stateListAnimator = null
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun margin(left: Int, top: Int, right: Int, bottom: Int) =
        LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }
}
