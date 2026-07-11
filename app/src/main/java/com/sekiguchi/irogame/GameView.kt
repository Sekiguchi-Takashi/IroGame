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
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    private enum class Phase { IDLE, QUESTION, CELEBRATE }
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

    // ---- どうぶつデータ（らっこ：どうぶつクイズ） ----
    private val animalNames = listOf("いぬ", "ねこ", "うさぎ", "ぞう", "ぱんだ", "ぶた", "かえる", "ひよこ")

    private val charNames = listOf("らっこ", "かめ", "ぺんぎん")
    private val charModes = listOf("どうぶつ", "いろ", "かず")
    private val armColors = intArrayOf(
        Color.parseColor("#8D6E63"),
        Color.parseColor("#66BB6A"),
        Color.parseColor("#37474F")
    )

    // ---- ゲーム状態 ----
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
        // 3択ボタン（どうぶつ・いろ）
        val bw = w * 0.78f
        val bh = h * 0.08f
        val ys = floatArrayOf(0.615f, 0.725f, 0.835f)
        for (i in 0..2) {
            btnRects[i].set(w / 2 - bw / 2, h * ys[i], w / 2 + bw / 2, h * ys[i] + bh)
        }
        // 数字ボタン 1〜10（5×2）
        val cbw = w * 0.164f
        val cbh = h * 0.075f
        for (i in 0..9) {
            val col = i % 5
            val row = i / 5
            val cx = w * (0.12f + 0.19f * col)
            val ty = h * (0.63f + 0.125f * row)
            countRects[i].set(cx - cbw / 2, ty, cx + cbw / 2, ty + cbh)
        }
    }

    // ============ タッチ ============
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) return true
        val x = e.x
        val y = e.y
        if (phase == Phase.CELEBRATE) return true
        val ci = hitChar(x, y)
        if (ci >= 0) {
            startQuestion(ci)
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

    private fun hitChar(x: Float, y: Float): Int {
        for (i in 0..2) {
            val dx = x - charX[i]
            val dy = y - charY
            if (dx * dx + dy * dy < charR * 1.5f * charR * 1.5f) return i
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

    private fun answer(i: Int) {
        val correct = when (mode) {
            Mode.COUNT -> i + 1 == countTarget
            else -> choices[i] == targetIdx
        }
        if (correct) {
            phase = Phase.CELEBRATE
            celebT0 = now()
            score++
            spawnConfetti()
            val t = tg()
            t?.startTone(ToneGenerator.TONE_DTMF_1, 130)
            uiHandler.postDelayed({ t?.startTone(ToneGenerator.TONE_DTMF_5, 130) }, 160)
            uiHandler.postDelayed({ t?.startTone(ToneGenerator.TONE_DTMF_9, 160) }, 320)
            uiHandler.postDelayed({ t?.startTone(ToneGenerator.TONE_DTMF_D, 320) }, 500)
            uiHandler.postDelayed({
                phase = Phase.IDLE
                selChar = -1
                parts.clear()
            }, 3000)
        } else {
            shakeBtn = i
            shakeT0 = now()
            tg()?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
        }
    }

    private fun spawnConfetti() {
        parts.clear()
        val palette = colorList.map { it.second }
        repeat(110) {
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val sp = w * 0.3f + Random.nextFloat() * w * 1.1f
            parts.add(
                P(
                    x = w / 2, y = h * 0.40f,
                    vx = cos(a) * sp, vy = sin(a) * sp - w * 0.55f,
                    rot = Random.nextFloat() * 360f,
                    vr = (Random.nextFloat() - 0.5f) * 720f,
                    size = w * 0.012f + Random.nextFloat() * w * 0.02f,
                    c = palette[Random.nextInt(palette.size)]
                )
            )
        }
    }

    // ============ 描画 ============
    override fun onDraw(cv: Canvas) {
        val t = now()
        val dt = if (lastFrame == 0L) 0f else min(0.05f, (t - lastFrame) / 1000f)
        lastFrame = t

        cv.drawColor(Color.parseColor("#FFF8E1"))
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#DCEDC8")
        cv.drawOval(-w * 0.2f, charY + charR * 1.0f, w * 1.2f, charY + charR * 2.6f, paint)

        drawScore(cv)

        for (i in 0..2) {
            var yoff = sin(t / 400f + i * 2f) * charR * 0.05f
            if (phase == Phase.CELEBRATE && i == selChar) {
                yoff = -abs(sin((t - celebT0) / 170f)) * charR * 0.55f
            }
            if (selChar == i && phase != Phase.IDLE) {
                paint.color = Color.parseColor("#66FFF176")
                cv.drawCircle(charX[i], charY + yoff, charR * 1.7f, paint)
            }
            drawChar(cv, i, charX[i], charY + yoff, charR)
            tp.textSize = w * 0.042f
            tp.color = Color.parseColor("#6D4C41")
            cv.drawText(charNames[i], charX[i], charY + charR * 1.55f, tp)
            tp.textSize = w * 0.03f
            tp.color = Color.parseColor("#A1887F")
            cv.drawText(charModes[i], charX[i], charY + charR * 1.9f, tp)
        }

        if (phase != Phase.IDLE && selChar >= 0) drawBoard(cv, t)

        tp.textSize = w * 0.05f
        tp.color = Color.parseColor("#6D4C41")
        val msg = when (phase) {
            Phase.IDLE -> "すきな どうぶつを タッチしてね"
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
        if (phase == Phase.CELEBRATE) drawCelebrate(cv, t, dt)

        postInvalidateOnAnimation()
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

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
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

        // うで
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

    private fun drawCelebrate(cv: Canvas, t: Long, dt: Float) {
        val el = t - celebT0
        val cx = w / 2
        val cy = h * 0.44f

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
        cv.drawText("せいかい！", cx, cy, tp)
        tp.style = Paint.Style.FILL
        tp.color = Color.HSVToColor(floatArrayOf((el / 8f) % 360f, 0.85f, 0.95f))
        cv.drawText("せいかい！", cx, cy, tp)
        tp.textSize = w * 0.07f
        tp.color = Color.parseColor("#FF7043")
        cv.drawText("すごーい！", cx, cy + w * 0.13f, tp)
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
