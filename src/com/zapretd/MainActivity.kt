package com.zapretd

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var palette: Palette
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var heroCard: LinearLayout
    private lateinit var power: PowerButton
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var segmentVpn: TextView
    private lateinit var segmentRoot: TextView
    private lateinit var modeHint: TextView
    private lateinit var presetValue: TextView
    private lateinit var logView: TextView

    private var busy = false
    private var rootRunning = false
    private var rootChecked = false
    private var renderedState = ""

    private val ticker = object : Runnable {
        override fun run() {
            refresh(true)
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        palette = Palette(this)
        normalizePreset()
        styleSystemBars()
        setContentView(buildContent())
        refresh(false)
    }

    override fun onResume() {
        super.onResume()
        if (settings.useRoot && !rootChecked) {
            rootChecked = true
            Thread {
                val running = try {
                    RootEngine.isRunning()
                } catch (e: Exception) {
                    false
                }
                runOnUiThread { rootRunning = running }
            }.start()
        }
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                launchVpn()
            } else {
                AppLog.add("Разрешение на VPN не выдано")
                refresh(false)
            }
        }
    }

    private fun normalizePreset() {
        try {
            val known = Catalog.options(this, settings.useRoot).map { it.name }
            if (known.isNotEmpty() && settings.selected !in known) settings.select(known[0])
        } catch (e: Exception) {
            AppLog.add("Пресеты: ${e.message}")
        }
    }

    private fun styleSystemBars() {
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        var flags = 0
        if (!palette.isDark) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) flags = flags or 0x10
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun isRunning(): Boolean =
        if (settings.useRoot) rootRunning else ZapretVpnService.running

    private fun isStarting(): Boolean =
        busy || (!settings.useRoot && ZapretVpnService.starting)

    private fun toggle() {
        if (isStarting()) return
        if (isRunning()) stopProtection() else startProtection()
    }

    private fun startProtection() {
        if (!settings.anyServiceEnabled()) {
            toast("Включи хотя бы один сервис")
            return
        }
        if (settings.useRoot) {
            busy = true
            refresh(false)
            Thread {
                val result = try {
                    RootEngine.start(this, settings)
                } catch (e: Exception) {
                    EngineResult(false, e.message ?: "Не удалось запустить")
                }
                runOnUiThread {
                    busy = false
                    rootRunning = result.ok
                    AppLog.add(result.message)
                    if (!result.ok) toast(result.message)
                    refresh(true)
                }
            }.start()
        } else {
            val permission = VpnService.prepare(this)
            if (permission != null) {
                startActivityForResult(permission, REQUEST_VPN)
            } else {
                launchVpn()
            }
        }
    }

    private fun launchVpn() {
        startService(Intent(this, ZapretVpnService::class.java))
        refresh(false)
    }

    private fun stopProtection() {
        if (settings.useRoot) {
            busy = true
            refresh(false)
            Thread {
                RootEngine.stop()
                runOnUiThread {
                    busy = false
                    rootRunning = false
                    AppLog.add("Остановлено")
                    refresh(true)
                }
            }.start()
        } else {
            startService(Intent(this, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_STOP))
            AppLog.add("Остановлено")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun label(text: String, size: Float, color: Int, medium: Boolean = false): TextView {
        val view = TextView(this)
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        view.setTextColor(color)
        if (medium) view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        return view
    }

    private fun params(top: Int = 0): LinearLayout.LayoutParams {
        val result = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        result.topMargin = dp(top)
        return result
    }

    private fun card(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = shape(palette.surface, 24)
        card.setPadding(dp(20), dp(18), dp(20), dp(18))
        return card
    }

    private fun buildContent(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(palette.background)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(20), dp(20), dp(20), dp(32))

        column.addView(label("Zapret", 34f, palette.onSurface, true))
        column.addView(label("Discord и YouTube без блокировок", 14f, palette.onSurfaceVariant), params(2))
        column.addView(buildHero(), params(22))
        column.addView(buildModeCard(), params(12))
        column.addView(buildPresetCard(), params(12))
        column.addView(buildScanButton(), params(12))
        column.addView(buildServicesCard(), params(12))
        column.addView(buildLogCard(), params(12))

        scroll.addView(column)
        return scroll
    }

    private fun buildHero(): View {
        heroCard = LinearLayout(this)
        heroCard.orientation = LinearLayout.VERTICAL
        heroCard.setGravity(Gravity.CENTER_HORIZONTAL)
        heroCard.setPadding(dp(24), dp(28), dp(24), dp(26))

        power = PowerButton(this, palette)
        power.setOnClickListener { toggle() }
        heroCard.addView(power, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusTitle = label("", 22f, palette.onSurface, true)
        statusTitle.setGravity(Gravity.CENTER)
        heroCard.addView(statusTitle, params(18))

        statusSub = label("", 14f, palette.onSurfaceVariant)
        statusSub.setGravity(Gravity.CENTER)
        heroCard.addView(statusSub, params(4))
        return heroCard
    }

    private fun segment(text: String): TextView {
        val view = label(text, 14f, palette.onSurface, true)
        view.setGravity(Gravity.CENTER)
        view.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        return view
    }

    private fun buildModeCard(): View {
        val card = card()
        card.addView(label("Режим работы", 12f, palette.primary, true))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = shape(0, 20, palette.outline, 1)
        bar.setPadding(dp(3), dp(3), dp(3), dp(3))

        segmentVpn = segment("VPN · без root")
        segmentRoot = segment("Root · nfqws")
        segmentVpn.setOnClickListener { chooseMode(false) }
        segmentRoot.setOnClickListener { chooseMode(true) }
        bar.addView(segmentVpn)
        bar.addView(segmentRoot)

        val barParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        barParams.topMargin = dp(10)
        card.addView(bar, barParams)

        modeHint = label("", 13f, palette.onSurfaceVariant)
        card.addView(modeHint, params(10))
        return card
    }

    private fun chooseMode(root: Boolean) {
        if (isRunning() || isStarting()) {
            toast("Сначала выключи защиту")
            return
        }
        settings.useRoot = root
        normalizePreset()
        refresh(false)
    }

    private fun buildPresetCard(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.background = rippleShape(palette.surface, 24, palette.primary)
        row.setPadding(dp(20), dp(16), dp(20), dp(16))
        row.isClickable = true
        row.setOnClickListener { showPresets() }

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(label("Пресет", 12f, palette.primary, true))
        presetValue = label(settings.selected, 17f, palette.onSurface)
        texts.addView(presetValue, params(2))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("›", 28f, palette.onSurfaceVariant))
        return row
    }

    private fun buildScanButton(): View {
        val button = label("Подобрать лучший пресет", 15f, palette.onPrimaryContainer, true)
        button.setGravity(Gravity.CENTER)
        button.background = rippleShape(palette.primaryContainer, 26, palette.primary)
        button.isClickable = true
        button.setOnClickListener { showScan() }
        button.layoutParams = params(12).also { it.height = dp(52) }
        return button
    }

    private fun serviceRow(title: String, subtitle: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.setPadding(0, dp(8), 0, dp(8))

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(label(title, 16f, palette.onSurface))
        texts.addView(label(subtitle, 13f, palette.onSurfaceVariant))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = M3Switch(this, palette)
        toggle.setChecked(initial, false)
        toggle.onChange = {
            onChange(it)
            if (isRunning()) toast("Перезапусти защиту, чтобы применить")
        }
        row.addView(toggle)
        row.setOnClickListener { toggle.performClick() }
        return row
    }

    private fun buildServicesCard(): View {
        val card = card()
        card.addView(label("Сервисы", 12f, palette.primary, true))
        card.addView(serviceRow("Discord", "Чат, голос, CDN и медиа", settings.discord) { settings.discord = it })
        card.addView(serviceRow("YouTube", "Видео, превью, googlevideo", settings.youtube) { settings.youtube = it })
        card.addView(serviceRow("Другие сайты", "Cloudflare и остальное из списка", settings.other) { settings.other = it })
        return card
    }

    private fun buildLogCard(): View {
        val card = card()
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.setGravity(Gravity.CENTER_VERTICAL)
        header.addView(label("Журнал", 12f, palette.primary, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clear = label("Очистить", 13f, palette.primary, true)
        clear.setPadding(dp(8), dp(4), dp(8), dp(4))
        clear.setOnClickListener {
            AppLog.clear()
            refresh(false)
        }
        header.addView(clear)
        card.addView(header)

        logView = label("", 11f, palette.onSurfaceVariant)
        logView.typeface = Typeface.MONOSPACE
        logView.setTextIsSelectable(true)
        card.addView(logView, params(8))
        return card
    }

    private fun refresh(animate: Boolean) {
        val active = isRunning()
        val starting = isStarting()
        val state = "$active|$starting|${settings.useRoot}|${settings.selected}"

        if (state != renderedState) {
            val previous = renderedState
            renderedState = state
            val wasActive = previous.startsWith("true")
            power.setActive(active, animate && wasActive != active)
            power.busy = starting
            heroCard.background = shape(if (active) palette.primaryContainer else palette.surface, 28)
            statusTitle.setTextColor(if (active) palette.onPrimaryContainer else palette.onSurface)
            statusSub.setTextColor(if (active) palette.onPrimaryContainer else palette.onSurfaceVariant)
            statusTitle.text = when {
                starting && active -> "Останавливаю…"
                starting -> "Запускаю…"
                active -> "Защита включена"
                else -> "Защита выключена"
            }
            val modeName = if (settings.useRoot) "Root · nfqws" else "VPN · ByeDPI"
            statusSub.text = if (active) "$modeName · ${settings.selected}" else "Нажми кнопку, чтобы включить"
            styleSegments()
            presetValue.text = settings.selected
        }
        val text = AppLog.text()
        if (logView.text.toString() != text) logView.text = if (text.isEmpty()) "Пока пусто" else text
    }

    private fun styleSegments() {
        val root = settings.useRoot
        paintSegment(segmentVpn, !root, "VPN · без root")
        paintSegment(segmentRoot, root, "Root · nfqws")
        modeHint.text = if (root) {
            "nfqws и iptables: пресеты zapret работают как в оригинале. Нужен root и ядро с NFQUEUE."
        } else {
            "VPN и ByeDPI: свои стратегии, не пресеты zapret. Пакетные трюки zapret (seqovl, fooling) без root недоступны. UDP не обрабатывается."
        }
    }

    private fun paintSegment(view: TextView, selected: Boolean, title: String) {
        view.text = if (selected) "✓  $title" else title
        view.setTextColor(if (selected) palette.onSecondaryContainer else palette.onSurfaceVariant)
        view.background = if (selected) shape(palette.secondaryContainer, 18) else null
    }

    private fun radio(selected: Boolean): View {
        val ring = shape(0, 10, if (selected) palette.primary else palette.outline, 2)
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL)
        val dot = shape(palette.primary, 10)
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL)
        val layers = if (selected) LayerDrawable(arrayOf(ring, dot)) else LayerDrawable(arrayOf(ring))
        if (selected) layers.setLayerInset(1, dp(5), dp(5), dp(5), dp(5))
        val view = View(this)
        view.background = layers
        view.layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        return view
    }

    private fun showPresets() {
        val presets = try {
            Catalog.options(this, settings.useRoot)
        } catch (e: Exception) {
            toast("Не удалось прочитать пресеты")
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = shape(palette.surfaceHigh, 28)
        box.setPadding(dp(24), dp(24), dp(24), dp(12))
        box.addView(label("Пресет", 24f, palette.onSurface))
        val hint = if (settings.useRoot) "Полные пресеты из zapret" else "Стратегии ByeDPI для режима без root"
        box.addView(label(hint, 13f, palette.onSurfaceVariant), params(4))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        for (preset in presets) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setGravity(Gravity.CENTER_VERTICAL)
            row.setPadding(dp(8), 0, dp(8), 0)
            row.background = rippleShape(0, 16, palette.primary)
            row.addView(radio(preset.name == settings.selected))
            val name = label(preset.name, 16f, palette.onSurface)
            val nameParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            nameParams.leftMargin = dp(16)
            row.addView(name, nameParams)
            row.setOnClickListener {
                settings.select(preset.name)
                if (isRunning()) toast("Перезапусти защиту, чтобы применить")
                refresh(false)
                dialog.dismiss()
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }

        val scroll = ScrollView(this)
        scroll.addView(list)
        val maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        val listHeight = minOf(dp(52) * presets.size, maxHeight)
        val scrollParams = params(12)
        scrollParams.height = listHeight
        box.addView(scroll, scrollParams)

        val cancel = label("Отмена", 14f, palette.primary, true)
        cancel.setPadding(dp(16), dp(12), dp(16), dp(12))
        cancel.setOnClickListener { dialog.dismiss() }
        val cancelParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cancelParams.gravity = Gravity.END
        cancelParams.topMargin = dp(8)
        box.addView(cancel, cancelParams)

        val frame = FrameLayout(this)
        frame.addView(box)
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0))
        dialog.show()
        val width = minOf(resources.displayMetrics.widthPixels - dp(48), dp(420))
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showScan() {
        if (isRunning() || isStarting()) {
            toast("Сначала выключи защиту")
            return
        }
        if (!settings.anyServiceEnabled()) {
            toast("Включи хотя бы один сервис")
            return
        }
        val presets = try {
            Catalog.scannable(this, settings.useRoot)
        } catch (e: Exception) {
            toast("Не удалось прочитать пресеты")
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = shape(palette.surfaceHigh, 28)
        box.setPadding(dp(24), dp(24), dp(24), dp(12))
        box.addView(label("Подбор пресета", 24f, palette.onSurface))
        val status = label("Проверяю 0 из ${presets.size}", 13f, palette.onSurfaceVariant)
        box.addView(status, params(4))
        val baselineView = label("Без обхода: проверяю…", 13f, palette.onSurfaceVariant)
        box.addView(baselineView, params(4))

        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = presets.size
        bar.progressTintList = android.content.res.ColorStateList.valueOf(palette.primary)
        bar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(palette.outlineVariant)
        box.addView(bar, params(14))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(list)
        val scrollParams = params(12)
        scrollParams.height = (resources.displayMetrics.heightPixels * 0.4f).toInt()
        box.addView(scroll, scrollParams)

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setGravity(Gravity.END)
        val cancel = label("Отмена", 14f, palette.primary, true)
        cancel.setPadding(dp(16), dp(12), dp(16), dp(12))
        actions.addView(cancel)
        box.addView(actions, params(8))

        val frame = FrameLayout(this)
        frame.addView(box)
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0))
        dialog.show()
        dialog.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(48), dp(420)), ViewGroup.LayoutParams.WRAP_CONTENT)

        val tester = PresetTester(this, settings)
        val results = ArrayList<ProbeResult>()
        cancel.setOnClickListener {
            tester.cancelled = true
            dialog.dismiss()
        }

        fun addRow(result: ProbeResult) {
            val text = if (result.passed > 0) {
                val head = "${result.preset}  ·  ${result.detail}  ·  ${result.ms} мс"
                if (result.passed < result.total && result.note.isNotBlank()) head + "\n" + result.note else head
            } else {
                "${result.preset}  ·  не прошёл\n${result.note}"
            }
            val row = label(text, 14f, if (result.passed > 0) palette.onSurface else palette.onSurfaceVariant)
            row.setPadding(0, dp(6), 0, dp(6))
            list.addView(row)
        }

        fun finish() {
            list.removeAllViews()
            val good = results.filter { it.passed > 0 }
                .sortedWith(compareByDescending<ProbeResult> { it.passed }.thenBy { it.ms })
            val bad = results.filter { it.passed == 0 }
            good.forEach { addRow(it) }
            if (bad.isNotEmpty()) {
                val text = label("Не прошли: ${bad.size}", 13f, palette.onSurfaceVariant, true)
                text.setPadding(0, dp(10), 0, 0)
                list.addView(text)
                bad.forEach { addRow(it) }
            }
            actions.removeAllViews()
            val close = label("Закрыть", 14f, palette.primary, true)
            close.setPadding(dp(16), dp(12), dp(16), dp(12))
            close.setOnClickListener { dialog.dismiss() }
            actions.addView(close)
            val bestYoutube = results.filter { it.youtube.passed > 0 }
                .sortedWith(compareByDescending<ProbeResult> { it.youtube.passed }.thenBy { it.youtube.ms }).firstOrNull()
            val bestDiscord = results.filter { it.discord.passed > 0 }
                .sortedWith(compareByDescending<ProbeResult> { it.discord.passed }.thenBy { it.discord.ms }).firstOrNull()

            val comboMode = !settings.useRoot
            val nothing = if (comboMode) bestYoutube == null && bestDiscord == null else good.isEmpty()
            if (nothing) {
                status.text = "Ни один пресет не прошёл. Проверь интернет и режим"
            } else {
                val lines = ArrayList<String>()
                if (comboMode) {
                    if (bestYoutube != null) lines.add("YouTube: ${bestYoutube.preset} · ${bestYoutube.youtube.passed}/${bestYoutube.youtube.total} · ${bestYoutube.youtube.ms} мс")
                    if (bestDiscord != null) lines.add("Discord: ${bestDiscord.preset} · ${bestDiscord.discord.passed}/${bestDiscord.discord.total} · ${bestDiscord.discord.ms} мс")
                } else {
                    lines.add("Лучший: ${good[0].preset} · ${good[0].detail} · ${good[0].ms} мс")
                }
                status.text = lines.joinToString("\n")
                val apply = label(if (comboMode) "Применить комбо" else "Применить", 14f, palette.onPrimary, true)
                apply.setPadding(dp(20), dp(12), dp(20), dp(12))
                apply.background = rippleShape(palette.primary, 20, palette.onPrimary)
                apply.setOnClickListener {
                    if (comboMode) {
                        if (bestYoutube != null) settings.comboYoutube = bestYoutube.preset
                        if (bestDiscord != null) settings.comboDiscord = bestDiscord.preset
                        settings.select(Catalog.COMBO)
                        toast("Включено комбо")
                    } else {
                        settings.select(good[0].preset)
                        toast("Выбран ${good[0].preset}")
                    }
                    refresh(false)
                    dialog.dismiss()
                }
                val applyParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                applyParams.leftMargin = dp(8)
                actions.addView(apply, applyParams)
            }
        }

        Thread {
            try {
                tester.scan(presets) { result ->
                    runOnUiThread {
                        if (!dialog.isShowing) return@runOnUiThread
                        if (result.baseline) {
                            baselineView.text = if (result.passed > 0) "Без обхода: ${result.detail} · ${result.ms} мс\n${result.note}" else "Без обхода: не открывается\n${result.note}"
                            return@runOnUiThread
                        }
                        results.add(result)
                        bar.progress = results.size
                        status.text = "Проверяю ${results.size} из ${presets.size}"
                        addRow(result)
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            } catch (e: Exception) {
                AppLog.add("Подбор: ${e.message}")
            }
            runOnUiThread {
                if (dialog.isShowing && !tester.cancelled) finish()
            }
        }.start()
    }

    companion object {
        private const val REQUEST_VPN = 41
    }
}
