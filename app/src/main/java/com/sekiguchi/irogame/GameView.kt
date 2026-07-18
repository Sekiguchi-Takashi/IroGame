package com.sekiguchi.irogame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    private enum class Screen { MAIN, HIDE, HIDE2 }
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

    // ---- かくれんぼ共通 ----
    private var spotIdx = 0
    private var hideChr = 0
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
    private var trBtnX = 0f
    private var trBtnY = 0f
    private var trBtnR = 0f

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
        trBtnX = w * 0.885f
        trBtnY = h * 0.085f
        trBtnR = w * 0.07f
    }

    // ============ タッチ ============
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) return true
        val x = e.x
        val y = e.y

        if (screen == Screen.HIDE || screen == Screen.HIDE2) {
            if (hitCorner(x, y)) {
                screen = Screen.MAIN
                phase = Phase.IDLE
                selChar = -1
                hideFound = false
                parts.clear()
                tg()?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                return true
            }
            if (hitTopRight(x, y)) {
                screen = if (screen == Screen.HIDE) Screen.HIDE2 else Screen.HIDE
                hideFound = false
                pickNewSpot()
                parts.clear()
                tg()?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                return true
            }
            if (!hideFound) {
                val pos = curSpotPos()
                if (abs(x - pos[0]) < w * 0.15f && abs(y - pos[1]) < w * 0.18f) {
                    hideFound = true
                    foundT0 = now()
                    score++
                    spawnConfetti(pos[0], pos[1])
                    playHappy()
                    uiHandler.postDelayed({
                        hideFound = false
                        pickNewSpot()
                        parts.clear()
                    }, 3200)
                }
            }
            return true
        }

        // MAIN画面
        if (phase == Phase.CELEBRATE || phase == Phase.LINEUP) return true
        if (hitCorner(x, y)) {
            screen = Screen.HIDE
            hideFound = false
            pickNewSpot()
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

    private fun hitTopRight(x: Float, y: Float): Boolean {
        val dx = x - trBtnX
        val dy = y - trBtnY
        return dx * dx + dy * dy < trBtnR * 1.4f * trBtnR * 1.4f
    }

    private fun hitChar(x: Float, y: Float): Int {
        for (i in 0..2) {
            val dx = x - curX[i]
            val dy = y - curY[i]
            if (dx * dx + dy * dy < charR * 1.6f * charR * 1.6f) return i
        }
        return -1
    }

    private fun pickNewSpot() {
        if (screen == Screen.HIDE2) {
            var ns = Random.nextInt(beachSpots.size)
            while (ns == spotIdx) ns = Random.nextInt(beachSpots.size)
            spotIdx = ns
            hideChr = beachSpots[spotIdx][0].toInt()
        } else {
            var ns = Random.nextInt(hideSpots.size)
            while (ns == spotIdx) ns = Random.nextInt(hideSpots.size)
            spotIdx = ns
            hideChr = Random.nextInt(3)
        }
    }

    private fun curSpotPos(): FloatArray =
        if (screen == Screen.HIDE2)
            floatArrayOf(mx(beachSpots[spotIdx][1]), my(beachSpots[spotIdx][2]))
        else
            floatArrayOf(mx(hideSpots[spotIdx][0]), my(hideSpots[spotIdx][1]))

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

    // ============ 描画（メイン） ============
    override fun onDraw(cv: Canvas) {
        val t = now()
        val dt = if (lastFrame == 0L) 0f else min(0.05f, (t - lastFrame) / 1000f)
        lastFrame = t

        if (screen == Screen.HIDE) {
            drawHideScreen(cv, t, dt)
            postInvalidateOnAnimation()
            return
        }
        if (screen == Screen.HIDE2) {
            drawHide2Screen(cv, t, dt)
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

        if (phase == Phase.IDLE) {
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#494FC3F7")
            cv.drawRect(0f, h * 0.21f, w, h * 0.455f, paint)
            paint.color = Color.parseColor("#88FFFFFF")
            for (k in 0..5) {
                val bx = w * (0.08f + 0.17f * k)
                val by = h * 0.44f - ((t * 0.028f + k * 251f) % (h * 0.20f))
                cv.drawCircle(bx, by, w * (0.008f + 0.004f * (k % 3)), paint)
            }
        }

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
        if (phase == Phase.CELEBRATE) drawCelebrate(cv, t, dt, "せいかい！", "すごーい！", w / 2, h * 0.44f, h * 0.44f)
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
        paint.color = Color.parseColor("#B3E5FC")
        cv.drawRect(0f, 0f, w, h * 0.22f, paint)
        paint.color = Color.parseColor("#FFF176")
        cv.drawCircle(w * 0.87f, h * 0.075f, w * 0.055f, paint)
        paint.color = Color.WHITE
        val cx1 = w * (0.22f + 0.02f * sin(t / 4000f))
        cv.drawOval(cx1 - w * 0.1f, h * 0.05f, cx1 + w * 0.1f, h * 0.085f, paint)
        cv.drawOval(cx1 - w * 0.05f, h * 0.035f, cx1 + w * 0.06f, h * 0.07f, paint)
        paint.color = Color.parseColor("#4FC3F7")
        cv.drawRect(0f, h * 0.16f, w, h * 0.47f, paint)
        paint.color = Color.parseColor("#81D4FA")
        for (k in 0..4) {
            val wx = w * 0.2f * k + (t * 0.01f) % (w * 0.2f)
            cv.drawArc(wx - w * 0.1f, h * 0.165f, wx + w * 0.1f, h * 0.195f, 180f, 180f, false, paint)
        }
        paint.color = Color.parseColor("#FFE0B2")
        cv.drawRect(0f, h * 0.465f, w, h, paint)
        cv.drawOval(-w * 0.2f, h * 0.435f, w * 1.2f, h * 0.52f, paint)
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
        paint.color = Color.WHITE
        cv.drawOval(cx - r * 0.45f, cy - r * 0.95f, cx - r * 0.1f, cy - r * 0.15f, paint)
        cv.drawOval(cx + r * 0.1f, cy - r * 0.95f, cx + r * 0.45f, cy - r * 0.15f, paint)
        paint.color = Color.parseColor("#F8BBD0")
        cv.drawOval(cx - r * 0.37f, cy - r * 0.82f, cx - r * 0.18f, cy - r * 0.28f, paint)
        cv.drawOval(cx + r * 0.18f, cy - r * 0.82f, cx + r * 0.37f, cy - r * 0.28f, paint)
        paint.color = Color.WHITE
        cv.drawCircle(cx, cy + r * 0.12f, r * 0.55f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.05f
        paint.color = Color.parseColor("#BCAAA4")
        cv.drawCircle(cx, cy + r * 0.12f, r * 0.55f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#F06292")
        cv.drawCircle(cx, cy + r * 0.35f, r * 0.06f, paint)
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

    // ---- うみべへボタン（かめ） ----
    private fun drawTurtleButton(cv: Canvas) {
        val cx = trBtnX
        val cy = trBtnY
        val r = trBtnR
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#33000000")
        cv.drawCircle(cx, cy + r * 0.08f, r, paint)
        paint.color = Color.WHITE
        cv.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.09f
        paint.color = Color.parseColor("#66BB6A")
        cv.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        drawTurtle(cv, cx, cy - r * 0.05f, r * 0.42f)
        tp.textSize = w * 0.026f
        tp.color = Color.parseColor("#33691E")
        cv.drawText("うみべへ", cx, cy + r * 1.5f, tp)
    }

    // ---- こうえんへボタン（き） ----
    private fun drawParkButton(cv: Canvas) {
        val cx = trBtnX
        val cy = trBtnY
        val r = trBtnR
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#33000000")
        cv.drawCircle(cx, cy + r * 0.08f, r, paint)
        paint.color = Color.WHITE
        cv.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.09f
        paint.color = Color.parseColor("#8D6E63")
        cv.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#6D4C41")
        cv.drawRect(cx - r * 0.1f, cy, cx + r * 0.1f, cy + r * 0.5f, paint)
        paint.color = Color.parseColor("#43A047")
        cv.drawCircle(cx, cy - r * 0.22f, r * 0.42f, paint)
        cv.drawCircle(cx - r * 0.3f, cy - r * 0.02f, r * 0.28f, paint)
        cv.drawCircle(cx + r * 0.3f, cy - r * 0.02f, r * 0.28f, paint)
        tp.textSize = w * 0.026f
        tp.color = Color.WHITE
        cv.drawText("こうえんへ", cx, cy + r * 1.5f, tp)
    }

    // ============ 画像まわり（かくれんぼ共通） ============
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bgPark: Bitmap? = null
    private var bgBeach: Bitmap? = null
    private val stickers = arrayOfNulls<Bitmap>(3)
    private val stickers2 = arrayOfNulls<Bitmap>(3)
    private var ms = 1f
    private var mdx = 0f
    private var mdy = 0f
    private var imgK = 1f

    private fun ensureBitmaps() {
        if (bgPark == null) {
            bgPark = BitmapFactory.decodeResource(resources, R.drawable.bg_park)
            stickers[0] = BitmapFactory.decodeResource(resources, R.drawable.st_dog)
            stickers[1] = BitmapFactory.decodeResource(resources, R.drawable.st_rabbit)
            stickers[2] = BitmapFactory.decodeResource(resources, R.drawable.st_boar)
            bgBeach = BitmapFactory.decodeResource(resources, R.drawable.bg_beach)
            stickers2[0] = BitmapFactory.decodeResource(resources, R.drawable.st_whale)
            stickers2[1] = BitmapFactory.decodeResource(resources, R.drawable.st_turtle)
            stickers2[2] = BitmapFactory.decodeResource(resources, R.drawable.st_crab)
        }
    }

    private fun mx(px: Float) = px * imgK * ms + mdx
    private fun my(py: Float) = py * imgK * ms + mdy

    // ============ かくれんぼ画面1（こうえん） ============
    // かくれる場所（元画像1024x1536の座標系）
    private val hideSpots = arrayOf(
        floatArrayOf(300f, 715f),   // すべりだいのうえ
        floatArrayOf(615f, 1140f),  // すなばのうえ
        floatArrayOf(598f, 583f),   // ブランコのうえ
        floatArrayOf(840f, 995f),   // もくばのうえ
        floatArrayOf(800f, 1490f)   // ベンチのした
    )

    private val hintNames = listOf("いぬさん", "うさぎさん", "いのししさん")
    private val partNames = listOf("しっぽ", "みみ", "はな")

    private fun drawHideScreen(cv: Canvas, t: Long, dt: Float) {
        ensureBitmaps()
        val bg = bgPark
        if (bg == null) {
            cv.drawColor(Color.parseColor("#AED581"))
            drawPenguinButton(cv)
            return
        }
        ms = max(w / bg.width, h / bg.height)
        mdx = (w - bg.width * ms) / 2f
        mdy = (h - bg.height * ms) / 2f
        imgK = bg.width / 1024f
        cv.drawBitmap(bg, null, RectF(mdx, mdy, mdx + bg.width * ms, mdy + bg.height * ms), bmpPaint)

        val px = mx(hideSpots[spotIdx][0])
        val py = my(hideSpots[spotIdx][1])
        if (!hideFound) {
            drawPeekPart(cv, hideChr, px, py, t)
            val hint = "だれかの " + partNames[hideChr] + "が みえるよ！"
            tp.textSize = w * 0.05f
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = w * 0.014f
            tp.color = Color.WHITE
            cv.drawText(hint, w / 2, h * 0.095f, tp)
            tp.style = Paint.Style.FILL
            tp.color = Color.parseColor("#33691E")
            cv.drawText(hint, w / 2, h * 0.095f, tp)
        } else {
            drawCelebrate(cv, t, dt, "みつけた！", "やったー！", w / 2, h * 0.40f, h * 0.40f)
            drawPhotoCard(cv, t)
        }

        drawScore(cv)
        drawPenguinButton(cv)
        drawTurtleButton(cv)
    }

    private fun drawPeekPart(cv: Canvas, chr: Int, x: Float, y: Float, t: Long) {
        val wig = sin(t / 280f) * 4f
        cv.save()
        cv.rotate(wig, x, y)
        val s = w * 0.055f
        when (chr) {
            0 -> drawShibaTail(cv, x, y, s)
            1 -> drawRabbitEars(cv, x, y, s)
            else -> drawBoarNose(cv, x, y, s)
        }
        cv.restore()
        paint.style = Paint.Style.FILL
    }

    private fun drawShibaTail(cv: Canvas, x: Float, y: Float, s: Float) {
        val rect = RectF(x - s, y - s * 1.7f, x + s, y + s * 0.3f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.parseColor("#4A3426")
        paint.strokeWidth = s * 0.95f
        cv.drawArc(rect, 150f, 260f, false, paint)
        paint.color = Color.parseColor("#E8913A")
        paint.strokeWidth = s * 0.72f
        cv.drawArc(rect, 150f, 260f, false, paint)
        paint.color = Color.parseColor("#F8E7CE")
        paint.strokeWidth = s * 0.3f
        cv.drawArc(rect, 165f, 235f, false, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawRabbitEars(cv: Canvas, x: Float, y: Float, s: Float) {
        for (side in intArrayOf(-1, 1)) {
            cv.save()
            cv.rotate(13f * side, x, y)
            val l = x + side * s * 0.55f
            paint.color = Color.parseColor("#4A3426")
            cv.drawOval(l - s * 0.44f, y - s * 2.65f, l + s * 0.44f, y + s * 0.2f, paint)
            paint.color = Color.parseColor("#C89B6B")
            cv.drawOval(l - s * 0.35f, y - s * 2.52f, l + s * 0.35f, y + s * 0.08f, paint)
            paint.color = Color.parseColor("#EBD6B4")
            cv.drawOval(l - s * 0.17f, y - s * 2.2f, l + s * 0.17f, y - s * 0.2f, paint)
            cv.restore()
        }
    }

    private fun drawBoarNose(cv: Canvas, x: Float, y: Float, s: Float) {
        paint.color = Color.parseColor("#4A3426")
        cv.drawOval(x - s * 1.15f, y - s * 0.85f, x + s * 1.15f, y + s * 0.85f, paint)
        paint.color = Color.parseColor("#9C7250")
        cv.drawOval(x - s * 1.0f, y - s * 0.72f, x + s * 1.0f, y + s * 0.72f, paint)
        paint.color = Color.parseColor("#5D4037")
        cv.drawOval(x - s * 0.5f, y - s * 0.35f, x - s * 0.15f, y + s * 0.35f, paint)
        cv.drawOval(x + s * 0.15f, y - s * 0.35f, x + s * 0.5f, y + s * 0.35f, paint)
        paint.color = Color.parseColor("#B98A63")
        cv.drawOval(x - s * 0.85f, y - s * 0.52f, x - s * 0.45f, y - s * 0.22f, paint)
    }

    // ============ かくれんぼ画面2（うみべ） ============
    // かくれる場所（元画像1024x1024の座標系）: {キャラ(0=くじら 1=かめ 2=かに), x, y}
    private val beachSpots = arrayOf(
        floatArrayOf(0f, 300f, 545f),  // くじら: ひだりの うみ
        floatArrayOf(0f, 520f, 495f),  // くじら: なみの ちかく
        floatArrayOf(1f, 480f, 800f),  // かめ: すなはまの まんなか
        floatArrayOf(1f, 700f, 555f),  // かめ: いわの ちかく
        floatArrayOf(2f, 690f, 895f),  // かに: くさの かげ
        floatArrayOf(2f, 430f, 938f)   // かに: すなはまの した
    )

    private val hintNames2 = listOf("くじらさん", "かめさん", "かにさん")
    private val partNames2 = listOf("しおふき", "こうら", "はさみ")

    private fun drawHide2Screen(cv: Canvas, t: Long, dt: Float) {
        ensureBitmaps()
        val bg = bgBeach
        if (bg == null) {
            cv.drawColor(Color.parseColor("#4FC3F7"))
            drawPenguinButton(cv)
            return
        }
        ms = max(w / bg.width, h / bg.height)
        mdx = (w - bg.width * ms) / 2f
        mdy = (h - bg.height * ms) / 2f
        imgK = bg.width / 1024f
        cv.drawBitmap(bg, null, RectF(mdx, mdy, mdx + bg.width * ms, mdy + bg.height * ms), bmpPaint)

        val sp = beachSpots[spotIdx]
        val px = mx(sp[1])
        val py = my(sp[2])
        if (!hideFound) {
            val wig = sin(t / 280f) * 4f
            cv.save()
            cv.rotate(wig, px, py)
            val s = w * 0.055f
            when (sp[0].toInt()) {
                0 -> drawWhaleSpout(cv, px, py, s)
                1 -> drawTurtleShellPart(cv, px, py, s)
                else -> drawCrabClaws(cv, px, py, s)
            }
            cv.restore()
            paint.style = Paint.Style.FILL
            val hint = "だれかの " + partNames2[sp[0].toInt()] + "が みえるよ！"
            tp.textSize = w * 0.05f
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = w * 0.014f
            tp.color = Color.WHITE
            cv.drawText(hint, w / 2, h * 0.095f, tp)
            tp.style = Paint.Style.FILL
            tp.color = Color.parseColor("#01579B")
            cv.drawText(hint, w / 2, h * 0.095f, tp)
        } else {
            drawCelebrate(cv, t, dt, "みつけた！", "やったー！", w / 2, h * 0.40f, h * 0.40f)
            drawPhotoCard(cv, t)
        }

        drawScore(cv)
        drawPenguinButton(cv)
        drawParkButton(cv)
    }

    private fun drawWhaleSpout(cv: Canvas, x: Float, y: Float, s: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#CCFFFFFF")
        cv.drawOval(x - s * 1.25f, y - s * 0.28f, x + s * 1.25f, y + s * 0.28f, paint)
        drawDrop(cv, x, y - s * 2.0f, s * 0.52f, 0f)
        drawDrop(cv, x - s * 0.9f, y - s * 1.45f, s * 0.42f, -30f)
        drawDrop(cv, x + s * 0.9f, y - s * 1.45f, s * 0.42f, 30f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.parseColor("#253C50")
        paint.strokeWidth = s * 0.34f
        cv.drawLine(x, y - s * 0.1f, x, y - s * 0.95f, paint)
        paint.color = Color.parseColor("#6FA0BF")
        paint.strokeWidth = s * 0.2f
        cv.drawLine(x, y - s * 0.1f, x, y - s * 0.95f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawDrop(cv: Canvas, cx: Float, cy: Float, r: Float, deg: Float) {
        cv.save()
        cv.rotate(deg, cx, cy)
        val p = Path()
        p.moveTo(cx, cy - r * 1.5f)
        p.cubicTo(cx + r * 0.95f, cy - r * 0.4f, cx + r * 0.85f, cy + r * 0.6f, cx, cy + r * 0.6f)
        p.cubicTo(cx - r * 0.85f, cy + r * 0.6f, cx - r * 0.95f, cy - r * 0.4f, cx, cy - r * 1.5f)
        p.close()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.3f
        paint.color = Color.parseColor("#253C50")
        cv.drawPath(p, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#6FA0BF")
        cv.drawPath(p, paint)
        paint.color = Color.parseColor("#D8EAF5")
        cv.drawCircle(cx - r * 0.25f, cy + r * 0.05f, r * 0.18f, paint)
        cv.restore()
    }

    private fun drawTurtleShellPart(cv: Canvas, x: Float, y: Float, s: Float) {
        paint.style = Paint.Style.FILL
        val dome = Path()
        val oval = RectF(x - s * 1.35f, y - s * 1.9f, x + s * 1.35f, y + s * 0.55f)
        dome.arcTo(oval, 180f, 180f)
        dome.close()
        paint.color = Color.parseColor("#8B5E3C")
        cv.drawPath(dome, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.16f
        paint.color = Color.parseColor("#4A3426")
        cv.drawPath(dome, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#6E4527")
        cv.drawCircle(x, y - s * 1.05f, s * 0.4f, paint)
        cv.drawCircle(x - s * 0.72f, y - s * 0.5f, s * 0.3f, paint)
        cv.drawCircle(x + s * 0.72f, y - s * 0.5f, s * 0.3f, paint)
        paint.color = Color.parseColor("#E8D3A8")
        val rim = RectF(x - s * 1.42f, y - s * 0.1f, x + s * 1.42f, y + s * 0.26f)
        cv.drawRoundRect(rim, s * 0.18f, s * 0.18f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.1f
        paint.color = Color.parseColor("#4A3426")
        cv.drawRoundRect(rim, s * 0.18f, s * 0.18f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawCrabClaws(cv: Canvas, x: Float, y: Float, s: Float) {
        for (side in intArrayOf(-1, 1)) {
            val ax = x + side * s * 0.85f
            val ay = y - s * 1.05f
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.parseColor("#5A2418")
            paint.strokeWidth = s * 0.42f
            cv.drawLine(x + side * s * 0.3f, y + s * 0.3f, ax, ay + s * 0.45f, paint)
            paint.color = Color.parseColor("#D95F4B")
            paint.strokeWidth = s * 0.26f
            cv.drawLine(x + side * s * 0.3f, y + s * 0.3f, ax, ay + s * 0.45f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#5A2418")
            cv.drawCircle(ax, ay, s * 0.66f, paint)
            paint.color = Color.parseColor("#D95F4B")
            cv.drawCircle(ax, ay, s * 0.52f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s * 0.14f
            paint.color = Color.parseColor("#5A2418")
            cv.drawLine(ax, ay - s * 0.52f, ax, ay - s * 0.05f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#F2A08F")
            cv.drawCircle(ax - side * s * 0.18f, ay - s * 0.18f, s * 0.12f, paint)
        }
    }

    // みつけたら写真がでる（両画面共通）
    private fun drawPhotoCard(cv: Canvas, t: Long) {
        val bmp = (if (screen == Screen.HIDE2) stickers2 else stickers)[hideChr] ?: return
        val ap = ((t - foundT0) / 420f).coerceIn(0f, 1f)
        val sc = easeOutBack(ap)
        val cw = w * 0.60f
        val ch = cw * 1.18f
        val cx = w / 2
        val cy = h * 0.40f
        cv.save()
        cv.scale(sc, sc, cx, cy)
        cv.rotate(sin(t / 600f) * 2f, cx, cy)
        val r = RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#55000000")
        r.offset(w * 0.012f, w * 0.012f)
        cv.drawRoundRect(r, w * 0.03f, w * 0.03f, paint)
        r.offset(-w * 0.012f, -w * 0.012f)
        paint.color = Color.WHITE
        cv.drawRoundRect(r, w * 0.03f, w * 0.03f, paint)
        val m = cw * 0.06f
        cv.drawBitmap(bmp, null, RectF(r.left + m, r.top + m, r.right - m, r.top + m + (cw - 2 * m)), bmpPaint)
        val nm = if (screen == Screen.HIDE2) hintNames2[hideChr] else hintNames[hideChr]
        tp.textSize = cw * 0.105f
        tp.color = Color.parseColor("#4E342E")
        cv.drawText(nm + " はっけん！", cx, r.bottom - ch * 0.05f, tp)
        cv.restore()
    }

    private fun drawCelebrate(cv: Canvas, t: Long, dt: Float, mainText: String, subText: String, cx: Float, cy: Float, textCy: Float) {
        val baseT = if (screen == Screen.MAIN) celebT0 else foundT0
        val el = t - baseT

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
        cv.scale(scale, scale, cx, textCy)
        tp.textSize = w * 0.17f
        tp.style = Paint.Style.STROKE
        tp.strokeWidth = w * 0.02f
        tp.color = Color.WHITE
        cv.drawText(mainText, cx, textCy, tp)
        tp.style = Paint.Style.FILL
        tp.color = Color.HSVToColor(floatArrayOf((el / 8f) % 360f, 0.85f, 0.95f))
        cv.drawText(mainText, cx, textCy, tp)
        tp.textSize = w * 0.07f
        tp.color = Color.parseColor("#FF7043")
        cv.drawText(subText, cx, textCy + w * 0.13f, tp)
        cv.restore()
    }

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
