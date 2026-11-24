package com.lim.rppg

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class StressLevel(val label: String, val color: Int) {
    LOW("낮음 (이완)", 0xFF4FC3F7.toInt()),
    NORMAL("보통", 0xFF66FFB3.toInt()),
    ELEVATED("다소 높음", 0xFFFFC107.toInt()),
    HIGH("높음", 0xFFFF5252.toInt()),
}

data class HrvResult(
    val meanHr: Double,
    val sdnnMs: Double,
    val rmssdMs: Double,
    /** Baevsky 스트레스 지수의 제곱근 (Kubios 표기와 동일 척도) */
    val stressIndex: Double,
    val score: Int,
    val level: StressLevel,
    val beats: Int,
    val durationSec: Double,
    /** 기대 박동 수 대비 정상 RR 비율 (0–1) */
    val beatYield: Double,
    val reliable: Boolean,
)

/**
 * rPPG 맥파 → 박동 검출 → RR 간격 → HRV/스트레스.
 *
 * - 박동: 심박 ±0.6 Hz 협대역 맥파의 상향 영교차(선형 보간) + 최소 간격(현재 심박 주기의 60%)
 * - RR 정제: 0.33–1.5 s, 중앙값 ±25% 밖은 아티팩트로 제거
 * - Baevsky SI = AMo / (2·Mo·MxDMn), 50 ms 히스토그램.
 *   rPPG 잡음에 강하도록 MxDMn은 max−min 대신 5–95 백분위 범위 사용.
 * - 점수: √SI 5→0점, 20→100점 선형. 등급: √SI <7 낮음, <12 보통, <18 다소 높음, 그 이상 높음
 */
object Hrv {
    const val MIN_SEC = 30.0

    /** 이 비율 이상 박동이 깨끗하게 검출돼야 스트레스 수치를 신뢰 */
    const val MIN_YIELD = 0.85

    fun analyze(x: DoubleArray, fs: Double, hrHintBpm: Double): HrvResult? {
        val n = x.size
        if (n < MIN_SEC * fs) return null
        val hint = hrHintBpm.coerceIn(42.0, 180.0)
        val minDist = max(0.33, 0.6 * 60.0 / hint) * fs

        // 협대역 맥파의 상향 영교차(선형 보간) = 박동 위상 기준점. 피크보다 잡음에 강함
        val peaks = ArrayList<Double>()
        for (i in 1 until n) {
            val a = x[i - 1]; val b = x[i]
            if (a < 0 && b >= 0) {
                val pos = (i - 1) + (-a) / (b - a)
                if (peaks.isNotEmpty() && pos - peaks.last() < minDist) continue
                peaks.add(pos)
            }
        }
        if (peaks.size < 16) return null

        val rawRr = DoubleArray(peaks.size - 1) { (peaks[it + 1] - peaks[it]) / fs }
            .filter { it in 0.33..1.5 }
        if (rawRr.size < 15) return null
        val med = rawRr.sorted()[rawRr.size / 2]
        val rr = rawRr.filter { abs(it - med) <= 0.25 * med }
        if (rr.size < 15) return null

        val mean = rr.average()
        val sdnn = sqrt(rr.sumOf { (it - mean) * (it - mean) } / (rr.size - 1))
        var ss = 0.0
        for (i in 1 until rr.size) { val d = rr[i] - rr[i - 1]; ss += d * d }
        val rmssd = sqrt(ss / (rr.size - 1))

        // Baevsky
        val bin = 0.05
        val counts = HashMap<Int, Int>()
        for (v in rr) { val k = (v / bin).toInt(); counts[k] = (counts[k] ?: 0) + 1 }
        val modeEntry = counts.maxByOrNull { it.value }!!
        val mo = (modeEntry.key + 0.5) * bin
        val amo = modeEntry.value * 100.0 / rr.size
        val sorted = rr.sorted()
        val p5 = sorted[(0.05 * (sorted.size - 1)).roundToInt()]
        val p95 = sorted[(0.95 * (sorted.size - 1)).roundToInt()]
        val mxdmn = max(p95 - p5, 0.02)
        val si = sqrt(amo / (2 * mo * mxdmn))

        val score = ((si - 5.0) / 15.0 * 100).roundToInt().coerceIn(0, 100)
        val level = when {
            si < 7 -> StressLevel.LOW
            si < 12 -> StressLevel.NORMAL
            si < 18 -> StressLevel.ELEVATED
            else -> StressLevel.HIGH
        }
        val dur = n / fs
        val expected = dur * hint / 60.0
        val yieldRatio = (rr.size / expected).coerceAtMost(1.0)
        return HrvResult(60.0 / mean, sdnn * 1000, rmssd * 1000, si, score, level, rr.size + 1, dur,
            yieldRatio, yieldRatio >= MIN_YIELD)
    }
}
