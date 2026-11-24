package com.lim.rppg

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var signalView: SignalView
    private lateinit var bpmText: TextView
    private lateinit var qualityText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    private lateinit var cameraExecutor: ExecutorService
    private var analyzer: RppgAnalyzer? = null
    private var camera: Camera? = null

    private val processor = RppgProcessor()
    private val recentBpm = ArrayDeque<Double>()

    @Volatile private var measuring = false
    private var lastEstimateMs = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusText.setText(R.string.permission_denied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        signalView = findViewById(R.id.signalView)
        bpmText = findViewById(R.id.bpmText)
        qualityText = findViewById(R.id.qualityText)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        cameraExecutor = Executors.newSingleThreadExecutor()

        startButton.setOnClickListener { if (measuring) stopMeasuring() else startMeasuring() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

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
            // 30fps 고정 요청 (가변 프레임레이트는 재샘플링으로 보정하지만 일정할수록 좋음)
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

    /** 측정 중에는 자동노출/화이트밸런스를 잠가 밝기 변화가 맥파로 오인되지 않게 함 */
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

    private fun startMeasuring() {
        processor.reset()
        recentBpm.clear()
        signalView.clear()
        bpmText.setText(R.string.bpm_placeholder)
        qualityText.text = ""
        lockAutoExposure(true)
        measuring = true
        startButton.setText(R.string.stop)
    }

    private fun stopMeasuring() {
        measuring = false
        lockAutoExposure(false)
        startButton.setText(R.string.start)
        statusText.setText(R.string.status_idle)
    }

    /** 카메라 스레드에서 호출됨 */
    private fun onFrame(f: FrameSample) {
        runOnUiThread { overlay.update(f.rois, f.uprightWidth, f.uprightHeight) }
        if (!measuring) return

        if (!f.faceFound) {
            processor.reset()
            runOnUiThread { statusText.setText(R.string.status_no_face) }
            return
        }
        processor.add(f.timestampNs, f.r, f.g, f.b)

        val now = SystemClock.elapsedRealtime()
        if (now - lastEstimateMs < 500) return
        lastEstimateMs = now

        val buffered = processor.bufferedSeconds()
        if (buffered < processor.minSec) {
            val pct = (buffered / processor.minSec * 100).toInt().coerceIn(0, 99)
            runOnUiThread { statusText.text = getString(R.string.status_collecting, pct) }
            return
        }

        val res = processor.estimate() ?: return
        // SNR이 너무 낮은 추정치는 표시 BPM에 반영하지 않고, 최근 5개 중앙값으로 안정화
        if (res.snrDb > -3.0) {
            recentBpm.addLast(res.bpm)
            while (recentBpm.size > 5) recentBpm.removeFirst()
        }
        val shown = recentBpm.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

        runOnUiThread {
            if (!measuring) return@runOnUiThread
            statusText.setText(R.string.status_measuring)
            shown?.let { bpmText.text = String.format("%.0f", it) }
            qualityText.text = when {
                res.snrDb >= 3.0 -> getString(R.string.quality_good, res.snrDb)
                res.snrDb >= 0.0 -> getString(R.string.quality_fair, res.snrDb)
                else -> getString(R.string.quality_poor, res.snrDb)
            }
            signalView.setData(res.waveform)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer?.close()
        cameraExecutor.shutdown()
    }
}
