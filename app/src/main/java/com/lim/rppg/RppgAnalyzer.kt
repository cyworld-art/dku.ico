package com.lim.rppg

import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor

/** 한 프레임의 측정 결과. ROI 좌표는 '똑바로 세운(upright)' 이미지 기준 */
data class FrameSample(
    val timestampNs: Long,
    val faceFound: Boolean,
    val r: Double, val g: Double, val b: Double,
    val rois: List<RectF>,
    val uprightWidth: Int, val uprightHeight: Int,
)

/**
 * CameraX 분석기: ML Kit로 얼굴을 찾고 이마·양 볼 ROI의 평균 RGB를 계산.
 * YUV→RGB 변환은 선형(affine)이므로 ROI의 평균 Y/U/V를 먼저 구한 뒤 한 번만 변환한다.
 */
class RppgAnalyzer(
    private val executor: Executor,
    private val listener: (FrameSample) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.2f)
            .build()
    )

    /** 얼굴 박스 지터는 그대로 모션 노이즈가 되므로 EMA로 부드럽게 */
    private var smooth: RectF? = null
    private val emaAlpha = 0.25f

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        detector.process(input).addOnCompleteListener(executor) { task ->
            try {
                val faces = if (task.isSuccessful) task.result else emptyList()
                handle(proxy, faces, rotation)
            } finally {
                proxy.close()
            }
        }
    }

    private fun handle(proxy: ImageProxy, faces: List<Face>, rotation: Int) {
        val w = proxy.width; val h = proxy.height
        val uw = if (rotation % 180 == 0) w else h
        val uh = if (rotation % 180 == 0) h else w
        val ts = proxy.imageInfo.timestamp

        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        if (face == null) {
            smooth = null
            listener(FrameSample(ts, false, 0.0, 0.0, 0.0, emptyList(), uw, uh))
            return
        }

        val bb = RectF(face.boundingBox)
        val s = smooth?.let {
            RectF(
                it.left + emaAlpha * (bb.left - it.left),
                it.top + emaAlpha * (bb.top - it.top),
                it.right + emaAlpha * (bb.right - it.right),
                it.bottom + emaAlpha * (bb.bottom - it.bottom),
            )
        } ?: bb
        smooth = s

        val fw = s.width(); val fh = s.height()
        val rois = listOf(
            // 이마
            RectF(s.left + 0.30f * fw, s.top + 0.08f * fh, s.right - 0.30f * fw, s.top + 0.22f * fh),
            // 왼쪽 볼 / 오른쪽 볼
            RectF(s.left + 0.15f * fw, s.top + 0.50f * fh, s.left + 0.38f * fw, s.top + 0.68f * fh),
            RectF(s.right - 0.38f * fw, s.top + 0.50f * fh, s.right - 0.15f * fw, s.top + 0.68f * fh),
        )

        val acc = DoubleArray(5) // sumY, nY, sumU, sumV, nUV
        for (roi in rois) accumulate(proxy, toBuffer(roi, rotation, w, h), acc)
        if (acc[1] < 50 || acc[4] < 10) {
            listener(FrameSample(ts, false, 0.0, 0.0, 0.0, emptyList(), uw, uh))
            return
        }
        val y = acc[0] / acc[1]
        val u = acc[2] / acc[4] - 128.0
        val v = acc[3] / acc[4] - 128.0
        val r = y + 1.402 * v
        val g = y - 0.344136 * u - 0.714136 * v
        val b = y + 1.772 * u
        listener(FrameSample(ts, true, r, g, b, rois, uw, uh))
    }

    /** upright 좌표 사각형 → 센서 버퍼 좌표 사각형 */
    private fun toBuffer(rc: RectF, rotation: Int, w: Int, h: Int): Rect {
        fun map(ux: Float, uy: Float): Pair<Float, Float> = when (rotation) {
            90 -> uy to (h - 1 - ux)
            180 -> (w - 1 - ux) to (h - 1 - uy)
            270 -> (w - 1 - uy) to ux
            else -> ux to uy
        }
        val (x1, y1) = map(rc.left, rc.top)
        val (x2, y2) = map(rc.right, rc.bottom)
        return Rect(
            minOf(x1, x2).toInt().coerceIn(0, w - 1),
            minOf(y1, y2).toInt().coerceIn(0, h - 1),
            maxOf(x1, x2).toInt().coerceIn(1, w),
            maxOf(y1, y2).toInt().coerceIn(1, h),
        )
    }

    private fun accumulate(proxy: ImageProxy, rc: Rect, acc: DoubleArray) {
        val yp = proxy.planes[0]; val up = proxy.planes[1]; val vp = proxy.planes[2]
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        val yRow = yp.rowStride; val yPix = yp.pixelStride
        val uRow = up.rowStride; val uPix = up.pixelStride
        val vRow = vp.rowStride; val vPix = vp.pixelStride

        var sy = 0L; var ny = 0L
        var yy = rc.top
        while (yy < rc.bottom) {
            var xx = rc.left
            val base = yy * yRow
            while (xx < rc.right) {
                sy += yb.get(base + xx * yPix).toInt() and 0xFF
                ny++
                xx += 2
            }
            yy += 2
        }
        var su = 0L; var sv = 0L; var nuv = 0L
        for (cy in rc.top / 2 until rc.bottom / 2) {
            for (cx in rc.left / 2 until rc.right / 2) {
                su += ub.get(cy * uRow + cx * uPix).toInt() and 0xFF
                sv += vb.get(cy * vRow + cx * vPix).toInt() and 0xFF
                nuv++
            }
        }
        acc[0] += sy.toDouble(); acc[1] += ny.toDouble()
        acc[2] += su.toDouble(); acc[3] += sv.toDouble(); acc[4] += nuv.toDouble()
    }

    fun close() = detector.close()
}
