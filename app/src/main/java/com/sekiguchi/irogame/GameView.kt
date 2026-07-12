package com.sekiguchi.irogame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    private enum class Screen { MAIN, HIDE }
    private enum class Phase { IDLE, LINEUP, QUESTION, CELEBRATE }
    private enum class Mode { ANIMAL, COLOR, COUNT }

    // ---- 色データ（かめ：いろクイズ） ----
    private val colorList = listOf(
        "あか" to Color.parseColor("#E53935"),
        "あお" to Color.parseColor("#1E88E5"),
        "きいろ" to Color.parseColor("#FDD835"),
        "みどり" to Color.parseColor("#43A047"),
        "ぴんく" to Color.parseColor("#F06292"),
        "おれんじ" to Color.parseColor("#FB8C00"),
        "むらさき" to Color.parseColor("#8E24AA"),
        "みずいろ" to Color.parseColor("#4FC3F7")
    )

    private val animalNames = listOf("いぬ", "ねこ", "うさぎ", "ぞう", "ぱんだ", "ぶた", "かえる", "ひよこ")

    private val charNames = listOf("らっこ", "かめ", "ぺんぎん")
    private val charModes = listOf("どうぶつ", "いろ", "かず")
    private val armColors = intArrayOf(
        Color.parseColor("#8D6E63"),
        Color.parseColor("#66BB6A"),
        Color.parseColor("#37474F")
    )

    // ---- ゲーム状態 ----
    private var screen = Screen.MAIN
    private var phase = Phase.IDLE
    private var mode = Mode.COLOR
    private var selChar = -1
    private var targetIdx = 0
    private var countTarget = 1
    private var choices: List<Int> = emptyList()
    private var score = 0

    private var boardT0 = 0L
    private var celebT0 = 0L
    private var shakeBtn = -1
    private var shakeT0 = 0L
    private var lastFrame = 0L

    // ---- 整列アニメーション ----
    private val lineStartX = FloatArray(3)
    private val lineStartY = FloatArray(3)
    private var lineT0 = 0L
    private var pendingChar = -1
    private val curX = FloatArray(3)
    private val curY = FloatArray(3)

    // ---- かくれんぼ ----
    private var spotIdx = 0
    private var hideFound = false
    private var foundT0 = 0L

    // ---- 紙吹雪 ----
    private class P(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var rot: Float, var vr: Float, var size: Float, var c: Int
    )

    private val parts = ArrayList<P>()

    // ---- 描画用 ----
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    private var w = 0f
    private var h = 0f
    private val charX = FloatArray(3)
    private var charY = 0f
    private var charR = 0f
    private val btnRects = Array(3) { RectF() }
    private val countRects = Array(10) { RectF() }
    private var cornerBtnX = 0f
    private var cornerBtnY = 0f
    private var cornerBtnR = 0f

    private fun now() = SystemClock.uptimeMillis()

    private fun tg(): ToneGenerator? = try {
        if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        tone
    } catch (e: Exception) {
        null
    }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(nw, nh, ow, oh)
        w = nw.toFloat()
        h = nh.toFloat()
        charX[0] = w * 0.20f
        charX[1] = w * 0.50f
        charX[2] = w * 0.80f
        charY = h * 0.40f
        charR = w * 0.115f
        val bw = w * 0.78f
        val bh = h * 0.08f
        val ys = floatArrayOf(0.615f, 0.72f, 0.825f)
        for (i in 0..2) {
            btnRects[i].set(w / 2 - bw / 2, h * ys[i], w / 2 + bw / 2, h * ys[i] + bh)
        }
        val cbw = w * 0.164f
        val cbh = h * 0.072f
        for (i in 0..9) {
            val col = i % 5
            val row = i / 5
            val cx = w * (0.12f + 0.19f * col)
            val ty = h * (0.625f + 0.115f * row)
            countRects[i].set(cx - cbw / 2, ty, cx + cbw / 2, ty + cbh)
        }
        cornerBtnX = w * 0.115f
        cornerBtnY = h * 0.925f
        cornerBtnR = w * 0.085f
    }

    // ============ タッチ ============
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) return true
        val x = e.x
        val y = e.y

        if (screen == Screen.HIDE) {
            if (hitCorner(x, y)) {
                screen = Screen.MAIN
                phase = Phase.IDLE
                selChar = -1
                parts.clear()
                tg()?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                return true
            }
            if (!hideFound) {
                val p = peekPos(spotIdx)
                if (abs(x - p[0]) < w * 0.12f && abs(y - p[1]) < w * 0.14f) {
                    hideFound = true
                    foundT0 = now()
                    score++
                    spawnConfetti(p[0], p[1])
                    playHappy()
                    uiHandler.postDelayed({
                        hideFound = false
                        var ns = Random.nextInt(5)
                        while (ns == spotIdx) ns = Random.nextInt(5)
                        spotIdx = ns
                        parts.clear()
                    }, 2800)
                }
            }
            return true
        }

        // MAIN画面
        if (phase == Phase.CELEBRATE || phase == Phase.LINEUP) return true
        if (hitCorner(x, y)) {
            screen = Screen.HIDE
            hideFound = false
            spotIdx = Random.nextInt(5)
            parts.clear()
            tg()?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            return true
        }
        val ci = hitChar(x, y)
        if (ci >= 0) {
            if (phase == Phase.IDLE) {
                for (i in 0..2) {
                    lineStartX[i] = curX[i]
                    lineStartY[i] = curY[i]
                }
                pendingChar = ci
                lineT0 = now()
                phase = Phase.LINEUP
                tg()?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            } else {
                startQuestion(ci)
            }
            return true
        }
        if (phase == Phase.QUESTION) {
            if (mode == Mode.COUNT) {
                for (i in 0..9) {
                    if (countRects[i].contains(x, y)) {
                        answer(i)
                        return true
                    }
                }
            } else {
                for (i in 0..2) {
                    if (btnRects[i].contains(x, y)) {
                        answer(i)
                        return true
                    }
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hitCorner(x: Float, y: Float): Boolean {
        val dx = x - cornerBtnX
        val dy = y - cornerBtnY
        return dx * dx + dy * dy < cornerBtnR * 1.3f * cornerBtnR * 1.3f
    }

    private fun hitChar(x: Float, y: Float): Int {
        for (i in 0..2) {
            val dx = x - curX[i]
            val dy = y - curY[i]
            if (dx * dx + dy * dy < charR * 1.6f * charR * 1.6f) return i
        }
        return -1
    }

    private fun startQuestion(ci: Int) {
        selChar = ci
        mode = when (ci) {
            0 -> Mode.ANIMAL
            1 -> Mode.COLOR
            else -> Mode.COUNT
        }
        when (mode) {
            Mode.ANIMAL -> {
                targetIdx = Random.nextInt(animalNames.size)
                val others = animalNames.indices.filter { it != targetIdx }.shuffled().take(2)
                choices = (others + targetIdx).shuffled()
            }
            Mode.COLOR -> {
                targetIdx = Random.nextInt(colorList.size)
                val others = colorList.indices.filter { it != targetIdx }.shuffled().take(2)
                choices = (others + targetIdx).shuffled()
            }
            Mode.COUNT -> {
                countTarget = Random.nextInt(1, 11)
            }
        }
        boardT0 = now()
        shakeBtn = -1
        phase = Phase.QUESTION
        tg()?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    private fun playHappy() {
        val t = tg()
        t?.startTone(ToneGenerator.TONE_DTMF_1, 130)
        uiHandler.postDelayed({ t?.startTone(ToneGenerator.TONE_DTMF_5, 130) }, 160)
        uiHandler.postDelayed({ t?.startTone(ToneGenerator.TONE_DTMF_9, 160) }, 320)
        uiHandler.postDelayed({ t?.startTone(ToneGenerator.TONE_DTMF_D, 320) }, 500)
    }

    private fun answer(i: Int) {
        val correct = when (mode) {
            Mode.COUNT -> i + 1 == countTarget
            else -> choices[i] == targetIdx
        }
        if (correct) {
            phase = Phase.CELEBRATE
            celebT0 = now()
            score++
            spawnConfetti(w / 2, h * 0.40f)
            playHappy()
            uiHandler.postDelayed({
                if (screen == Screen.MAIN) {
                    phase = Phase.IDLE
                    selChar = -1
                    parts.clear()
                }
            }, 3000)
        } else {
            shakeBtn = i
            shakeT0 = now()
            tg()?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
        }
    }

    private fun spawnConfetti(sx: Float, sy: Float) {
        parts.clear()
        val palette = colorList.map { it.second }
        repeat(110) {
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val sp = w * 0.3f + Random.nextFloat() * w * 1.1f
            parts.add(
                P(
                    x = sx, y = sy,
                    vx = cos(a) * sp, vy = sin(a) * sp - w * 0.55f,
                    rot = Random.nextFloat() * 360f,
                    vr = (Random.nextFloat() - 0.5f) * 720f,
                    size = w * 0.012f + Random.nextFloat() * w * 0.02f,
                    c = palette[Random.nextInt(palette.size)]
                )
            )
        }
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    // ============ 描画 ============
    override fun onDraw(cv: Canvas) {
        val t = now()
        val dt = if (lastFrame == 0L) 0f else min(0.05f, (t - lastFrame) / 1000f)
        lastFrame = t

        if (screen == Screen.HIDE) {
            drawHideScreen(cv, t, dt)
            postInvalidateOnAnimation()
            return
        }

        drawBeach(cv, t)
        drawScore(cv)
        updatePositions(t)

        val swimming = phase == Phase.IDLE || phase == Phase.LINEUP
        for (i in 0..2) {
            var yoff = 0f
            var tilt = 0f
            if (swimming) {
                tilt = sin(t / 900f + i * 1.3f) * 8f
            } else {
                yoff = sin(t / 400f + i * 2f) * charR * 0.05f
                if (phase == Phase.CELEBRATE && i == selChar) {
                    yoff = -abs(sin((t - celebT0) / 170f)) * charR * 0.55f
                }
            }
            if (selChar == i && (phase == Phase.QUESTION || phase == Phase.CELEBRATE)) {
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#66FFF176")
                cv.drawCircle(curX[i], curY[i] + yoff, charR * 1.7f, paint)
            }
            cv.save()
            cv.rotate(tilt, curX[i], curY[i] + yoff)
            drawChar(cv, i, curX[i], curY[i] + yoff, charR)
            cv.restore()
        }

        // 海のとうめいなみず（泳いでいるとき）
        if (phase == Phase.IDLE) {
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#494FC3F7")
            cv.drawRect(0f, h * 0.21f, w, h * 0.455f, paint)
            // あわ
            paint.color = Color.parseColor("#88FFFFFF")
            for (k in 0..5) {
                val bx = w * (0.08f + 0.17f * k)
                val by = h * 0.44f - ((t * 0.028f + k * 251f) % (h * 0.20f))
                cv.drawCircle(bx, by, w * (0.008f + 0.004f * (k % 3)), paint)
            }
        }

        // なまえラベル
        for (i in 0..2) {
            tp.textSize = w * 0.04f
            tp.color = if (swimming) Color.WHITE else Color.parseColor("#6D4C41")
            cv.drawText(charNames[i], curX[i], curY[i] + charR * 1.65f, tp)
            if (!swimming) {
                tp.textSize = w * 0.03f
                tp.color = Color.parseColor("#A1887F")
                cv.drawText(charModes[i], curX[i], curY[i] + charR * 2.0f, tp)
            }
        }

        if ((phase == Phase.QUESTION || phase == Phase.CELEBRATE) && selChar >= 0) drawBoard(cv, t)

        tp.textSize = w * 0.05f
        tp.color = Color.parseColor("#6D4C41")
        val msg = when (phase) {
            Phase.IDLE -> "すきな どうぶつを タッチしてね"
            Phase.LINEUP -> ""
            Phase.QUESTION -> when (mode) {
                Mode.ANIMAL -> "これは なんの どうぶつかな？"
                Mode.COLOR -> "この いたは なにいろかな？"
                Mode.COUNT -> "ぺんぎんは なんわ いるかな？"
            }
            Phase.CELEBRATE -> ""
        }
        if (msg.isNotEmpty()) cv.drawText(msg, w / 2, h * 0.585f, tp)

        if (phase == Phase.QUESTION) {
            if (mode == Mode.COUNT) drawCountButtons(cv, t) else drawChoiceButtons(cv, t)
        }
        if (phase == Phase.IDLE || phase == Phase.QUESTION) drawRabbitButton(cv, t)
        if (phase == Phase.CELEBRATE) drawCelebrate(cv, t, dt, "せいかい！", "すごーい！", w / 2, h * 0.44f)

        postInvalidateOnAnimation()
    }

    private fun updatePositions(t: Long) {
        val baseX = floatArrayOf(w * 0.22f, w * 0.50f, w * 0.78f)
        when (phase) {
            Phase.IDLE -> {
                for (i in 0..2) {
                    curX[i] = baseX[i] + sin(t * 0.00035f + i * 2.1f) * w * 0.10f
                    curY[i] = h * 0.295f + sin(t * 0.0007f + i * 1.7f) * h * 0.035f +
                        max(0f, sin(t * 0.00018f + i * 2.9f)) * h * 0.05f
                }
            }
            Phase.LINEUP -> {
                val p = ((t - lineT0) / 550f).coerceIn(0f, 1f)
                val e = p * p * (3f - 2f * p)
                for (i in 0..2) {
                    curX[i] = lineStartX[i] + (charX[i] - lineStartX[i]) * e
                    curY[i] = lineStartY[i] + (charY - lineStartY[i]) * e
                }
                if (p >= 1f && pendingChar >= 0) {
                    val pc = pendingChar
                    pendingChar = -1
                    startQuestion(pc)
                }
            }
            else -> {
                for (i in 0..2) {
                    curX[i] = charX[i]
                    curY[i] = charY
                }
            }
        }
    }

    private fun drawBeach(cv: Canvas, t: Long) {
        paint.style = Paint.Style.FILL
        // そら
        paint.color = Color.parseColor("#B3E5FC")
        cv.drawRect(0f, 0f, w, h * 0.22f, paint)
        // たいよう
        paint.color = Color.parseColor("#FFF176")
        cv.drawCircle(w * 0.87f, h * 0.075f, w * 0.055f, paint)
        // くも
        paint.color = Color.WHITE
        val cx1 = w * (0.22f + 0.02f * sin(t / 4000f))
        cv.drawOval(cx1 - w * 0.1f, h * 0.05f, cx1 + w * 0.1f, h * 0.085f, paint)
        cv.drawOval(cx1 - w * 0.05f, h * 0.035f, cx1 + w * 0.06f, h * 0.07f, paint)
        // うみ
        paint.color = Color.parseColor("#4FC3F7")
        cv.drawRect(0f, h * 0.16f, w, h * 0.47f, paint)
        // なみ
        paint.color = Color.parseColor("#81D4FA")
        for (k in 0..4) {
            val wx = w * 0.2f * k + (t * 0.01f) % (w * 0.2f)
            cv.drawArc(wx - w * 0.1f, h * 0.165f, wx + w * 0.1f, h * 0.195f, 180f, 180f, false, paint)
        }
        // すなはま
        paint.color = Color.parseColor("#FFE0B2")
        cv.drawRect(0f, h * 0.465f, w, h, paint)
        cv.drawOval(-w * 0.2f, h * 0.435f, w * 1.2f, h * 0.52f, paint)
        // かいがら・ヒトデ
        paint.color = Color.parseColor("#FFAB91")
        cv.drawCircle(w * 0.12f, h * 0.55f, w * 0.014f, paint)
        paint.color = Color.parseColor("#FFCC80")
        cv.drawCircle(w * 0.88f, h * 0.57f, w * 0.014f, paint)
    }

    private fun drawScore(cv: Canvas) {
        tp.textSize = w * 0.06f
        tp.textAlign = Paint.Align.LEFT
        tp.color = Color.parseColor("#FBC02D")
        cv.drawText("★", w * 0.05f, h * 0.06f, tp)
        tp.color = Color.parseColor("#6D4C41")
        cv.drawText(" $score", w * 0.12f, h * 0.06f, tp)
        tp.textAlign = Paint.Align.CENTER
    }

    private fun drawBoard(cv: Canvas, t: Long) {
        val bw = when (mode) {
            Mode.COLOR -> w * 0.38f
            Mode.ANIMAL -> w * 0.5f
            Mode.COUNT -> w * 0.62f
        }
        val bh = when (mode) {
            Mode.COLOR -> bw * 0.7f
            Mode.ANIMAL -> bw * 0.78f
            Mode.COUNT -> bw * 0.62f
        }
        var bx = charX[selChar]
        bx = bx.coerceIn(bw / 2 + w * 0.02f, w - bw / 2 - w * 0.02f)
        var yoff = 0f
        if (phase == Phase.CELEBRATE) yoff = -abs(sin((t - celebT0) / 170f)) * charR * 0.55f
        val startY = charY + charR * 0.4f + yoff
        val endY = charY - charR * 1.15f - bh / 2 + yoff
        val p = ((t - boardT0) / 350f).coerceIn(0f, 1f)
        val by = startY + (endY - startY) * easeOutBack(p)
        val rot = sin(t / 500f) * 2f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = charR * 0.22f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = armColors[selChar]
        cv.drawLine(charX[selChar] - charR * 0.5f, charY + yoff, bx - bw * 0.32f, by + bh * 0.48f, paint)
        cv.drawLine(charX[selChar] + charR * 0.5f, charY + yoff, bx + bw * 0.32f, by + bh * 0.48f, paint)
        paint.style = Paint.Style.FILL

        cv.save()
        cv.rotate(rot, bx, by)
        val r = RectF(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
        paint.color = Color.parseColor("#33000000")
        r.offset(0f, bh * 0.04f)
        cv.drawRoundRect(r, bw * 0.08f, bw * 0.08f, paint)
        r.offset(0f, -bh * 0.04f)
        paint.color = when (mode) {
            Mode.COLOR -> colorList[targetIdx].second
            Mode.ANIMAL -> Color.parseColor("#FFF3E0")
            Mode.COUNT -> Color.parseColor("#E3F2FD")
        }
        cv.drawRoundRect(r, bw * 0.08f, bw * 0.08f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = bw * 0.03f
        paint.color = Color.WHITE
        cv.drawRoundRect(r, bw * 0.08f, bw * 0.08f, paint)
        paint.style = Paint.Style.FILL

        when (mode) {
            Mode.ANIMAL -> drawAnimal(cv, targetIdx, bx, by, bh * 0.32f)
            Mode.COUNT -> drawPenguinGroup(cv, bx, by, bw, bh)
            Mode.COLOR -> {}
        }
        cv.restore()
    }

    private fun drawPenguinGroup(cv: Canvas, bx: Float, by: Float, bw: Float, bh: Float) {
        val n = countTarget
        val rows = if (n <= 5) 1 else 2
        val pr = min(bw / 13.5f, bh / (rows * 3.4f))
        val spacing = pr * 2.5f
        for (row in 0 until rows) {
            val k = if (row == 0) min(n, 5) else n - 5
            val rowY = by + (row - (rows - 1) / 2f) * pr * 3.1f
            val startX = bx - (k - 1) * spacing / 2f
            for (c in 0 until k) {
                drawPenguin(cv, startX + c * spacing, rowY, pr)
            }
        }
    }

    private fun drawChoiceButtons(cv: Canvas, t: Long) {
        for (i in 0..2) {
            var dx = 0f
            if (i == shakeBtn) {
                val el = t - shakeT0
                if (el < 400) dx = sin(el / 25f) * w * 0.03f * (1f - el / 400f)
            }
            val r = RectF(btnRects[i])
            r.offset(dx, 0f)
            drawBtnBase(cv, r)
            tp.textSize = r.height() * 0.5f
            tp.color = Color.parseColor("#4E342E")
            val ty = r.centerY() - (tp.descent() + tp.ascent()) / 2
            val label = if (mode == Mode.ANIMAL) animalNames[choices[i]] else colorList[choices[i]].first
            cv.drawText(label, r.centerX(), ty, tp)
        }
    }

    private fun drawCountButtons(cv: Canvas, t: Long) {
        for (i in 0..9) {
            var dx = 0f
            if (i == shakeBtn) {
                val el = t - shakeT0
                if (el < 400) dx = sin(el / 25f) * w * 0.02f * (1f - el / 400f)
            }
            val r = RectF(countRects[i])
            r.offset(dx, 0f)
            drawBtnBase(cv, r)
            tp.textSize = r.height() * 0.55f
            tp.color = Color.parseColor("#4E342E")
            val ty = r.centerY() - (tp.descent() + tp.ascent()) / 2
            cv.drawText("${i + 1}", r.centerX(), ty, tp)
        }
    }

    private fun drawBtnBase(cv: Canvas, r: RectF) {
        val rad = r.height() * 0.35f
        paint.color = Color.parseColor("#22000000")
        r.offset(0f, r.height() * 0.06f)
        cv.drawRoundRect(r, rad, rad, paint)
        r.offset(0f, -r.height() * 0.06f)
        paint.color = Color.WHITE
        cv.drawRoundRect(r, rad, rad, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.parseColor("#BCAAA4")
        cv.drawRoundRect(r, rad, rad, paint)
        paint.style = Paint.Style.FILL
    }

    // ---- かくれんぼボタン（目かくしうさぎ） ----
    private fun drawRabbitButton(cv: Canvas, t: Long) {
        val cx = cornerBtnX
        val cy = cornerBtnY
        val r = cornerBtnR
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#33000000")
        cv.drawCircle(cx, cy + r * 0.08f, r, paint)
        paint.color = Color.WHITE
        cv.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.08f
        paint.color = Color.parseColor("#F48FB1")
        cv.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        // みみ
        paint.color = Color.WHITE
        cv.drawOval(cx - r * 0.45f, cy - r * 0.95f, cx - r * 0.1f, cy - r * 0.15f, paint)
        cv.drawOval(cx + r * 0.1f, cy - r * 0.95f, cx + r * 0.45f, cy - r * 0.15f, paint)
        paint.color = Color.parseColor("#F8BBD0")
        cv.drawOval(cx - r * 0.37f, cy - r * 0.82f, cx - r * 0.18f, cy - r * 0.28f, paint)
        cv.drawOval(cx + r * 0.18f, cy - r * 0.82f, cx + r * 0.37f, cy - r * 0.28f, paint)
        // かお
        paint.color = Color.WHITE
        cv.drawCircle(cx, cy + r * 0.12f, r * 0.55f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.05f
        paint.color = Color.parseColor("#BCAAA4")
        cv.drawCircle(cx, cy + r * 0.12f, r * 0.55f, paint)
        paint.style = Paint.Style.FILL
        // くち
        paint.color = Color.parseColor("#F06292")
        cv.drawCircle(cx, cy + r * 0.35f, r * 0.06f, paint)
        // 目をかくす手（ゆらゆら）
        val wob = sin(t / 350f) * r * 0.03f
        paint.color = Color.WHITE
        cv.drawCircle(cx - r * 0.24f, cy + r * 0.02f + wob, r * 0.22f, paint)
        cv.drawCircle(cx + r * 0.24f, cy + r * 0.02f - wob, r * 0.22f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.05f
        paint.color = Color.parseColor("#BCAAA4")
        cv.drawCircle(cx - r * 0.24f, cy + r * 0.02f + wob, r * 0.22f, paint)
        cv.drawCircle(cx + r * 0.24f, cy + r * 0.02f - wob, r * 0.22f, paint)
        paint.style = Paint.Style.FILL
        tp.textSize = w * 0.028f
        tp.color = Color.parseColor("#6D4C41")
        cv.drawText("かくれんぼ", cx, cy + r * 1.45f, tp)
    }

    // ---- もどるボタン（ぺんぎん） ----
    private fun drawPenguinButton(cv: Canvas) {
        val cx = cornerBtnX
        val cy = cornerBtnY
        val r = cornerBtnR
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#33000000")
        cv.drawCircle(cx, cy + r * 0.08f, r, paint)
        paint.color = Color.WHITE
        cv.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.08f
        paint.color = Color.parseColor("#4FC3F7")
        cv.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        drawPenguin(cv, cx, cy, r * 0.5f)
        tp.textSize = w * 0.028f
        tp.color = Color.parseColor("#6D4C41")
        cv.drawText("もどる", cx, cy + r * 1.45f, tp)
    }

    // ============ かくれんぼ画面 ============
    private fun peekPos(i: Int): FloatArray = when (i) {
        0 -> floatArrayOf(w * 0.16f, h * 0.43f, 0f)   // しげみ1のうえ（みみ）
        1 -> floatArrayOf(w * 0.60f, h * 0.40f, 0f)   // しげみ2のうえ（みみ）
        2 -> floatArrayOf(w * 0.935f, h * 0.475f, 1f) // きのよこ（て）
        3 -> floatArrayOf(w * 0.36f, h * 0.72f, 2f)   // ベンチのした（あし）
        else -> floatArrayOf(w * 0.705f, h * 0.575f, 0f) // すべりだいのうえ（みみ）
    }

    private fun drawHideScreen(cv: Canvas, t: Long, dt: Float) {
        paint.style = Paint.Style.FILL
        // そら
        paint.color = Color.parseColor("#B3E5FC")
        cv.drawRect(0f, 0f, w, h * 0.32f, paint)
        paint.color = Color.parseColor("#FFF176")
        cv.drawCircle(w * 0.12f, h * 0.07f, w * 0.05f, paint)
        // もり（おく）
        for (k in 0..5) {
            val tx = w * (0.02f + 0.19f * k)
            paint.color = Color.parseColor("#5D4037")
            cv.drawRect(tx - w * 0.015f, h * 0.24f, tx + w * 0.015f, h * 0.33f, paint)
            paint.color = if (k % 2 == 0) Color.parseColor("#2E7D32") else Color.parseColor("#388E3C")
            cv.drawCircle(tx, h * 0.21f, w * 0.10f, paint)
        }
        // くさはら
        paint.color = Color.parseColor("#9CCC65")
        cv.drawRect(0f, h * 0.31f, w, h * 0.58f, paint)
        paint.color = Color.parseColor("#AED581")
        cv.drawRect(0f, h * 0.56f, w, h, paint)

        // まえのき（みぎ）
        paint.color = Color.parseColor("#6D4C41")
        cv.drawRect(w * 0.835f, h * 0.30f, w * 0.895f, h * 0.56f, paint)
        paint.color = Color.parseColor("#43A047")
        cv.drawCircle(w * 0.865f, h * 0.255f, w * 0.135f, paint)

        // しげみ1（ひだり）
        paint.color = Color.parseColor("#66BB6A")
        cv.drawCircle(w * 0.09f, h * 0.475f, w * 0.085f, paint)
        cv.drawCircle(w * 0.16f, h * 0.465f, w * 0.095f, paint)
        cv.drawCircle(w * 0.235f, h * 0.478f, w * 0.08f, paint)
        // しげみ2（まんなか）
        paint.color = Color.parseColor("#81C784")
        cv.drawCircle(w * 0.545f, h * 0.44f, w * 0.075f, paint)
        cv.drawCircle(w * 0.615f, h * 0.435f, w * 0.085f, paint)
        cv.drawCircle(w * 0.68f, h * 0.445f, w * 0.07f, paint)

        // ベンチ
        paint.color = Color.parseColor("#8D6E63")
        cv.drawRect(w * 0.24f, h * 0.625f, w * 0.48f, h * 0.640f, paint) // せもたれ
        cv.drawRect(w * 0.24f, h * 0.665f, w * 0.48f, h * 0.685f, paint) // ざせき
        cv.drawRect(w * 0.26f, h * 0.685f, w * 0.29f, h * 0.735f, paint)
        cv.drawRect(w * 0.43f, h * 0.685f, w * 0.46f, h * 0.735f, paint)

        // すべりだい
        paint.color = Color.parseColor("#90A4AE")
        cv.drawRect(w * 0.665f, h * 0.585f, w * 0.745f, h * 0.605f, paint) // ステージ
        cv.drawRect(w * 0.675f, h * 0.605f, w * 0.695f, h * 0.76f, paint) // はしご
        cv.drawRect(w * 0.72f, h * 0.605f, w * 0.735f, h * 0.76f, paint)
        paint.color = Color.parseColor("#EF5350")
        val slide = Path()
        slide.moveTo(w * 0.745f, h * 0.59f)
        slide.lineTo(w * 0.90f, h * 0.76f)
        slide.lineTo(w * 0.855f, h * 0.775f)
        slide.lineTo(w * 0.735f, h * 0.615f)
        slide.close()
        cv.drawPath(slide, paint)

        drawScore(cv)

        // うさぎ（かくれている or みつかった）
        val p = peekPos(spotIdx)
        if (!hideFound) {
            drawPeek(cv, p[0], p[1], p[2].toInt(), t)
        } else {
            val jump = -abs(sin((t - foundT0) / 170f)) * w * 0.06f
            drawRabbitFull(cv, p[0], p[1] - w * 0.05f + jump, w * 0.07f)
        }

        tp.textSize = w * 0.05f
        tp.color = Color.parseColor("#33691E")
        if (!hideFound) cv.drawText("うさぎさんは どこかな？", w / 2, h * 0.10f, tp)

        drawPenguinButton(cv)

        if (hideFound) {
            drawCelebrate(cv, t, dt, "みつけた！", "やったー！", w / 2, h * 0.40f)
        }
    }

    private fun drawPeek(cv: Canvas, x: Float, y: Float, kind: Int, t: Long) {
        val wig = sin(t / 320f) * 3f
        cv.save()
        cv.rotate(wig, x, y)
        paint.style = Paint.Style.FILL
        when (kind) {
            0 -> { // みみだけ
                paint.color = Color.WHITE
                cv.drawOval(x - w * 0.075f, y - w * 0.19f, x - w * 0.015f, y + w * 0.015f, paint)
                cv.drawOval(x + w * 0.015f, y - w * 0.19f, x + w * 0.075f, y + w * 0.015f, paint)
                paint.color = Color.parseColor("#F8BBD0")
                cv.drawOval(x - w * 0.06f, y - w * 0.16f, x - w * 0.03f, y - w * 0.02f, paint)
                cv.drawOval(x + w * 0.03f, y - w * 0.16f, x + w * 0.06f, y - w * 0.02f, paint)
            }
            1 -> { // てだけ
                paint.color = Color.WHITE
                cv.drawCircle(x, y, w * 0.035f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.006f
                paint.color = Color.parseColor("#B0BEC5")
                cv.drawCircle(x, y, w * 0.035f, paint)
                cv.drawLine(x - w * 0.01f, y - w * 0.03f, x - w * 0.01f, y - w * 0.012f, paint)
                cv.drawLine(x + w * 0.01f, y - w * 0.03f, x + w * 0.01f, y - w * 0.012f, paint)
                paint.style = Paint.Style.FILL
            }
            2 -> { // あしだけ
                paint.color = Color.WHITE
                cv.drawOval(x - w * 0.085f, y - w * 0.02f, x - w * 0.005f, y + w * 0.025f, paint)
                cv.drawOval(x + w * 0.005f, y - w * 0.02f, x + w * 0.085f, y + w * 0.025f, paint)
                paint.color = Color.parseColor("#F8BBD0")
                cv.drawCircle(x - w * 0.06f, y, w * 0.012f, paint)
                cv.drawCircle(x + w * 0.03f, y, w * 0.012f, paint)
            }
        }
        cv.restore()
    }

    private fun drawRabbitFull(cv: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        // みみ
        paint.color = Color.WHITE
        cv.drawOval(cx - r * 0.6f, cy - r * 1.9f, cx - r * 0.1f, cy - r * 0.4f, paint)
        cv.drawOval(cx + r * 0.1f, cy - r * 1.9f, cx + r * 0.6f, cy - r * 0.4f, paint)
        paint.color = Color.parseColor("#F8BBD0")
        cv.drawOval(cx - r * 0.48f, cy - r * 1.7f, cx - r * 0.22f, cy - r * 0.6f, paint)
        cv.drawOval(cx + r * 0.22f, cy - r * 1.7f, cx + r * 0.48f, cy - r * 0.6f, paint)
        // からだ
        paint.color = Color.WHITE
        cv.drawOval(cx - r * 0.8f, cy + r * 0.4f, cx + r * 0.8f, cy + r * 2.0f, paint)
        // あたま
        cv.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.05f
        paint.color = Color.parseColor("#B0BEC5")
        cv.drawCircle(cx, cy, r, paint)
        cv.drawOval(cx - r * 0.8f, cy + r * 0.4f, cx + r * 0.8f, cy + r * 2.0f, paint)
        paint.style = Paint.Style.FILL
        // め・はな・ほっぺ
        paint.color = Color.BLACK
        cv.drawCircle(cx - r * 0.35f, cy - r * 0.1f, r * 0.1f, paint)
        cv.drawCircle(cx + r * 0.35f, cy - r * 0.1f, r * 0.1f, paint)
        paint.color = Color.parseColor("#F06292")
        cv.drawCircle(cx, cy + r * 0.2f, r * 0.11f, paint)
        paint.color = Color.parseColor("#F8BBD0")
        cv.drawCircle(cx - r * 0.6f, cy + r * 0.25f, r * 0.14f, paint)
        cv.drawCircle(cx + r * 0.6f, cy + r * 0.25f, r * 0.14f, paint)
    }

    private fun drawCelebrate(cv: Canvas, t: Long, dt: Float, mainText: String, subText: String, cx: Float, cy: Float) {
        val el = t - celebFoundTime()

        cv.save()
        cv.rotate(el / 22f, cx, cy)
        val ray = Path()
        for (k in 0 until 12) {
            paint.color = Color.HSVToColor(60, floatArrayOf((k * 30f + el / 10f) % 360f, 0.8f, 1f))
            ray.reset()
            ray.moveTo(cx, cy)
            val a1 = k * 30f * PI.toFloat() / 180f
            val a2 = (k * 30f + 14f) * PI.toFloat() / 180f
            val rr = w * 0.9f
            ray.lineTo(cx + cos(a1) * rr, cy + sin(a1) * rr)
            ray.lineTo(cx + cos(a2) * rr, cy + sin(a2) * rr)
            ray.close()
            cv.drawPath(ray, paint)
        }
        cv.restore()

        val it2 = parts.iterator()
        while (it2.hasNext()) {
            val p = it2.next()
            p.vy += h * 1.4f * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.vr * dt
            if (p.y > h + 50) {
                it2.remove()
                continue
            }
            paint.color = p.c
            cv.save()
            cv.rotate(p.rot, p.x, p.y)
            cv.drawRect(p.x - p.size, p.y - p.size * 0.6f, p.x + p.size, p.y + p.size * 0.6f, paint)
            cv.restore()
        }

        val ap = (el / 400f).coerceIn(0f, 1f)
        val scale = easeOutBack(ap) * (1f + 0.06f * sin(el / 120f))
        cv.save()
        cv.scale(scale, scale, cx, cy)
        tp.textSize = w * 0.17f
        tp.style = Paint.Style.STROKE
        tp.strokeWidth = w * 0.02f
        tp.color = Color.WHITE
        cv.drawText(mainText, cx, cy, tp)
        tp.style = Paint.Style.FILL
        tp.color = Color.HSVToColor(floatArrayOf((el / 8f) % 360f, 0.85f, 0.95f))
        cv.drawText(mainText, cx, cy, tp)
        tp.textSize = w * 0.07f
        tp.color = Color.parseColor("#FF7043")
        cv.drawText(subText, cx, cy + w * 0.13f, tp)
        cv.restore()
    }

    private fun celebFoundTime(): Long =
        if (screen == Screen.HIDE) foundT0 else celebT0

    // ============ キャラクター ============
    private fun drawChar(cv: Canvas, i: Int, cx: Float, cy: Float, r: Float) {
        when (i) {
            0 -> drawOtter(cv, cx, cy, r)
            1 -> drawTurtle(cv, cx, cy, r)
            2 -> drawPenguin(cv, cx, cy, r)
        }
    }

    private fun drawOtter(cv: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#8D6E63")
        cv.drawOval(cx - r * 0.85f, cy - r * 0.2f, cx + r * 0.85f, cy + r * 1.15f, paint)
        paint.color = Color.parseColor("#D7CCC8")
        cv.drawOval(cx - r * 0.5f, cy + r * 0.05f, cx + r * 0.5f, cy + r * 1.0f, paint)
        paint.color = Color.parseColor("#8D6E63")
        cv.drawCircle(cx - r * 0.5f, cy - r * 1.05f, r * 0.2f, paint)
        cv.drawCircle(cx + r * 0.5f, cy - r * 1.05f, r * 0.2f, paint)
        cv.drawCircle(cx, cy - r * 0.5f, r * 0.72f, paint)
        paint.color = Color.parseColor("#EFEBE9")
        cv.drawOval(cx - r * 0.45f, cy - r * 0.55f, cx + r * 0.45f, cy + r * 0.1f, paint)
        paint.color = Color.BLACK
        cv.drawCircle(cx - r * 0.28f, cy - r * 0.62f, r * 0.07f, paint)
        cv.drawCircle(cx + r * 0.28f, cy - r * 0.62f, r * 0.07f, paint)
        cv.drawCircle(cx, cy - r * 0.35f, r * 0.09f, paint)
        paint.strokeWidth = r * 0.04f
        cv.drawLine(cx - r * 0.15f, cy - r * 0.3f, cx - r * 0.55f, cy - r * 0.35f, paint)
        cv.drawLine(cx - r * 0.15f, cy - r * 0.24f, cx - r * 0.55f, cy - r * 0.18f, paint)
        cv.drawLine(cx + r * 0.15f, cy - r * 0.3f, cx + r * 0.55f, cy - r * 0.35f, paint)
        cv.drawLine(cx + r * 0.15f, cy - r * 0.24f, cx + r * 0.55f, cy - r * 0.18f, paint)
        paint.color = Color.parseColor("#6D4C41")
        cv.drawOval(cx - r * 0.6f, cy + r * 0.95f, cx - r * 0.15f, cy + r * 1.2f, paint)
        cv.drawOval(cx + r * 0.15f, cy + r * 0.95f, cx + r * 0.6f, cy + r * 1.2f, paint)
    }

    private fun drawTurtle(cv: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#9CCC65")
        cv.drawOval(cx - r * 0.85f, cy + r * 0.7f, cx - r * 0.35f, cy + r * 1.15f, paint)
        cv.drawOval(cx + r * 0.35f, cy + r * 0.7f, cx + r * 0.85f, cy + r * 1.15f, paint)
        cv.drawCircle(cx, cy - r * 0.65f, r * 0.5f, paint)
        paint.color = Color.BLACK
        cv.drawCircle(cx - r * 0.18f, cy - r * 0.72f, r * 0.07f, paint)
        cv.drawCircle(cx + r * 0.18f, cy - r * 0.72f, r * 0.07f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.05f
        cv.drawArc(cx - r * 0.18f, cy - r * 0.65f, cx + r * 0.18f, cy - r * 0.4f, 20f, 140f, false, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#66BB6A")
        cv.drawOval(cx - r * 0.9f, cy - r * 0.35f, cx + r * 0.9f, cy + r * 1.05f, paint)
        paint.color = Color.parseColor("#43A047")
        cv.drawCircle(cx, cy + r * 0.35f, r * 0.3f, paint)
        cv.drawCircle(cx - r * 0.45f, cy + r * 0.3f, r * 0.18f, paint)
        cv.drawCircle(cx + r * 0.45f, cy + r * 0.3f, r * 0.18f, paint)
        cv.drawCircle(cx, cy + r * 0.75f, r * 0.18f, paint)
    }

    private fun drawPenguin(cv: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#FB8C00")
        cv.drawOval(cx - r * 0.55f, cy + r * 0.95f, cx - r * 0.1f, cy + r * 1.2f, paint)
        cv.drawOval(cx + r * 0.1f, cy + r * 0.95f, cx + r * 0.55f, cy + r * 1.2f, paint)
        paint.color = Color.parseColor("#37474F")
        cv.drawOval(cx - r * 0.8f, cy - r * 1.15f, cx + r * 0.8f, cy + r * 1.1f, paint)
        paint.color = Color.WHITE
        cv.drawOval(cx - r * 0.5f, cy - r * 0.45f, cx + r * 0.5f, cy + r * 0.95f, paint)
        cv.drawCircle(cx - r * 0.25f, cy - r * 0.6f, r * 0.28f, paint)
        cv.drawCircle(cx + r * 0.25f, cy - r * 0.6f, r * 0.28f, paint)
        paint.color = Color.BLACK
        cv.drawCircle(cx - r * 0.22f, cy - r * 0.6f, r * 0.08f, paint)
        cv.drawCircle(cx + r * 0.22f, cy - r * 0.6f, r * 0.08f, paint)
        paint.color = Color.parseColor("#FB8C00")
        val beak = Path()
        beak.moveTo(cx - r * 0.15f, cy - r * 0.42f)
        beak.lineTo(cx + r * 0.15f, cy - r * 0.42f)
        beak.lineTo(cx, cy - r * 0.2f)
        beak.close()
        cv.drawPath(beak, paint)
    }

    // ============ どうぶつイラスト（板の上） ============
    private fun drawAnimal(cv: Canvas, idx: Int, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        when (idx) {
            0 -> { // いぬ
                paint.color = Color.parseColor("#6D4C41")
                cv.drawOval(cx - r * 1.3f, cy - r * 0.6f, cx - r * 0.6f, cy + r * 0.6f, paint)
                cv.drawOval(cx + r * 0.6f, cy - r * 0.6f, cx + r * 1.3f, cy + r * 0.6f, paint)
                paint.color = Color.parseColor("#A1887F")
                cv.drawCircle(cx, cy, r, paint)
                paint.color = Color.parseColor("#D7CCC8")
                cv.drawOval(cx - r * 0.45f, cy + r * 0.05f, cx + r * 0.45f, cy + r * 0.75f, paint)
                paint.color = Color.BLACK
                cv.drawCircle(cx - r * 0.35f, cy - r * 0.2f, r * 0.09f, paint)
                cv.drawCircle(cx + r * 0.35f, cy - r * 0.2f, r * 0.09f, paint)
                cv.drawCircle(cx, cy + r * 0.2f, r * 0.12f, paint)
                paint.color = Color.parseColor("#F06292")
                cv.drawOval(cx - r * 0.12f, cy + r * 0.45f, cx + r * 0.12f, cy + r * 0.75f, paint)
            }
            1 -> { // ねこ
                paint.color = Color.parseColor("#FFB74D")
                val ear = Path()
                ear.moveTo(cx - r * 0.85f, cy - r * 0.4f)
                ear.lineTo(cx - r * 0.65f, cy - r * 1.25f)
                ear.lineTo(cx - r * 0.2f, cy - r * 0.75f)
                ear.close()
                cv.drawPath(ear, paint)
                ear.reset()
                ear.moveTo(cx + r * 0.85f, cy - r * 0.4f)
                ear.lineTo(cx + r * 0.65f, cy - r * 1.25f)
                ear.lineTo(cx + r * 0.2f, cy - r * 0.75f)
                ear.close()
                cv.drawPath(ear, paint)
                cv.drawCircle(cx, cy, r, paint)
                paint.color = Color.BLACK
                cv.drawCircle(cx - r * 0.35f, cy - r * 0.15f, r * 0.09f, paint)
                cv.drawCircle(cx + r * 0.35f, cy - r * 0.15f, r * 0.09f, paint)
                paint.strokeWidth = r * 0.05f
                cv.drawLine(cx - r * 0.3f, cy + r * 0.25f, cx - r * 1.05f, cy + r * 0.15f, paint)
                cv.drawLine(cx - r * 0.3f, cy + r * 0.4f, cx - r * 1.05f, cy + r * 0.45f, paint)
                cv.drawLine(cx + r * 0.3f, cy + r * 0.25f, cx + r * 1.05f, cy + r * 0.15f, paint)
                cv.drawLine(cx + r * 0.3f, cy + r * 0.4f, cx + r * 1.05f, cy + r * 0.45f, paint)
                paint.color = Color.parseColor("#F06292")
                val nose = Path()
                nose.moveTo(cx - r * 0.12f, cy + r * 0.15f)
                nose.lineTo(cx + r * 0.12f, cy + r * 0.15f)
                nose.lineTo(cx, cy + r * 0.32f)
                nose.close()
                cv.drawPath(nose, paint)
            }
            2 -> { // うさぎ
                paint.color = Color.WHITE
                cv.drawOval(cx - r * 0.6f, cy - r * 1.9f, cx - r * 0.1f, cy - r * 0.4f, paint)
                cv.drawOval(cx + r * 0.1f, cy - r * 1.9f, cx + r * 0.6f, cy - r * 0.4f, paint)
                paint.color = Color.parseColor("#F8BBD0")
                cv.drawOval(cx - r * 0.48f, cy - r * 1.7f, cx - r * 0.22f, cy - r * 0.6f, paint)
                cv.drawOval(cx + r * 0.22f, cy - r * 1.7f, cx + r * 0.48f, cy - r * 0.6f, paint)
                paint.color = Color.WHITE
                cv.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.04f
                paint.color = Color.parseColor("#B0BEC5")
                cv.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                cv.drawCircle(cx - r * 0.35f, cy - r * 0.1f, r * 0.09f, paint)
                cv.drawCircle(cx + r * 0.35f, cy - r * 0.1f, r * 0.09f, paint)
                paint.color = Color.parseColor("#F06292")
                cv.drawCircle(cx, cy + r * 0.2f, r * 0.1f, paint)
            }
            3 -> { // ぞう
                paint.color = Color.parseColor("#78909C")
                cv.drawCircle(cx - r * 0.95f, cy, r * 0.55f, paint)
                cv.drawCircle(cx + r * 0.95f, cy, r * 0.55f, paint)
                paint.color = Color.parseColor("#90A4AE")
                cv.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.35f
                paint.strokeCap = Paint.Cap.ROUND
                cv.drawArc(cx - r * 0.55f, cy + r * 0.1f, cx + r * 0.55f, cy + r * 1.5f, 20f, 140f, false, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                cv.drawCircle(cx - r * 0.35f, cy - r * 0.2f, r * 0.09f, paint)
                cv.drawCircle(cx + r * 0.35f, cy - r * 0.2f, r * 0.09f, paint)
            }
            4 -> { // ぱんだ
                paint.color = Color.BLACK
                cv.drawCircle(cx - r * 0.7f, cy - r * 0.7f, r * 0.35f, paint)
                cv.drawCircle(cx + r * 0.7f, cy - r * 0.7f, r * 0.35f, paint)
                paint.color = Color.WHITE
                cv.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.04f
                paint.color = Color.parseColor("#B0BEC5")
                cv.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                cv.drawOval(cx - r * 0.55f, cy - r * 0.35f, cx - r * 0.15f, cy + r * 0.15f, paint)
                cv.drawOval(cx + r * 0.15f, cy - r * 0.35f, cx + r * 0.55f, cy + r * 0.15f, paint)
                paint.color = Color.WHITE
                cv.drawCircle(cx - r * 0.35f, cy - r * 0.1f, r * 0.08f, paint)
                cv.drawCircle(cx + r * 0.35f, cy - r * 0.1f, r * 0.08f, paint)
                paint.color = Color.BLACK
                cv.drawCircle(cx, cy + r * 0.3f, r * 0.11f, paint)
            }
            5 -> { // ぶた
                paint.color = Color.parseColor("#F48FB1")
                val ear = Path()
                ear.moveTo(cx - r * 0.85f, cy - r * 0.35f)
                ear.lineTo(cx - r * 0.6f, cy - r * 1.15f)
                ear.lineTo(cx - r * 0.15f, cy - r * 0.75f)
                ear.close()
                cv.drawPath(ear, paint)
                ear.reset()
                ear.moveTo(cx + r * 0.85f, cy - r * 0.35f)
                ear.lineTo(cx + r * 0.6f, cy - r * 1.15f)
                ear.lineTo(cx + r * 0.15f, cy - r * 0.75f)
                ear.close()
                cv.drawPath(ear, paint)
                cv.drawCircle(cx, cy, r, paint)
                paint.color = Color.BLACK
                cv.drawCircle(cx - r * 0.4f, cy - r * 0.2f, r * 0.09f, paint)
                cv.drawCircle(cx + r * 0.4f, cy - r * 0.2f, r * 0.09f, paint)
                paint.color = Color.parseColor("#F06292")
                cv.drawOval(cx - r * 0.35f, cy + r * 0.05f, cx + r * 0.35f, cy + r * 0.55f, paint)
                paint.color = Color.parseColor("#AD1457")
                cv.drawCircle(cx - r * 0.13f, cy + r * 0.3f, r * 0.06f, paint)
                cv.drawCircle(cx + r * 0.13f, cy + r * 0.3f, r * 0.06f, paint)
            }
            6 -> { // かえる
                paint.color = Color.parseColor("#81C784")
                cv.drawCircle(cx - r * 0.5f, cy - r * 0.75f, r * 0.4f, paint)
                cv.drawCircle(cx + r * 0.5f, cy - r * 0.75f, r * 0.4f, paint)
                cv.drawOval(cx - r * 1.05f, cy - r * 0.75f, cx + r * 1.05f, cy + r * 0.9f, paint)
                paint.color = Color.WHITE
                cv.drawCircle(cx - r * 0.5f, cy - r * 0.75f, r * 0.24f, paint)
                cv.drawCircle(cx + r * 0.5f, cy - r * 0.75f, r * 0.24f, paint)
                paint.color = Color.BLACK
                cv.drawCircle(cx - r * 0.5f, cy - r * 0.75f, r * 0.1f, paint)
                cv.drawCircle(cx + r * 0.5f, cy - r * 0.75f, r * 0.1f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.06f
                cv.drawArc(cx - r * 0.4f, cy - r * 0.1f, cx + r * 0.4f, cy + r * 0.5f, 20f, 140f, false, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#F8BBD0")
                cv.drawCircle(cx - r * 0.7f, cy + r * 0.25f, r * 0.12f, paint)
                cv.drawCircle(cx + r * 0.7f, cy + r * 0.25f, r * 0.12f, paint)
            }
            7 -> { // ひよこ
                paint.color = Color.parseColor("#FFEE58")
                cv.drawCircle(cx, cy, r, paint)
                paint.strokeWidth = r * 0.05f
                paint.style = Paint.Style.STROKE
                cv.drawLine(cx, cy - r * 0.95f, cx - r * 0.15f, cy - r * 1.25f, paint)
                cv.drawLine(cx, cy - r * 0.95f, cx + r * 0.15f, cy - r * 1.25f, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                cv.drawCircle(cx - r * 0.35f, cy - r * 0.15f, r * 0.09f, paint)
                cv.drawCircle(cx + r * 0.35f, cy - r * 0.15f, r * 0.09f, paint)
                paint.color = Color.parseColor("#FB8C00")
                val beak = Path()
                beak.moveTo(cx - r * 0.15f, cy + r * 0.1f)
                beak.lineTo(cx + r * 0.15f, cy + r * 0.1f)
                beak.lineTo(cx, cy + r * 0.35f)
                beak.close()
                cv.drawPath(beak, paint)
                paint.color = Color.parseColor("#FFAB91")
                cv.drawCircle(cx - r * 0.6f, cy + r * 0.2f, r * 0.12f, paint)
                cv.drawCircle(cx + r * 0.6f, cy + r * 0.2f, r * 0.12f, paint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
    }
}
