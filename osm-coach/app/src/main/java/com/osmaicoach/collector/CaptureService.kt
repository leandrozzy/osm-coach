package com.osmaicoach.collector

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Só observa o OSM (packageNames no XML) e só tira screenshot enquanto existe uma sessão ativa.
 * Não clica, não toca, não executa nenhuma ação no jogo.
 */
class CaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var overlay: TextView? = null
    private var armedAt = 0L

    companion object {
        @Volatile
        var instance: CaptureService? = null
    }

    override fun onServiceConnected() {
        Health.install(applicationContext)
        SlotWidget.attach(applicationContext)
        Health.beat(applicationContext)
        instance = this
        Diag.serviceConnected = true
        Diag.lastError = null
        startLoop()
        startWatchdog()
        KeepAliveService.start(applicationContext)
    }

    private var watchdog: kotlinx.coroutines.Job? = null

    /**
     * Alguns celulares (ASUS/ROG, Xiaomi) apagam os alarmes quando o app é deslizado para fora dos recentes. Este
     * serviço o sistema religa sozinho: aqui os alarmes são conferidos a cada minuto e refeitos (e os avisos que
     * ficaram atrasados saem na hora).
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { Notifier.reschedule(applicationContext) } catch (e: Exception) { Diag.lastError = "Avisos: " + (e.message ?: e.javaClass.simpleName) }
                delay(60_000L)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == OSM_PACKAGE) Diag.lastOsmEventAt = System.currentTimeMillis()
    }

    override fun onInterrupt() {
        Diag.lastError = "Serviço de acessibilidade interrompido pelo Android"
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeOverlay()
        Diag.serviceConnected = false
        instance = null
        loop?.cancel()
        watchdog?.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlay()
        Diag.serviceConnected = false
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            val pipeline = FramePipeline.get(applicationContext)
            while (isActive) {
                Health.beat(applicationContext)
                val sid = Control.activeSession(applicationContext)
                // Botão "Encerrar" por cima do jogo enquanto captura (some quando o próprio app está na tela).
                withContext(Dispatchers.Main) { syncOverlay(sid != null) }
                if (sid == null) {
                    delay(1500L)
                    continue
                }
                // O próprio OSM Coach na tela: não é o jogo. Antes as abas Tática/Elenco/Resumo do app eram lidas como
                // "Relatório"/"Tática"/"Estádio" do OSM e podiam gravar lixo nos slots.
                if (AppVisible.resumed || rootInActiveWindow?.packageName?.toString() == packageName) {
                    delay(1200L)
                    continue
                }
                try {
                    // Os botões somem por um instante na hora da foto: o OCR lê o texto do jogo que estava atrás
                    // deles (antes lia "Encerrar"/"App" colado nos nomes, estatísticas e notas).
                    val hid = withContext(Dispatchers.Main) {
                        val b = overlayBox
                        if (b != null && b.alpha > 0f) { b.alpha = 0f; true } else false
                    }
                    if (hid) delay(60L)
                    val bmp = try { takeShot() } finally {
                        if (hid) withContext(Dispatchers.Main) { overlayBox?.alpha = 0.95f }
                    }
                    if (bmp != null) {
                        Overlay.shotClean = hid
                        try {
                            pipeline.onShot(sid, bmp)
                        } finally {
                            Overlay.shotClean = false
                            bmp.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Diag.lastError = "Captura: " + (e.message ?: e.javaClass.simpleName)
                }
                delay(pipeline.nextDelayMs())
            }
        }
    }

    private var overlayBox: LinearLayout? = null
    private var toggleView: TextView? = null
    private var noticeView: TextView? = null
    private var cardView: LinearLayout? = null
    private var listView: LinearLayout? = null
    private var headView: TextView? = null
    private var badgeView: TextView? = null
    private var ringView: View? = null
    private var statusView: TextView? = null
    private var ringAnim: android.animation.ObjectAnimator? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var hintJob: Job? = null
    private var hintKey = ""
    private var hints: List<Hints.Hint> = emptyList()
    private var hintSlot = 0
    private var hintType = ""

    /** Aviso curto por cima do OSM (ex.: tática montada diferente da gerada). Some sozinho. */
    /** O serviço foi (re)ligado com a capacidade de gestos (config nova)? */
    fun canSwipeAway(): Boolean = (serviceInfo?.capabilities ?: 0) and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0

    fun notice(text: String) {
        mainExecutor.execute {
            val tv = noticeView ?: return@execute
            tv.text = text
            tv.visibility = View.VISIBLE
            tv.removeCallbacks(hideNotice)
            tv.postDelayed(hideNotice, 10000L)
        }
    }

    /** Guarda onde os botões estão na tela para o OCR não ler o próprio painel do app. */
    private fun markBounds(v: View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val sh = resources.displayMetrics.heightPixels.toFloat()
        if (sw <= 0f || sh <= 0f) return
        Overlay.bounds = floatArrayOf(loc[0] / sw, loc[1] / sh, (loc[0] + v.width) / sw, (loc[1] + v.height) / sh)
    }

    private val hideNotice = Runnable { noticeView?.visibility = View.GONE }

    /** Texto da tática do slot atual (ou do próximo jogo, se o slot não foi reconhecido). */
    private suspend fun tacticText(): String = try {
        val repo = Repo(applicationContext)
        val now = System.currentTimeMillis()
        val slot = Diag.currentSlot.takeIf { it > 0 } ?: (1..4).mapNotNull { s ->
            val p = repo.dao.plan(s, "tactic") ?: return@mapNotNull null
            val j = runCatching { org.json.JSONObject(p.json) }.getOrNull() ?: return@mapNotNull null
            val f = repo.fieldMap(s)
            if (!Director.tacticValid(j, p.at, f, now)) return@mapNotNull null
            Pair(s, f[K.MATCH_AT]?.value?.toLongOrNull() ?: Long.MAX_VALUE)
        }.minByOrNull { it.second }?.first ?: 0
        if (slot <= 0) "Entre no slot (pré-jogo) para eu saber qual tática mostrar."
        else {
            val p = repo.dao.plan(slot, "tactic")
            val j = p?.json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            if (p == null || j == null) "S$slot: nenhuma tática gerada. Gere no app (aba Tática)."
            else {
                val ok = Director.tacticValid(j, p.at, repo.fieldMap(slot), now)
                val ch = rivalChangesOf(repo, slot)
                val warn = if (ch.isNotEmpty()) "⚠ O rival mudou: " + RivalWatch.headline(ch) + ". Gere uma nova tática.\n" else ""
                if (!ok) {
                    val f = repo.fieldMap(slot)
                    val next = f[K.RIVAL_TEAM]?.value?.takeIf { FieldMerge.known(it) }
                    val at = f[K.MATCH_AT]?.value?.toLongOrNull()
                    val gaps = Director.tacticGaps(f, now)
                    "S$slot" + (next?.let { " • próximo jogo vs $it" } ?: "") + "\n" +
                        "Ainda não há tática para este jogo (a guardada era contra ${j.optString("rival")}).\n" +
                        (if (at != null && at - now > Director.TACTIC_WINDOW_MS) "Ela é gerada nas últimas 8 h antes do jogo."
                        else if (gaps.isNotEmpty()) "Antes de gerar, leia: " + gaps.joinToString(", ") { it.first } + "."
                        else "Toque em 📱 App → Tática para gerar.")
                } else {
                    "S$slot • vs ${j.optString("rival")}\n" + warn + Director.tacticSummary(j)
                }
            }
        }
    } catch (e: Exception) {
        "Não consegui carregar a tática: " + (e.message ?: e.javaClass.simpleName)
    }

    private fun onTacticTap() {
        scope.launch {
            val t = tacticText()
            withContext(Dispatchers.Main) { showCard(listOf(Hints.Hint("📋", "Tática", t)), "Tática do jogo", allowDone = false) }
        }
    }

    private fun removeOverlay() {
        val v = overlayBox ?: return
        hintJob?.cancel()
        hintJob = null
        hintKey = ""
        Overlay.bounds = null
        overlayBox = null
        overlay = null
        toggleView = null
        noticeView = null
        cardView = null
        listView = null
        headView = null
        badgeView = null
        ringAnim?.cancel(); ringAnim = null; ringView = null; statusView = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            // a janela já foi removida pelo sistema
        }
    }

    /** Chamado pela tela do app ao abrir/fechar: troca o rótulo do botão de alternar na hora. */
    fun refreshOverlay() {
        mainExecutor.execute { updateToggle() }
    }

    private fun updateToggle() {
        toggleView?.text = if (AppVisible.resumed) "⚽ OSM" else "📱 App"
    }

    private fun chip(text: String, color: Int, dp: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(android.graphics.Color.WHITE)
        textSize = 12f
        setPadding((12 * dp).toInt(), (7 * dp).toInt(), (12 * dp).toInt(), (7 * dp).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 40 * dp
            setColor(color)
        }
    }

    /** Monta a lista do cartão: cada dica com ícone, título, detalhe e o "✓ feito". */
    private var selGroup = ""
    private fun showCard(list: List<Hints.Hint>, head: String, allowDone: Boolean = true) {
        val card = cardView ?: return
        val lv = listView ?: return
        val dp = resources.displayMetrics.density
        headView?.text = head
        lv.removeAllViews()
        if (list.isEmpty()) {
            lv.addView(TextView(this).apply {
                text = "✅ Nada pendente."
                setTextColor(0xFFB8C7E6.toInt()); textSize = 12f
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            })
        }
        // botões de grupo (Tática, Treinamento, Jogadores, Administração, Outros): só a lista do grupo escolhido aparece
        val groups = Hints.GROUPS.filter { g -> list.any { Hints.groupOf(it) == g } }
        if (groups.size > 1) {
            if (selGroup !in groups) selGroup = groups.firstOrNull { g -> list.any { it.action && Hints.groupOf(it) == g } } ?: groups.first()
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (g in groups) {
                val n = list.count { Hints.groupOf(it) == g }
                val on = g == selGroup
                chips.addView(TextView(this).apply {
                    text = g.substringBefore(' ') + " " + n
                    textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(if (on) android.graphics.Color.WHITE else 0xFFB8C7E6.toInt())
                    setTypeface(typeface, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                    background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(if (on) 0xFF2563EB.toInt() else 0x332AB4FF) }
                    setOnClickListener { selGroup = g; showCard(list, head, allowDone) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (6 * dp).toInt() })
            }
            lv.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
            lv.addView(TextView(this).apply { text = selGroup; setTextColor(0xFF7CC8FF.toInt()); textSize = 12f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) })
        } else selGroup = groups.firstOrNull() ?: ""
        for (h in list.filter { groups.size <= 1 || Hints.groupOf(it) == selGroup }.take(6)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((8 * dp).toInt(), (5 * dp).toInt(), (8 * dp).toInt(), (5 * dp).toInt())
                background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0x332AB4FF) }
            }
            row.addView(TextView(this).apply { text = h.icon; textSize = 18f; setPadding(0, 0, (8 * dp).toInt(), 0) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = h.title; setTextColor(android.graphics.Color.WHITE); textSize = 14f; maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            col.addView(TextView(this).apply { text = h.detail; setTextColor(0xFFC3D3EE.toInt()); textSize = 12f; maxLines = if (allowDone) 3 else 30; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (allowDone) {
                val done = TextView(this).apply {
                    text = "✓"; textSize = 16f; setTextColor(0xFF22C55E.toInt()); gravity = Gravity.CENTER
                    setPadding((10 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
                    setOnClickListener {
                        Hints.dismiss(applicationContext, h.title)
                        hints = hints.filter { it.title != h.title }
                        lv.removeView(row)
                        updateBadge()
                    }
                }
                row.addView(done)
            }
            lv.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() })
        }
        card.visibility = View.VISIBLE
        overlayBox?.post { overlayBox?.let { markBounds(it) } }
    }

    private fun updateBadge() {
        val b = badgeView ?: return
        val n = hints.count { it.action }
        b.text = n.toString()
        b.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    /**
     * Estado visual da bola (só aparência): anel lima = parada, anel ciano pulsando + selo "Capturando" quando uma captura aconteceu nos
     * últimos 5 s, anel âmbar quando há pendências e nada sendo lido.
     */
    private fun updateRing() {
        val r = ringView ?: return
        val dp = resources.displayMetrics.density
        val nowMs = System.currentTimeMillis()
        val capturing = nowMs - Diag.lastShotAt < 5000L && Diag.lastShotAt > 0L
        val pending = hints.any { it.action }
        // leitura de lista (Plantel, Calendário, Mercado, Resultado): estado vem do CONTEÚDO lido (chegou ao fim ou não), nunca do tempo; o tempo só decide quanto a mensagem fica na tela
        val rs = Diag.readState
        val recentRead = nowMs - Diag.readAt < 25_000L && Diag.readType == Diag.currentType
        val reading = rs == 1 && capturing
        val done = rs == 2 && recentRead
        val partial = rs == 3 && recentRead
        val col = when { done -> 0xFFB9F363.toInt(); partial -> 0xFFF5B942.toInt(); capturing -> 0xFF39BDF8.toInt(); pending -> 0xFFF5B942.toInt(); else -> 0xFFB9F363.toInt() }
        val spinning = capturing && !done && !partial
        // lendo/capturando: arco giratório (dá para ver que está trabalhando e quanto tempo ficar na tela); parado: anel cheio; terminou: anel verde + "pulinho"
        r.background = if (spinning) ArcRing(col, 3.5f * dp) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x00000000); setStroke((3.5f * dp).toInt(), col) }
        statusView?.let { s ->
            s.visibility = if (capturing || done || partial) View.VISIBLE else View.GONE
            val (txt, bg) = when {
                done -> "✓ Capturado" to 0xF2B9F363.toInt()
                partial -> "⚠ Incompleto" to 0xF2F5B942.toInt()
                reading -> "● Lendo… fique e role" to 0xF239BDF8.toInt()
                else -> "● Capturando" to 0xF239BDF8.toInt()
            }
            s.text = txt
            (s.background as? GradientDrawable)?.setColor(bg)
        }
        if (spinning) {
            if (ringAnim == null) ringAnim = android.animation.ObjectAnimator.ofFloat(r, "rotation", 0f, 360f).apply {
                duration = 1000; interpolator = android.view.animation.LinearInterpolator(); repeatCount = android.animation.ValueAnimator.INFINITE; start()
            }
        } else {
            ringAnim?.cancel(); ringAnim = null; r.rotation = 0f; r.alpha = 1f
            if (done && lastDonePop != Diag.readAt) {          // uma vez por leitura concluída
                lastDonePop = Diag.readAt
                r.animate().scaleX(1.25f).scaleY(1.25f).setDuration(180).withEndAction { r.animate().scaleX(1f).scaleY(1f).setDuration(220).start() }.start()
            }
        }
    }

    private var lastDonePop = 0L

    /** Anel com um arco de 270° (o resto apagado): girando vira o indicador de "lendo". */
    private class ArcRing(private val color: Int, private val stroke: Float) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke; strokeCap = android.graphics.Paint.Cap.ROUND }
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds; val m = stroke / 2f
            val rect = android.graphics.RectF(b.left + m, b.top + m, b.right - m, b.bottom - m)
            p.color = (color and 0x00FFFFFF) or 0x40000000; c.drawOval(rect, p)
            p.color = color; c.drawArc(rect, 0f, 270f, false, p)
        }
        override fun setAlpha(a: Int) { p.alpha = a }
        override fun setColorFilter(f: android.graphics.ColorFilter?) { p.colorFilter = f }
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Recalcula o que fazer quando a tela/slot muda ou chega leitura nova; dica que sumiu = feito. */
    private fun startHints() {
        if (hintJob != null) return
        hintJob = scope.launch {
            while (isActive) {
                val type = Diag.currentType
                val slot = Diag.currentSlot
                val key = "$type|$slot|${Diag.lastUpdateAt}"
                if (key != hintKey && type != ScreenType.NON_OSM.name) {
                    hintKey = key
                    val st = runCatching { ScreenType.valueOf(type) }.getOrDefault(ScreenType.OTHER_OSM)
                    val fresh = runCatching { Hints.forScreen(applicationContext, slot, st) }.getOrDefault(emptyList())
                    val sameScreen = slot == hintSlot && type == hintType
                    val gone = if (sameScreen) hints.filter { o -> fresh.none { it.title == o.title } && o.icon != "📋" } else emptyList()
                    hints = fresh
                    hintSlot = slot
                    hintType = type
                    withContext(Dispatchers.Main) {
                        updateBadge()
                        if (gone.isNotEmpty()) notice("✅ Feito: " + gone.first().title)
                        if (cardView?.visibility == View.VISIBLE && headView?.text?.startsWith("Tática") != true) {
                            showCard(hints, (if (slot > 0) "S$slot • " else "OSM • ") + Hints.screenLabel(st))
                        }
                    }
                }
                withContext(Dispatchers.Main) { updateRing() }
                delay(1500L)
            }
        }
    }

    /**
     * Bola por cima do jogo (como os apps de coach): mostra quantas coisas há para fazer na tela aberta e, ao
     * tocar, o que fazer (treinar tal, vender tal por X, melhorar o estádio, a tática). Arrastável; gruda na
     * borda. App / Tática / Encerrar ficam dentro do cartão.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun syncOverlay(show: Boolean) {
        if (!show) {
            removeOverlay()
            return
        }
        val cur = overlay
        if (cur != null) {
            if (armedAt > 0L && System.currentTimeMillis() - armedAt > 4000L) {
                armedAt = 0L
                cur.text = "■ Encerrar"
            }
            updateToggle()
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val sw = resources.displayMetrics.widthPixels
        val bubbleSize = (58 * dp).toInt()
        val maxW = minOf((340 * dp).toInt(), (sw * 0.78f).toInt())

        // bola: só o logo do app (sem círculo de fundo) + contador vermelho
        val bubble = android.widget.FrameLayout(this)
        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.g_icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            elevation = 9 * dp
        }
        val ring = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x00000000); setStroke((3.5f * dp).toInt(), 0xFFB9F363.toInt()) }
        }
        bubble.addView(ring, android.widget.FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER))
        bubble.addView(icon, android.widget.FrameLayout.LayoutParams(bubbleSize - (10 * dp).toInt(), bubbleSize - (10 * dp).toInt(), Gravity.CENTER))
        val badge = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFEF5350.toInt()); setStroke((1.5f * dp).toInt(), android.graphics.Color.WHITE) }
            elevation = 10 * dp
            visibility = View.GONE
        }
        bubble.addView(badge, android.widget.FrameLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt(), Gravity.TOP or Gravity.END))

        // cartão: título, lista de dicas e os botões App / Tática / Encerrar
        val head = TextView(this).apply {
            setTextColor(0xFFB9F363.toInt()); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val end = chip("■ Encerrar", 0xFFEF5350.toInt(), dp)
        val toggle = chip("📱 App", 0xFFB9F363.toInt(), dp).apply { setTextColor(0xFF0B111B.toInt()) }
        val tactic = chip("📋 Tática", 0xFF1C2938.toInt(), dp)
        val statusPill = chip("● Capturando", 0xF239BDF8.toInt(), dp).apply { setTextColor(0xFF0B111B.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD); visibility = View.GONE }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun lp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (6 * dp).toInt() }
            addView(toggle, lp()); addView(tactic, lp()); addView(end, lp())
        }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xF5151F2D.toInt())
                cornerRadius = 20 * dp
                setStroke((1.5f * dp).toInt(), 0xCCB9F363.toInt())
            }
            elevation = 12 * dp
            addView(head)
            addView(scroll, LinearLayout.LayoutParams(maxW, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() })
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * dp).toInt() })
            visibility = View.GONE
        }
        // a lista não passa de ~55% da tela (o resto rola)
        scroll.layoutParams = LinearLayout.LayoutParams(maxW, LinearLayout.LayoutParams.WRAP_CONTENT)
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            val cap = (resources.displayMetrics.heightPixels * 0.38f).toInt()
            if (scroll.height > cap) scroll.layoutParams = scroll.layoutParams.apply { height = cap }
        }
        val notice = chip("", 0xF2F5B942.toInt(), dp).apply {
            setTextColor(android.graphics.Color.BLACK)
            maxWidth = maxW
            maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        fun gap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * dp).toInt() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0.97f
            addView(bubble, LinearLayout.LayoutParams(bubbleSize + (6 * dp).toInt(), bubbleSize + (6 * dp).toInt()))
            addView(statusPill, gap())
            addView(card, gap())
            addView(notice, gap())
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.30f).toInt()
        }
        overlayLp = lp
        fun dragOrTap(v: View, snap: Boolean, onTap: () -> Unit) {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            v.setOnTouchListener { _: View, e: MotionEvent ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (kotlin.math.abs(dx) > 12 * dp || kotlin.math.abs(dy) > 12 * dp) moved = true
                        if (moved) {
                            lp.x = (startX + if (lp.gravity and Gravity.END == Gravity.END) -dx.toInt() else dx.toInt()).coerceAtLeast(0)
                            lp.y = (startY + dy.toInt()).coerceAtLeast(0)
                            try { wm.updateViewLayout(box, lp) } catch (ex: Exception) { }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) onTap()
                        else if (snap) {
                            // gruda na borda mais perto (esquerda ou direita), como as bolhas de chat
                            val right = e.rawX > sw / 2f
                            lp.gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
                            lp.x = 0
                            box.gravity = if (right) Gravity.END else Gravity.START
                            try { wm.updateViewLayout(box, lp) } catch (ex: Exception) { }
                        }
                        box.post { markBounds(box) }
                    }
                }
                true
            }
        }
        dragOrTap(bubble, snap = true) {
            if (card.visibility == View.VISIBLE) {
                card.visibility = View.GONE
                box.post { markBounds(box) }
            } else {
                val st = runCatching { ScreenType.valueOf(Diag.currentType) }.getOrDefault(ScreenType.OTHER_OSM)
                val slot = Diag.currentSlot
                showCard(hints, if (slot > 0) "S$slot • " + Hints.screenLabel(st) else "Entre num slot do OSM")
            }
        }
        dragOrTap(end, snap = false) { onOverlayTap() }
        dragOrTap(toggle, snap = false) { onToggleTap() }
        dragOrTap(tactic, snap = false) { onTacticTap() }
        dragOrTap(notice, snap = false) { notice.visibility = View.GONE }
        box.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> markBounds(v) }
        try {
            wm.addView(box, lp)
            // o painel nunca passa da tela: se o conjunto (bola + cartão + aviso) ficar mais baixo que o espaço, sobe até caber (botões sempre visíveis)
            box.viewTreeObserver.addOnGlobalLayoutListener {
                val maxY = (resources.displayMetrics.heightPixels - box.height - (56 * dp).toInt()).coerceAtLeast(0)
                if (lp.y > maxY) { lp.y = maxY; try { wm.updateViewLayout(box, lp) } catch (ex: Exception) { } }
            }
            overlayBox = box
            overlay = end
            toggleView = toggle
            noticeView = notice
            cardView = card
            listView = list
            headView = head
            badgeView = badge
            ringView = ring
            statusView = statusPill
            updateToggle()
            startHints()
        } catch (e: Exception) {
            Diag.lastError = "Botão flutuante: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** No app vai para o OSM; no OSM (ou em outro lugar) volta para o app. A captura continua. */
    private fun onToggleTap() {
        try {
            if (AppVisible.resumed) {
                val i = packageManager.getLaunchIntentForPackage(OSM_PACKAGE) ?: return
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } else {
                // estando num slot do OSM (S1..S4), o app abre nesse slot (na aba em que você estava)
                val sl = Diag.currentSlot
                if (sl in 1..4) { Route.tab = -1; Route.slot = sl; Route.nonce++ }
                startActivity(
                    android.content.Intent(this, MainActivity::class.java).addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                )
            }
        } catch (e: Exception) {
            Diag.lastError = "Alternar app/OSM: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun onOverlayTap() {
        val tv = overlay ?: return
        val now = System.currentTimeMillis()
        if (armedAt == 0L || now - armedAt > 4000L) {
            armedAt = now
            tv.text = "Toque de novo para encerrar"
            return
        }
        armedAt = 0L
        removeOverlay()
        AppScope.scope.launch { Control.end(applicationContext) }
        Toast.makeText(this, "Captura encerrada. Processando em segundo plano…", Toast.LENGTH_SHORT).show()
    }

    private suspend fun takeShot(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    cont.resume(copy)
                }

                override fun onFailure(errorCode: Int) {
                    Diag.lastError = "Screenshot falhou (código $errorCode)"
                    cont.resume(null)
                }
            })
        } catch (e: Exception) {
            Diag.lastError = "Screenshot: " + (e.message ?: e.javaClass.simpleName)
            cont.resume(null)
        }
    }
}
