package com.lim.rppg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 실시간 맥파(BVP) 파형 */
class SignalView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var data: FloatArray = FloatArray(0)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5C7A"); strokeWidth = 5f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint().apply { color = Color.parseColor("#2A3138"); strokeWidth = 2f }
    private val path = Path()

    fun setData(d: FloatArray) { data = d; invalidate() }
    fun clear() { data = FloatArray(0); invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawLine(0f, h / 2, w, h / 2, grid)
        if (data.size < 2) return
        path.reset()
        for (i in data.indices) {
            val x = w * i / (data.size - 1)
            val y = h / 2 - data[i] * h * 0.42f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, line)
    }
}

/**
 * 카메라 프리뷰 위에 얼굴 가이드와 ROI를 그린다.
 * PreviewView 기본값(FILL_CENTER) + 전면 카메라 좌우 반전을 가정한 좌표 변환.
 */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var rois: List<RectF> = emptyList()
    private var imgW = 0; private var imgH = 0
    var mirror = true

    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFB3"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    fun update(r: List<RectF>, w: Int, h: Int) { rois = r; imgW = w; imgH = h; invalidate() }

    override fun onDraw(c: Canvas) {
        val vw = width.toFloat(); val vh = height.toFloat()
        // 얼굴 가이드 타원
        c.drawOval(RectF(vw * 0.2f, vh * 0.15f, vw * 0.8f, vh * 0.75f), guidePaint)
        if (imgW == 0 || rois.isEmpty()) return
        val s = maxOf(vw / imgW, vh / imgH)
        val dx = (vw - imgW * s) / 2; val dy = (vh - imgH * s) / 2
        for (r in rois) {
            var l = dx + r.left * s; var rt = dx + r.right * s
            if (mirror) { val nl = vw - rt; rt = vw - l; l = nl }
            c.drawRect(l, dy + r.top * s, rt, dy + r.bottom * s, roiPaint)
        }
    }
}

/** rPPG vs 워치 심박 추이 비교 차트 (x: 측정 경과 초, y: BPM) */
class CompareChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var a: List<PointF> = emptyList()
    private var b: List<PointF> = emptyList()
    private var xMax = 60f

    private val pa = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5C7A"); strokeWidth = 5f; style = Paint.Style.STROKE
    }
    private val pb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4FC3F7"); strokeWidth = 5f; style = Paint.Style.STROKE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4FC3F7") }
    private val grid = Paint().apply { color = Color.parseColor("#2A3138"); strokeWidth = 2f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A949E"); textSize = 26f
    }
    private val path = Path()

    fun setData(rppg: List<PointF>, watch: List<PointF>, durationSec: Float) {
        a = rppg.toList(); b = watch.toList(); xMax = durationSec; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val padL = 56f; val padB = 8f; val padT = 8f
        val all = a + b
        var lo = (all.minOfOrNull { it.y } ?: 60f) - 5f
        var hi = (all.maxOfOrNull { it.y } ?: 100f) + 5f
        if (hi - lo < 20f) { val m = (hi + lo) / 2; lo = m - 10f; hi = m + 10f }
        fun px(x: Float) = padL + (x.coerceIn(0f, xMax) / xMax) * (w - padL - 8f)
        fun py(y: Float) = padT + (1 - (y - lo) / (hi - lo)) * (h - padT - padB)

        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2
            val y = py(v)
            c.drawLine(padL, y, w, y, grid)
            c.drawText(v.toInt().toString(), 4f, y + 9f, label)
        }
        drawLine(c, a, pa, ::px, ::py)
        if (b.size >= 2) drawLine(c, b, pb, ::px, ::py)
        for (p in b) c.drawCircle(px(p.x), py(p.y), 5f, dot)
    }

    private fun drawLine(c: Canvas, s: List<PointF>, p: Paint, px: (Float) -> Float, py: (Float) -> Float) {
        if (s.size < 2) return
        path.reset()
        s.forEachIndexed { i, pt -> if (i == 0) path.moveTo(px(pt.x), py(pt.y)) else path.lineTo(px(pt.x), py(pt.y)) }
        c.drawPath(path, p)
    }
}
