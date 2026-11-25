package com.lim.rppg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PointF
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var signalView: SignalView
    private lateinit var compareChart: CompareChartView
    private lateinit var bpmText: TextView
    private lateinit var bpmSub: TextView
    private lateinit var watchText: TextView
    private lateinit var watchSub: TextView
    private lateinit var stressText: TextView
    private lateinit var stressSub: TextView
    private lateinit var stressBar: ProgressBar
    private lateinit var qualityText: TextView
    private lateinit var constitutionText: TextView
    private lateinit var compareText: TextView
    private lateinit var statusText: TextView
    private lateinit var sessionProgress: ProgressBar
    private lateinit var startButton: Button

    private lateinit var cameraExecutor: ExecutorService
    private var analyzer: RppgAnalyzer? = null
    private var camera: Camera? = null

    private val processor = RppgProcessor()
    private val recorder = SessionRecorder()
    private val recentBpm = ArrayDeque<Double>()   // 카메라 스레드 전용

    @Volatile private var measuring = false
    @Volatile private var durationSec = 60
    @Volatile private var shownBpm: Double? = null
    private var lastEstimateMs = 0L
    private var lastHrvMs = 0L
    private var sessionStartWall = 0L

    // UI 스레드 전용
    private val rppgSeries = ArrayList<PointF>()
    private val watchSeries = ArrayList<PointF>()
    private var watch: WatchSource? = null
    private var lastHrv: HrvResult? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusText.text = "카메라 권한이 필요합니다"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        signalView = findViewById(R.id.signalView)
        compareChart = findViewById(R.id.compareChart)
        bpmText = findViewById(R.id.bpmText); bpmSub = findViewById(R.id.bpmTextSub)
        watchText = findViewById(R.id.watchText); watchSub = findViewById(R.id.watchTextSub)
        stressText = findViewById(R.id.stressText); stressSub = findViewById(R.id.stressTextSub)
        stressBar = findViewById(R.id.stressBar)
        qualityText = findViewById(R.id.qualityText)
        constitutionText = findViewById(R.id.constitutionText)
        compareText = findViewById(R.id.compareText)
        statusText = findViewById(R.id.statusText)
        sessionProgress = findViewById(R.id.sessionProgress)
        startButton = findViewById(R.id.startButton)
        cameraExecutor = Executors.newSingleThreadExecutor()

        startButton.setOnClickListener { if (measuring) userStop() else startMeasuring() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (!prefs.profileReady) {
            AlertDialog.Builder(this)
                .setTitle("처음 오셨네요")
                .setMessage("측정 전에 메뉴(⋮) → 기본 설정에서 나이·성별을 입력해 주세요.\n워치 비교와 체질 진단도 메뉴에서 설정할 수 있습니다.")
                .setPositiveButton("설정하기") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("나중에", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetupState()
    }

    // ---------- 메뉴 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val target = when (item.itemId) {
            R.id.menu_settings -> SettingsActivity::class.java
            R.id.menu_watch -> WatchActivity::class.java
            R.id.menu_constitution -> ConstitutionActivity::class.java
            R.id.menu_privacy -> PrivacyActivity::class.java
            else -> return super.onOptionsItemSelected(item)
        }
        if (measuring && target != PrivacyActivity::class.java) {
            Toast.makeText(this, "측정 중에는 설정을 바꿀 수 없습니다", Toast.LENGTH_SHORT).show()
            return true
        }
        startActivity(Intent(this, target))
        return true
    }

    /** 설정 상태에 따라 버튼 잠금/안내 문구 갱신 */
    private fun refreshSetupState() {
        if (measuring) return
        durationSec = prefs.durationSec
        startButton.isEnabled = prefs.profileReady
        statusText.text = if (prefs.profileReady)
            "얼굴을 점선 안에 맞추고 측정 시작 (${durationSec}초)"
        else
            "메뉴(⋮) → 기본 설정을 먼저 완료하세요"
        watchSub.text = prefs.watchLabel
        val s = prefs.sasang
        constitutionText.text = if (s == null) "체질: 미진단 — 메뉴(⋮) → 체질 진단"
        else "체질: ${s.label} (설문 기반)\n${s.stressTip}"
    }

    // ---------- 카메라 ----------

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            val analysis = analysisBuilder.build()
            val a = RppgAnalyzer(cameraExecutor, ::onFrame)
            analyzer = a
            analysis.setAnalyzer(cameraExecutor, a)
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun lockAutoExposure(lock: Boolean) {
        val cam = camera ?: return
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
                .build()
        )
    }

    // ---------- 측정 세션 ----------

    private fun startMeasuring() {
        if (!prefs.profileReady) { refreshSetupState(); return }
        durationSec = prefs.durationSec
        processor.reset()
        cameraExecutor.execute { recentBpm.clear() }
        shownBpm = null
        lastHrv = null
        rppgSeries.clear(); watchSeries.clear()
        sessionStartWall = System.currentTimeMillis()
        recorder.reset(sessionStartWall)
        signalView.clear()
        compareChart.setData(rppgSeries, watchSeries, durationSec.toFloat())
        bpmText.text = "--"; watchText.text = "--"; stressText.text = "--"
        stressSub.text = "30초 후 계산"; stressBar.progress = 0
        stressText.setTextColor(0xFF66FFB3.toInt())
        qualityText.text = ""; compareText.text = ""
        sessionProgress.progress = 0
        lockAutoExposure(true)
        startWatch()
        measuring = true
        startButton.text = getString(R.string.stop)
    }

    private fun stopMeasuring(message: String) {
        measuring = false
        watch?.stop(); watch = null
        lockAutoExposure(false)
        startButton.text = getString(R.string.start)
        refreshSetupState()
        statusText.text = message
    }

    private fun startWatch() {
        val src: WatchSource? = when (prefs.watchMode) {
            "ble" -> prefs.bleAddress.takeIf { it.isNotEmpty() }?.let { BleHrSource(applicationContext, it) }
            "hc" -> HealthConnectSource(applicationContext, lifecycleScope)
            else -> null
        }
        watch = src
        if (src == null) { watchSub.text = prefs.watchLabel; return }
        src.start(
            onHr = { t, bpm -> runOnUiThread { onWatchHr(t, bpm) } },
            onStatus = { s -> runOnUiThread { watchSub.text = s } },
        )
    }

    private fun onWatchHr(epochMs: Long, bpm: Int) {
        if (!measuring) return
        val x = (epochMs - sessionStartWall) / 1000f
        if (x < -5f) return  // 측정 시작 전 기록은 제외 (Health Connect 과거 데이터)
        watchText.text = bpm.toString()
        watchSeries.add(PointF(x.coerceAtLeast(0f), bpm.toFloat()))
        watchSeries.sortBy { it.x }
        compareChart.setData(rppgSeries, watchSeries, durationSec.toFloat())
        updateCompare()
    }

    /** rPPG 각 시점과 가장 가까운 워치 값을 짝지어 평균 절대 차이 계산 */
    /** @return (평균 절대 차이, 짝 개수) */
    private fun compareStats(): Pair<Double, Int> {
        val tol = if (prefs.watchMode == "hc") 30f else 3f
        var sum = 0.0; var n = 0
        for (p in rppgSeries) {
            val q = watchSeries.minByOrNull { abs(it.x - p.x) } ?: continue
            if (abs(q.x - p.x) <= tol) { sum += abs(q.y - p.y); n++ }
        }
        return (if (n > 0) sum / n else Double.NaN) to n
    }

    private fun updateCompare() {
        if (watchSeries.isEmpty() || rppgSeries.isEmpty()) return
        val (mae, n) = compareStats()
        val sum = mae * n
        val now = shownBpm
        val lastW = watchSeries.last().y
        val cur = if (now != null) "현재 차이 %+.0f BPM".format(now - lastW) else ""
        compareText.text = if (n > 0) "$cur  ·  평균 절대 차이 %.1f BPM (n=%d)".format(sum / n, n) else cur
    }

    // ---------- 프레임 처리 (카메라 스레드) ----------

    private fun onFrame(f: FrameSample) {
        runOnUiThread { overlay.update(f.rois, f.uprightWidth, f.uprightHeight) }
        if (!measuring) return
        recorder.add(f)

        if (!f.faceFound) {
            processor.reset()
            runOnUiThread { if (measuring) statusText.text = "얼굴이 보이지 않습니다 — 처음부터 다시 수집" }
            return
        }
        processor.add(f.timestampNs, f.r, f.g, f.b)

        val now = SystemClock.elapsedRealtime()
        if (now - lastEstimateMs < 500) return
        lastEstimateMs = now

        val buffered = processor.bufferedSeconds()
        val dur = durationSec
        val progress = (buffered / dur * 100).toInt().coerceIn(0, 100)
        if (buffered < processor.minSec) {
            runOnUiThread {
                if (!measuring) return@runOnUiThread
                sessionProgress.progress = progress
                statusText.text = "신호 수집 중… 움직이지 마세요"
            }
            return
        }

        val res = processor.estimate() ?: return
        if (res.snrDb > -3.0) {
            recentBpm.addLast(res.bpm)
            while (recentBpm.size > 5) recentBpm.removeFirst()
        }
        val shown = recentBpm.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        shownBpm = shown

        var hrv: HrvResult? = null
        val finished = buffered >= dur
        if (shown != null && buffered >= Hrv.MIN_SEC && (finished || now - lastHrvMs > 5000)) {
            lastHrvMs = now
            hrv = processor.hrv(shown)
        }

        runOnUiThread {
            if (!measuring) return@runOnUiThread
            sessionProgress.progress = progress
            statusText.text = "측정 중 %d / %d초 — 편하게 호흡하세요".format(buffered.toInt(), dur)
            shown?.let {
                bpmText.text = "%.0f".format(it)
                rppgSeries.add(PointF((System.currentTimeMillis() - sessionStartWall) / 1000f, it.toFloat()))
                compareChart.setData(rppgSeries, watchSeries, dur.toFloat())
                updateCompare()
            }
            bpmSub.text = "SNR %.1f dB".format(res.snrDb)
            qualityText.text = when {
                res.snrDb >= 3.0 -> "신호 품질 좋음"
                res.snrDb >= 0.0 -> "신호 품질 보통"
                else -> "신호 품질 낮음 — 조명을 밝게, 움직임 최소화"
            }
            signalView.setData(res.waveform)
            hrv?.let { showStress(it) }
            if (finished) finishSession()
        }
    }

    private fun showStress(h: HrvResult) {
        lastHrv = h
        if (!h.reliable) {
            stressText.text = "--"
            stressSub.text = "신호 불충분 (%d%%)".format((h.beatYield * 100).toInt())
            return
        }
        stressText.text = h.score.toString()
        stressText.setTextColor(h.level.color)
        stressSub.text = h.level.label
        stressBar.progress = h.score
        stressBar.progressTintList = ColorStateList.valueOf(h.level.color)
    }

    private fun finishSession() {
        stopMeasuring("측정 완료")
        val sb = StringBuilder()
        shownBpm?.let { sb.append("rPPG 심박: %.0f BPM\n".format(it)) }
        if (watchSeries.isNotEmpty()) {
            sb.append("워치 심박(평균): %.0f BPM\n".format(watchSeries.map { it.y }.average()))
            if (compareText.text.isNotEmpty()) sb.append("${compareText.text}\n")
        }
        val h = lastHrv
        sb.append("\n")
        if (h == null) {
            sb.append("스트레스: 계산할 데이터가 부족합니다 (얼굴을 놓치지 않고 30초 이상 필요)\n")
        } else if (!h.reliable) {
            sb.append("스트레스: 신호가 불충분해 표시하지 않습니다 (박동 검출률 %d%%).\n밝은 조명에서 움직이지 말고 다시 측정해 보세요.\n"
                .format((h.beatYield * 100).toInt()))
        } else {
            sb.append("스트레스 지수: ${h.score} / 100 (${h.level.label})\n")
            sb.append("Baevsky √SI %.1f · RMSSD %.0f ms · SDNN %.0f ms\n".format(h.stressIndex, h.rmssdMs, h.sdnnMs))
            sb.append("분석 박동 ${h.beats}회 / %.0f초\n".format(h.durationSec))
        }
        prefs.sasang?.let { sb.append("\n${it.label} 스트레스 관리: ${it.stressTip}") }
        sb.append("\n\n※ 카메라 HRV는 잡음 때문에 변동성이 실제보다 크게 나올 수 있는 참고용 추정치입니다.")

        val auto = prefs.autoSave
        if (auto) saveSession(silent = true)?.let { sb.append("\n\n💾 저장됨: $it") }
        val dlg = AlertDialog.Builder(this)
            .setTitle("측정 결과")
            .setMessage(sb.toString())
            .setPositiveButton("확인", null)
        if (!auto) dlg.setNeutralButton("저장") { _, _ -> saveSession(silent = false) }
        dlg.show()
    }

    /** 측정 버튼으로 중간에 멈춘 경우: 5초 이상 수집했으면 저장 여부를 물음 */
    private fun userStop() {
        stopMeasuring("측정을 중지했습니다")
        if (recorder.durationSec() < 5.0) return
        AlertDialog.Builder(this)
            .setTitle("측정 중지")
            .setMessage("지금까지 %.0f초 분량의 데이터를 저장할까요?".format(recorder.durationSec()))
            .setPositiveButton("저장") { _, _ -> saveSession(silent = false) }
            .setNegativeButton("저장 안 함", null)
            .show()
    }

    /** raw/hr/summary 3개 파일 저장. @return 저장 위치 또는 실패 시 null */
    private fun saveSession(silent: Boolean): String? {
        val base = SessionStore.baseName(sessionStartWall)
        val files = listOf(
            "${base}_raw.csv" to recorder.rawCsv(),
            "${base}_hr.csv" to SessionStore.hrCsv(rppgSeries, watchSeries),
            "${base}_summary.txt" to summaryText(),
        )
        return try {
            val where = SessionStore.save(this, files)
            if (!silent) Toast.makeText(this, "저장됨: $where/${base}_*", Toast.LENGTH_LONG).show()
            "$where/${base}_*"
        } catch (e: Exception) {
            Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    /** MATLAB에서 읽기 쉬운 key=value 요약 */
    private fun summaryText(): String {
        val h = lastHrv
        val (mae, n) = if (watchSeries.isNotEmpty()) compareStats() else (Double.NaN to 0)
        fun d(v: Double?, fmt: String = "%.2f") = if (v == null || v.isNaN()) "NaN" else String.format(java.util.Locale.US, fmt, v)
        val lines = listOf(
            "app_version" to "2.1",
            "start_time" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(java.util.Date(sessionStartWall)),
            "duration_setting_s" to durationSec.toString(),
            "recorded_s" to d(recorder.durationSec()),
            "frames" to recorder.frames.toString(),
            "fs_resample_hz" to "30",
            "age" to prefs.age.toString(),
            "sex" to prefs.sex,
            "height_cm" to prefs.heightCm.toString(),
            "weight_kg" to prefs.weightKg.toString(),
            "rppg_bpm" to d(shownBpm, "%.1f"),
            "watch_mode" to prefs.watchMode,
            "watch_device" to (if (prefs.watchMode == "ble") prefs.bleName.ifEmpty { prefs.bleAddress } else ""),
            "watch_bpm_mean" to d(watchSeries.takeIf { it.isNotEmpty() }?.map { it.y.toDouble() }?.average(), "%.1f"),
            "watch_mae_bpm" to d(mae),
            "watch_pairs" to n.toString(),
            "stress_score" to (h?.score?.toString() ?: "NaN"),
            "stress_level" to (h?.level?.name ?: ""),
            "stress_reliable" to (h?.reliable?.toString() ?: "false"),
            "sqrt_si" to d(h?.stressIndex),
            "rmssd_ms" to d(h?.rmssdMs, "%.1f"),
            "sdnn_ms" to d(h?.sdnnMs, "%.1f"),
            "beat_yield" to d(h?.beatYield, "%.3f"),
            "beats" to (h?.beats?.toString() ?: "0"),
            "constitution" to (prefs.sasang?.label ?: ""),
            "constitution_pct_ty_sy_te_se" to prefs.constitutionPct,
        )
        return lines.joinToString("\n") { "${it.first}=${it.second}" } + "\n"
    }

    override fun onDestroy() {
        super.onDestroy()
        watch?.stop()
        analyzer?.close()
        cameraExecutor.shutdown()
    }
}
