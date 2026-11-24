package com.lim.rppg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

data class RppgResult(
    val bpm: Double,
    val snrDb: Double,
    /** 최근 몇 초의 필터링된 맥파(BVP), -1..1 정규화 */
    val waveform: FloatArray,
)

/**
 * rPPG 신호 처리기.
 *
 * 1) ROI 평균 RGB를 타임스탬프와 함께 버퍼링
 * 2) 불규칙한 프레임 간격을 고정 fs(30 Hz)로 선형 보간 재샘플링
 * 3) POS(Plane-Orthogonal-to-Skin, Wang et al. 2017)로 맥파 추출
 * 4) 0.7–3.0 Hz(42–180 BPM) 버터워스 대역통과 (zero-phase filtfilt)
 * 5) Hann 창 + 대역 내 DFT로 최대 피크 → BPM, 피크/고조파 대비 SNR 계산
 */
class RppgProcessor(
    private val fs: Double = 30.0,
    private val windowSec: Double = 10.0,
    val minSec: Double = 5.0,
) {
    private val t = ArrayList<Double>()
    private val r = ArrayList<Double>()
    private val g = ArrayList<Double>()
    private val b = ArrayList<Double>()

    private val loHz = 0.7
    private val hiHz = 3.0

    @Synchronized
    fun reset() {
        t.clear(); r.clear(); g.clear(); b.clear()
    }

    @Synchronized
    fun add(timestampNs: Long, rv: Double, gv: Double, bv: Double) {
        val ts = timestampNs / 1e9
        // 0.5초 이상 끊기면(얼굴 놓침·프레임 드랍) 새로 시작
        if (t.isNotEmpty() && (ts - t.last() > 0.5 || ts <= t.last())) reset()
        t.add(ts); r.add(rv); g.add(gv); b.add(bv)
        while (t.size > 2 && t.last() - t.first() > windowSec + 1.0) {
            t.removeAt(0); r.removeAt(0); g.removeAt(0); b.removeAt(0)
        }
    }

    @Synchronized
    fun bufferedSeconds(): Double = if (t.size < 2) 0.0 else t.last() - t.first()

    fun estimate(): RppgResult? {
        val ts: DoubleArray; val rs: DoubleArray; val gs: DoubleArray; val bs: DoubleArray
        synchronized(this) {
            if (bufferedSeconds() < minSec) return null
            ts = t.toDoubleArray(); rs = r.toDoubleArray(); gs = g.toDoubleArray(); bs = b.toDoubleArray()
        }
        val span = minOf(windowSec, ts.last() - ts.first())
        val n = floor(span * fs).toInt()
        if (n < (minSec * fs).toInt() - 1) return null
        val tStart = ts.last() - (n - 1) / fs

        val R = resample(ts, rs, tStart, n)
        val G = resample(ts, gs, tStart, n)
        val B = resample(ts, bs, tStart, n)

        var h = pos(R, G, B)
        h = filtfilt(h, Biquad.highPass(loHz, fs))
        h = filtfilt(h, Biquad.lowPass(hiHz, fs))

        val (f0, snr) = spectralPeak(h) ?: return null
        return RppgResult(f0 * 60.0, snr, waveform(h, 6.0))
    }

    // ---------- 단계별 구현 ----------

    private fun resample(ts: DoubleArray, v: DoubleArray, tStart: Double, n: Int): DoubleArray {
        val out = DoubleArray(n)
        var j = 0
        for (k in 0 until n) {
            val tk = tStart + k / fs
            while (j < ts.size - 2 && ts[j + 1] < tk) j++
            val t0 = ts[j]; val t1 = ts[j + 1]
            val a = if (t1 > t0) ((tk - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
            out[k] = v[j] + a * (v[j + 1] - v[j])
        }
        return out
    }

    /** POS: 1.6초 슬라이딩 창마다 시간 정규화 → 피부톤 직교 평면 투영 → 중첩 가산 */
    private fun pos(R: DoubleArray, G: DoubleArray, B: DoubleArray): DoubleArray {
        val n = R.size
        val l = (1.6 * fs).toInt()
        val H = DoubleArray(n)
        val s1 = DoubleArray(l); val s2 = DoubleArray(l)
        for (end in l - 1 until n) {
            val st = end - l + 1
            var mr = 0.0; var mg = 0.0; var mb = 0.0
            for (i in st..end) { mr += R[i]; mg += G[i]; mb += B[i] }
            mr /= l; mg /= l; mb /= l
            if (mr <= 0 || mg <= 0 || mb <= 0) continue
            for (i in 0 until l) {
                val cr = R[st + i] / mr; val cg = G[st + i] / mg; val cb = B[st + i] / mb
                s1[i] = cg - cb
                s2[i] = -2 * cr + cg + cb
            }
            val sd2 = std(s2)
            val alpha = if (sd2 > 1e-12) std(s1) / sd2 else 0.0
            var mh = 0.0
            for (i in 0 until l) { s1[i] = s1[i] + alpha * s2[i]; mh += s1[i] }
            mh /= l
            for (i in 0 until l) H[st + i] += s1[i] - mh
        }
        return H
    }

    /** Hann 창 + 0.5–4.0 Hz DFT. 피크는 0.7–3.0 Hz에서 찾고, SNR은 de Haan 방식(기본파±0.1Hz, 2고조파±0.2Hz) */
    private fun spectralPeak(x: DoubleArray): Pair<Double, Double>? {
        val n = x.size
        val w = DoubleArray(n) { x[it] * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }
        val step = 0.01
        val fMin = 0.5; val fMax = 4.0
        val bins = ((fMax - fMin) / step).toInt() + 1
        val freqs = DoubleArray(bins) { fMin + it * step }
        val pw = DoubleArray(bins)
        for (k in 0 until bins) {
            val om = 2 * PI * freqs[k] / fs
            var re = 0.0; var im = 0.0
            for (i in 0 until n) { re += w[i] * cos(om * i); im -= w[i] * sin(om * i) }
            pw[k] = re * re + im * im
        }
        var best = -1
        for (k in 0 until bins) {
            if (freqs[k] < loHz || freqs[k] > hiHz) continue
            if (best < 0 || pw[k] > pw[best]) best = k
        }
        if (best < 0 || pw[best] <= 0.0) return null
        // 포물선 보간으로 0.01 Hz 이하 정밀도
        var f0 = freqs[best]
        if (best in 1 until bins - 1) {
            val a = pw[best - 1]; val bb = pw[best]; val c = pw[best + 1]
            val d = a - 2 * bb + c
            if (abs(d) > 1e-20) f0 += step * (0.5 * (a - c) / d).coerceIn(-0.5, 0.5)
        }
        var sig = 0.0; var noise = 0.0
        for (k in 0 until bins) {
            val f = freqs[k]
            if (abs(f - f0) <= 0.1 || abs(f - 2 * f0) <= 0.2) sig += pw[k] else noise += pw[k]
        }
        val snr = if (noise > 0) 10 * log10(sig / noise) else 99.0
        return f0 to snr
    }

    private fun waveform(h: DoubleArray, sec: Double): FloatArray {
        val m = minOf(h.size, (sec * fs).toInt())
        val seg = h.copyOfRange(h.size - m, h.size)
        var mx = 1e-12
        for (v in seg) mx = maxOf(mx, abs(v))
        return FloatArray(m) { (seg[it] / mx).toFloat() }
    }

    private fun std(a: DoubleArray): Double {
        var m = 0.0
        for (v in a) m += v
        m /= a.size
        var s = 0.0
        for (v in a) s += (v - m) * (v - m)
        return sqrt(s / a.size)
    }

    private fun filtfilt(x: DoubleArray, bq: Biquad): DoubleArray {
        val y = bq.apply(x)
        y.reverse()
        val z = bq.apply(y)
        z.reverse()
        return z
    }

    /** RBJ cookbook 2차 버터워스 필터 */
    class Biquad(private val b0: Double, private val b1: Double, private val b2: Double,
                 private val a1: Double, private val a2: Double) {
        fun apply(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size)
            // 첫 샘플 값으로 초기 상태를 채워 시작 과도 응답을 줄임
            var x1 = x.firstOrNull() ?: 0.0; var x2 = x1
            val dc = (b0 + b1 + b2) / (1 + a1 + a2)
            var y1 = x1 * dc; var y2 = y1
            for (i in x.indices) {
                val v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x[i]; y2 = y1; y1 = v
                y[i] = v
            }
            return y
        }

        companion object {
            private const val Q = 0.7071067811865476
            fun lowPass(fc: Double, fs: Double): Biquad {
                val w0 = 2 * PI * fc / fs; val al = sin(w0) / (2 * Q); val c = cos(w0)
                val a0 = 1 + al
                return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
            }
            fun highPass(fc: Double, fs: Double): Biquad {
                val w0 = 2 * PI * fc / fs; val al = sin(w0) / (2 * Q); val c = cos(w0)
                val a0 = 1 + al
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
            }
        }
    }
}
