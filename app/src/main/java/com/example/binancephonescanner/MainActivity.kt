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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Main screen of the auto-buy app: the auto-buy switch and its status on top, then the single
 * watch (conditions, scan progress, latest detections) and the trade log.
 */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences(ScannerService.PREFS, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var autoStateText: TextView
    private lateinit var autoToggle: Button
    private lateinit var liquidateButton: Button
    private lateinit var watchStateText: TextView
    private lateinit var conditionText: TextView
    private lateinit var watchToggle: Button
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var summaryText: TextView
    private lateinit var matchContainer: LinearLayout
    private lateinit var logText: TextView
    private var renderedMatchSignature = "__NOT_RENDERED__"

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
        val root = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 13, 28), Color.rgb(15, 31, 49), Color.rgb(18, 20, 38))
            )
        }
        setContentView(root)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        root.addView(column)

        column.addView(text("BINANCE AUTO BUY", 21f, Color.rgb(236, 249, 255), true))
        column.addView(text("USDT PERPETUAL  ·  LONG ONLY  ·  AUTO TRADING", 11f, Color.rgb(78, 232, 193), true), margin(0, 3, 0, 8))
        statusText = text("상태 확인 중…", 14f, Color.WHITE, true)
        column.addView(statusText, margin(0, 0, 0, 12))

        // Auto-buy card
        val autoCard = card()
        autoCard.addView(text("자동매수", 18f, Color.WHITE, true))
        autoStateText = text("", 13f, Color.rgb(197, 214, 229), false)
        autoCard.addView(autoStateText, margin(0, 8, 0, 12))
        val autoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        autoToggle = button("자동매수 켜기", Color.rgb(0, 200, 137))
        autoToggle.setOnClickListener { onAutoToggle() }
        val autoSettings = button("자동매수 설정", Color.rgb(96, 82, 160))
        autoSettings.setOnClickListener { showTradeSettingsDialog() }
        autoRow.addView(autoToggle, LinearLayout.LayoutParams(0, dp(46), 3f))
        autoRow.addView(autoSettings, LinearLayout.LayoutParams(0, dp(46), 2f).apply { marginStart = dp(8) })
        autoCard.addView(autoRow, margin(0, 0, 0, 8))
        val toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val testButton = button("API 연결 테스트", Color.rgb(38, 116, 180))
        testButton.setOnClickListener { testConnection() }
        liquidateButton = button("지금 전량 청산", Color.rgb(150, 60, 70))
        liquidateButton.setOnClickListener { confirmLiquidate() }
        toolRow.addView(testButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        toolRow.addView(liquidateButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
        autoCard.addView(toolRow)
        column.addView(autoCard, margin(0, 0, 0, 12))

        // Watch card
        val watchCard = card()
        watchCard.addView(text("감시", 18f, Color.WHITE, true))
        watchStateText = text("○ 꺼짐", 15f, Color.rgb(255, 190, 110), true)
        watchCard.addView(watchStateText, margin(0, 6, 0, 0))
        conditionText = text("", 12f, Color.rgb(197, 214, 229), false)
        watchCard.addView(conditionText, margin(0, 8, 0, 12))
        progressText = text("검사 진행률 대기 중", 14f, Color.rgb(236, 249, 255), true)
        watchCard.addView(progressText, margin(0, 0, 0, 7))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(34, 197, 145))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 65, 83))
            minimumHeight = dp(10)
        }
        watchCard.addView(progressBar, LinearLayout.LayoutParams(-1, dp(10)).apply { bottomMargin = dp(12) })
        val watchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        watchToggle = button("감시 시작", Color.rgb(0, 200, 137))
        watchToggle.setOnClickListener { toggleWatch() }
        val history = button("기록 확인", Color.rgb(38, 116, 180))
        history.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java).apply {
                putExtra(ScannerService.EXTRA_WATCH_ID, watch().id)
            })
        }
        watchRow.addView(watchToggle, LinearLayout.LayoutParams(0, dp(46), 3f))
        watchRow.addView(history, LinearLayout.LayoutParams(0, dp(46), 2f).apply { marginStart = dp(8) })
        watchCard.addView(watchRow, margin(0, 0, 0, 8))
        val settings = button("조건 · 시간 설정", Color.rgb(96, 82, 160))
        settings.setOnClickListener { showSettingsDialog() }
        watchCard.addView(settings, LinearLayout.LayoutParams(-1, dp(44)))
        column.addView(watchCard, margin(0, 0, 0, 12))

        // Latest result card
        val resultCard = card()
        resultCard.addView(text("최근 검사 결과", 16f, Color.WHITE, true))
        summaryText = text("아직 검사 기록이 없습니다.", 13f, Color.rgb(197, 214, 229), false)
        resultCard.addView(summaryText, margin(0, 8, 0, 14))
        resultCard.addView(text("감지 종목", 15f, Color.WHITE, true))
        matchContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultCard.addView(matchContainer, margin(0, 8, 0, 0))
        column.addView(resultCard, margin(0, 0, 0, 12))

        // Trade log card
        val logCard = card()
        logCard.addView(text("자동매수 기록 (최근 순)", 16f, Color.WHITE, true))
        logText = text("아직 기록이 없습니다.", 12f, Color.rgb(197, 214, 229), false)
        logCard.addView(logText, margin(0, 8, 0, 0))
        column.addView(logCard, margin(0, 0, 0, 12))

        val battery = button("백그라운드 유지 설정", Color.rgb(38, 116, 180))
        battery.setOnClickListener { openBatterySettings() }
        column.addView(battery, LinearLayout.LayoutParams(-1, dp(44)))
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

        autoStateText.text = TradeEngine.statusText(this)
        autoToggle.text = if (config.autoBuy) "자동매수 끄기" else "자동매수 켜기"
        autoToggle.background = rounded(if (config.autoBuy) Color.rgb(68, 82, 105) else Color.rgb(0, 200, 137), dp(13))
        liquidateButton.alpha = if (TradeEngine.hasOpenSession(this)) 1f else 0.5f

        val sc = w.config
        watchStateText.text = if (watchOn) "● 실행 중 · ${sc.label}" else "○ 꺼짐 · ${sc.label}"
        watchStateText.setTextColor(if (watchOn) Color.rgb(80, 238, 180) else Color.rgb(255, 190, 110))
        conditionText.text = sc.conditionText() +
            "\n• 검사 시각: ${sc.scheduleText()} (한국 시간)\n※ 아직 마감되지 않은 봉 기준입니다."
        watchToggle.text = if (watchOn) "감시 중지" else "감시 시작"
        watchToggle.background = rounded(if (watchOn) Color.rgb(68, 82, 105) else Color.rgb(0, 200, 137), dp(13))

        val done = prefs.getInt(w.key("scan_progress_done"), 0)
        val total = prefs.getInt(w.key("scan_progress_total"), 0)
        val percent = prefs.getInt(w.key("scan_progress_percent"), 0).coerceIn(0, 100)
        val scanning = prefs.getBoolean(w.key("scan_in_progress"), false)
        progressText.text = if (total > 0) {
            "${if (scanning) "검사 중" else "검사 진행률"}  $done / $total · $percent%"
        } else {
            "검사 진행률 대기 중"
        }
        progressBar.progress = percent

        val last = prefs.getString(w.key("last_scan_time"), "없음") ?: "없음"
        val scanned = prefs.getInt(w.key("last_scanned"), 0)
        val matchesCount = prefs.getInt(w.key("last_match_count"), 0)
        val failures = prefs.getInt(w.key("last_failures"), 0)
        val next = prefs.getString(w.key("next_scan"), "감시 시작 후 표시") ?: "감시 시작 후 표시"
        val outcome = prefs.getString(w.key("last_outcome"), "대기 중") ?: "대기 중"
        summaryText.text = "최근 검사: $last\n검사 종목: ${scanned}개\n조건 충족: ${matchesCount}개\n요청 실패/시간 초과: ${failures}개\n결과: $outcome\n다음 검사: $next"

        val lines = TradeEngine.logLines(this)
        logText.text = if (lines.isEmpty()) "아직 기록이 없습니다." else lines.takeLast(15).reversed().joinToString("\n")

        renderMatches(w)
    }

    private fun renderMatches(w: Watch) {
        val matches = try {
            JSONArray(prefs.getString(w.key("last_matches"), "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val signature = (0 until matches.length()).joinToString("|") { i ->
            val m = matches.optJSONObject(i) ?: JSONObject()
            "${m.optString("symbol")}:${m.optDouble("changePct")}:${m.optDouble("volumeMultiple")}"
        }
        if (signature == renderedMatchSignature) return
        renderedMatchSignature = signature
        matchContainer.removeAllViews()
        if (matches.length() == 0) {
            matchContainer.addView(text("조건 충족 종목 없음 (최근 검사 기준)", 13f, Color.rgb(174, 194, 211), false))
            return
        }
        for (i in 0 until matches.length()) {
            val item = matches.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol")
            val price = item.optDouble("price")
            val highPct = item.optDouble("highPct")
            val changePct = item.optDouble("changePct")
            val volumeMultiple = item.optDouble("volumeMultiple")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(Color.argb(110, 30, 52, 73), dp(14))
            }
            row.addView(text(symbol, 17f, Color.rgb(91, 235, 194), true))
            row.addView(text(
                "현재가 ${formatPrice(price)}\n고가/시가 +${"%.2f".format(Locale.US, highPct)}%  ·  현재가/시가 +${"%.2f".format(Locale.US, changePct)}%\n거래량 ${"%.2f".format(Locale.US, volumeMultiple)}배",
                12f, Color.rgb(213, 227, 239), false
            ), margin(0, 5, 0, 8))
            val copy = button("종목명 복사", Color.rgb(0, 142, 116))
            copy.setOnClickListener { copySymbol(symbol) }
            row.addView(copy, LinearLayout.LayoutParams(-1, dp(42)))
            matchContainer.addView(row, margin(0, 0, 0, 8))
        }
    }

    // ---- Actions --------------------------------------------------------------------------

    private fun sendService(action: String, watchId: String?) {
        try {
            val intent = Intent(this, ScannerService::class.java).apply {
                this.action = action
                if (watchId != null) putExtra(ScannerService.EXTRA_WATCH_ID, watchId)
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

    private fun toggleWatch() {
        val w = watch()
        val config = TradeSettings.load(this)
        if (config.autoBuy) {
            Toast.makeText(this, "자동매수가 켜져 있는 동안은 감시를 끌 수 없습니다. 먼저 자동매수를 끄세요.", Toast.LENGTH_LONG).show()
            return
        }
        val enabled = prefs.getBoolean(w.key("enabled"), false)
        sendService(if (enabled) ScannerService.ACTION_STOP else ScannerService.ACTION_START, w.id)
        watchStateText.text = if (enabled) "◷ 중지 요청 중…" else "◷ 시작 요청 중…"
        watchStateText.setTextColor(Color.rgb(255, 214, 102))
        afterAction()
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
            "• 보호선: " + (if (config.trailStartPct > 0.0) "+${formatNumber(config.trailStartPct)}% 넘은 뒤 최고점에서 ${formatNumber(config.trailDropPct)}%p 하락 시 청산" else "사용 안 함") + "\n" +
            "• 종료 시각 전량 청산: ${if (config.endLiquidate) "예" else "아니오"}\n" +
            "• API 주소: ${config.baseUrl}" + (if (testNet) "  (기본 주소가 아님)" else "") + "\n\n" +
            "운용 시간 안에 켜면 지금의 USDT 잔고를 원금으로 잡고 바로 시작합니다. 켜 두면 매일 정해진 시각에 자동으로 시작합니다."
        AlertDialog.Builder(this)
            .setTitle("자동매수를 켤까요?")
            .setMessage(message)
            .setNegativeButton("취소", null)
            .setPositiveButton("켜기") { _, _ ->
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
        column.addView(caption("보호선 하락 폭 (%p, 최고 수익에서 이만큼 내려오면 전량 청산)"))
        column.addView(trailDropField)

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
                    trailDropPct = trailDrop
                )
            )
            Toast.makeText(this, "저장했습니다.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            handler.postDelayed({ safeRefreshData() }, 500)
        }
    }

    /** The scan conditions of the watch: candle size, thresholds and scan minutes. */
    private fun showSettingsDialog() {
        val w = watch()
        val current = w.config

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }

        val intervalKeys = ScanOptions.INTERVALS.keys.toList()
        val intervalSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                ScanOptions.INTERVALS.values.toList()
            )
            setSelection(intervalKeys.indexOf(current.interval).coerceAtLeast(0))
        }
        column.addView(caption("봉 종류 (타임프레임)"))
        column.addView(intervalSpinner)

        val highField = numberField(formatNumber(current.highPct), "예: 5")
        column.addView(caption("고가가 시가 대비 몇 % 이상 오른 적이 있는가 (%)"))
        column.addView(highField)

        val liveField = numberField(formatNumber(current.livePct), "예: 4")
        column.addView(caption("현재가가 시가 대비 몇 % 이상 올라 있는가 (%)"))
        column.addView(liveField)

        val volumeField = numberField(formatNumber(current.volumeMultiple), "예: 4.5")
        column.addView(caption("거래량이 직전 완료봉의 몇 배 이상인가 (배)"))
        column.addView(volumeField)

        val minutesField = EditText(this).apply {
            setText(current.minutes.joinToString(", "))
            hint = "예: 50  또는  0, 15, 30, 45"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        column.addView(caption("검사할 시각: 매시 몇 분에 검사할까요 (0~59, 쉼표로 여러 개, 한국 시간)"))
        column.addView(minutesField)

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun preset(label: String, minutes: String) {
            val chip = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setOnClickListener { minutesField.setText(minutes) }
            }
            presets.addView(chip, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        preset("정각", "0")
        preset("50분", "50")
        preset("59분", "59")
        preset("15분마다", "0, 15, 30, 45")
        column.addView(presets)
        column.addView(TextView(this).apply {
            text = "매시 지정한 분의 5초에 검사합니다. 봉이 마감되기 전 시각으로 정하면 진행 중인 봉을 검사합니다. 변경한 값은 다음 검사부터 적용됩니다."
            textSize = 12f
            setPadding(0, dp(10), 0, dp(6))
        })

        val scroll = ScrollView(this).apply { addView(column) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("조건 · 시간 설정")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val high = highField.text.toString().trim().toDoubleOrNull()
            val live = liveField.text.toString().trim().toDoubleOrNull()
            val volume = volumeField.text.toString().trim().toDoubleOrNull()
            val minutes = ScanOptions.parseMinutes(minutesField.text.toString())
            val problem = when {
                high == null || high < 0.0 -> "고가 상승률은 0 이상의 숫자로 입력하세요."
                live == null || live < 0.0 -> "현재가 상승률은 0 이상의 숫자로 입력하세요."
                volume == null || volume <= 0.0 -> "거래량 배수는 0보다 큰 숫자로 입력하세요."
                minutes == null -> "검사 시각은 0~59 사이의 숫자를 쉼표로 구분해 입력하세요."
                else -> null
            }
            if (problem != null || high == null || live == null || volume == null || minutes == null) {
                Toast.makeText(this, problem ?: "입력값을 확인하세요.", Toast.LENGTH_LONG).show()
            } else {
                val interval = intervalKeys[intervalSpinner.selectedItemPosition.coerceIn(0, intervalKeys.size - 1)]
                WatchStore.updateConfig(this, w.id, ScanConfig(interval, minutes, high, live, volume))
                if (prefs.getBoolean(w.key("enabled"), false)) {
                    sendService(ScannerService.ACTION_START, w.id)
                }
                Toast.makeText(this, "저장했습니다. 다음 검사부터 적용됩니다.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                handler.postDelayed({ safeRefreshData() }, 800)
            }
        }
    }

    // ---- View helpers ---------------------------------------------------------------------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.argb(125, 25, 42, 62), dp(20), Color.argb(110, 111, 161, 184))
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

    private fun formatPrice(value: Double): String =
        if (value >= 1000) "%,.3f".format(Locale.US, value)
        else "%.8f".format(Locale.US, value).trimEnd('0').trimEnd('.')

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }
}
