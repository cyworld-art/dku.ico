package com.lim.rppg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
