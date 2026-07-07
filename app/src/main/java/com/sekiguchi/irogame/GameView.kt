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

    // ---- 色データ（ひらがな名と色） ----
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

    private val charNames = listOf("らっこ", "かめ", "ぺんぎん")
    private val armColors = intArrayOf(
        Color.parseColor("#8D6E63"),
        Color.parseColor("#66BB6A"),
        Color.parseColor("#37474F")
    )

    // ---- ゲーム状態 ----
    private var phase = Phase.IDLE
    private var selChar = -1
    private var targetIdx = 0
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
        charY = h * 0.38f
        charR = w * 0.115f
        val bw = w * 0.78f
        val bh = h * 0.085f
        val ys = floatArrayOf(0.60f, 0.725f, 0.85f)
        for (i in 0..2) {
            btnRects[i].set(w / 2 - bw / 2, h * ys[i], w / 2 + bw / 2, h * ys[i] + bh)
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
            for (i in 0..2) {
                if (btnRects[i].contains(x, y)) {
                    answer(i)
                    return true
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
        targetIdx = Random.nextInt(colorList.size)
        val others = colorList.indices.filter { it != targetIdx }.shuffled().take(2)
        choices = (others + targetIdx).shuffled()
        boardT0 = now()
        shakeBtn = -1
        phase = Phase.QUESTION
        tg()?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    private fun answer(i: Int) {
        if (choices[i] == targetIdx) {
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
        // 地面（やわらかい丘）
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#DCEDC8")
        cv.drawOval(-w * 0.2f, charY + charR * 1.0f, w * 1.2f, charY + charR * 2.6f, paint)

        drawScore(cv)

        // キャラクター
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
        }

        // 色の板
        if (phase != Phase.IDLE && selChar >= 0) drawBoard(cv, t)

        // メッセージ
        tp.textSize = w * 0.052f
        tp.color = Color.parseColor("#6D4C41")
        val msg = when (phase) {
            Phase.IDLE -> "すきな どうぶつを タッチしてね"
            Phase.QUESTION -> "この いたは なにいろかな？"
            Phase.CELEBRATE -> ""
        }
        if (msg.isNotEmpty()) cv.drawText(msg, w / 2, h * 0.555f, tp)

        if (phase == Phase.QUESTION) drawButtons(cv, t)
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
        val bw = w * 0.38f
        val bh = bw * 0.7f
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
        cv.drawLine(charX[selChar] - charR * 0.5f, charY + yoff, bx - bw * 0.35f, by + bh * 0.45f, paint)
        cv.drawLine(charX[selChar] + charR * 0.5f, charY + yoff, bx + bw * 0.35f, by + bh * 0.45f, paint)
        paint.style = Paint.Style.FILL

        cv.save()
        cv.rotate(rot, bx, by)
        val r = RectF(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
        paint.color = Color.parseColor("#33000000")
        r.offset(0f, bh * 0.05f)
        cv.drawRoundRect(r, bw * 0.1f, bw * 0.1f, paint)
        r.offset(0f, -bh * 0.05f)
        paint.color = colorList[targetIdx].second
        cv.drawRoundRect(r, bw * 0.1f, bw * 0.1f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = bw * 0.035f
        paint.color = Color.WHITE
        cv.drawRoundRect(r, bw * 0.1f, bw * 0.1f, paint)
        paint.style = Paint.Style.FILL
        cv.restore()
    }

    private fun drawButtons(cv: Canvas, t: Long) {
        for (i in 0..2) {
            var dx = 0f
            if (i == shakeBtn) {
                val el = t - shakeT0
                if (el < 400) dx = sin(el / 25f) * w * 0.03f * (1f - el / 400f)
            }
            val r = RectF(btnRects[i])
            r.offset(dx, 0f)
            paint.color = Color.parseColor("#22000000")
            r.offset(0f, r.height() * 0.06f)
            cv.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
            r.offset(0f, -r.height() * 0.06f)
            paint.color = Color.WHITE
            cv.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.parseColor("#BCAAA4")
            cv.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
            paint.style = Paint.Style.FILL
            tp.textSize = r.height() * 0.5f
            tp.color = Color.parseColor("#4E342E")
            val ty = r.centerY() - (tp.descent() + tp.ascent()) / 2
            cv.drawText(colorList[choices[i]].first, r.centerX(), ty, tp)
        }
    }

    private fun drawCelebrate(cv: Canvas, t: Long, dt: Float) {
        val el = t - celebT0
        val cx = w / 2
        val cy = h * 0.42f

        // 回転する光線
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

        // 紙吹雪
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

        // 「せいかい！」
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
        // からだ
        paint.color = Color.parseColor("#8D6E63")
        cv.drawOval(cx - r * 0.85f, cy - r * 0.2f, cx + r * 0.85f, cy + r * 1.15f, paint)
        // おなか
        paint.color = Color.parseColor("#D7CCC8")
        cv.drawOval(cx - r * 0.5f, cy + r * 0.05f, cx + r * 0.5f, cy + r * 1.0f, paint)
        // みみ
        paint.color = Color.parseColor("#8D6E63")
        cv.drawCircle(cx - r * 0.5f, cy - r * 1.05f, r * 0.2f, paint)
        cv.drawCircle(cx + r * 0.5f, cy - r * 1.05f, r * 0.2f, paint)
        // あたま
        cv.drawCircle(cx, cy - r * 0.5f, r * 0.72f, paint)
        // かお
        paint.color = Color.parseColor("#EFEBE9")
        cv.drawOval(cx - r * 0.45f, cy - r * 0.55f, cx + r * 0.45f, cy + r * 0.1f, paint)
        // め
        paint.color = Color.BLACK
        cv.drawCircle(cx - r * 0.28f, cy - r * 0.62f, r * 0.07f, paint)
        cv.drawCircle(cx + r * 0.28f, cy - r * 0.62f, r * 0.07f, paint)
        // はな
        cv.drawCircle(cx, cy - r * 0.35f, r * 0.09f, paint)
        // ひげ
        paint.strokeWidth = r * 0.04f
        cv.drawLine(cx - r * 0.15f, cy - r * 0.3f, cx - r * 0.55f, cy - r * 0.35f, paint)
        cv.drawLine(cx - r * 0.15f, cy - r * 0.24f, cx - r * 0.55f, cy - r * 0.18f, paint)
        cv.drawLine(cx + r * 0.15f, cy - r * 0.3f, cx + r * 0.55f, cy - r * 0.35f, paint)
        cv.drawLine(cx + r * 0.15f, cy - r * 0.24f, cx + r * 0.55f, cy - r * 0.18f, paint)
        // あし
        paint.color = Color.parseColor("#6D4C41")
        cv.drawOval(cx - r * 0.6f, cy + r * 0.95f, cx - r * 0.15f, cy + r * 1.2f, paint)
        cv.drawOval(cx + r * 0.15f, cy + r * 0.95f, cx + r * 0.6f, cy + r * 1.2f, paint)
    }

    private fun drawTurtle(cv: Canvas, cx: Float, cy: Float, r: Float) {
        // あし
        paint.color = Color.parseColor("#9CCC65")
        cv.drawOval(cx - r * 0.85f, cy + r * 0.7f, cx - r * 0.35f, cy + r * 1.15f, paint)
        cv.drawOval(cx + r * 0.35f, cy + r * 0.7f, cx + r * 0.85f, cy + r * 1.15f, paint)
        // あたま
        cv.drawCircle(cx, cy - r * 0.65f, r * 0.5f, paint)
        // め
        paint.color = Color.BLACK
        cv.drawCircle(cx - r * 0.18f, cy - r * 0.72f, r * 0.07f, paint)
        cv.drawCircle(cx + r * 0.18f, cy - r * 0.72f, r * 0.07f, paint)
        // くち（にっこり）
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.05f
        cv.drawArc(cx - r * 0.18f, cy - r * 0.65f, cx + r * 0.18f, cy - r * 0.4f, 20f, 140f, false, paint)
        paint.style = Paint.Style.FILL
        // こうら
        paint.color = Color.parseColor("#66BB6A")
        cv.drawOval(cx - r * 0.9f, cy - r * 0.35f, cx + r * 0.9f, cy + r * 1.05f, paint)
        // こうらのもよう
        paint.color = Color.parseColor("#43A047")
        cv.drawCircle(cx, cy + r * 0.35f, r * 0.3f, paint)
        cv.drawCircle(cx - r * 0.45f, cy + r * 0.3f, r * 0.18f, paint)
        cv.drawCircle(cx + r * 0.45f, cy + r * 0.3f, r * 0.18f, paint)
        cv.drawCircle(cx, cy + r * 0.75f, r * 0.18f, paint)
    }

    private fun drawPenguin(cv: Canvas, cx: Float, cy: Float, r: Float) {
        // あし
        paint.color = Color.parseColor("#FB8C00")
        cv.drawOval(cx - r * 0.55f, cy + r * 0.95f, cx - r * 0.1f, cy + r * 1.2f, paint)
        cv.drawOval(cx + r * 0.1f, cy + r * 0.95f, cx + r * 0.55f, cy + r * 1.2f, paint)
        // からだ
        paint.color = Color.parseColor("#37474F")
        cv.drawOval(cx - r * 0.8f, cy - r * 1.15f, cx + r * 0.8f, cy + r * 1.1f, paint)
        // おなか
        paint.color = Color.WHITE
        cv.drawOval(cx - r * 0.5f, cy - r * 0.45f, cx + r * 0.5f, cy + r * 0.95f, paint)
        // かおの白いところ
        cv.drawCircle(cx - r * 0.25f, cy - r * 0.6f, r * 0.28f, paint)
        cv.drawCircle(cx + r * 0.25f, cy - r * 0.6f, r * 0.28f, paint)
        // め
        paint.color = Color.BLACK
        cv.drawCircle(cx - r * 0.22f, cy - r * 0.6f, r * 0.08f, paint)
        cv.drawCircle(cx + r * 0.22f, cy - r * 0.6f, r * 0.08f, paint)
        // くちばし
        paint.color = Color.parseColor("#FB8C00")
        val beak = Path()
        beak.moveTo(cx - r * 0.15f, cy - r * 0.42f)
        beak.lineTo(cx + r * 0.15f, cy - r * 0.42f)
        beak.lineTo(cx, cy - r * 0.2f)
        beak.close()
        cv.drawPath(beak, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
    }
}
