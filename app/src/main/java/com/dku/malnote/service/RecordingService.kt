package com.dku.malnote.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dku.malnote.MainActivity
import com.dku.malnote.MalNoteApp
import com.dku.malnote.R
import com.dku.malnote.audio.WavWriter
import com.dku.malnote.data.Recording
import com.dku.malnote.data.RecordingRepository
import com.dku.malnote.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

data class RecorderState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val recordingId: String? = null,
    val elapsedMs: Long = 0,
    val levels: List<Float> = emptyList(),
    val lastFinishedId: String? = null,
)

/** 화면이 꺼져도 녹음이 계속되도록 하는 마이크 포그라운드 서비스 (16kHz mono WAV) */
class RecordingService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val NOTI_ID = 1001
        const val SAMPLE_RATE = 16000

        private val _state = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> = _state.asStateFlow()

        fun start(ctx: Context) = send(ctx, ACTION_START, foreground = true)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)
        fun pause(ctx: Context) = send(ctx, ACTION_PAUSE)
        fun resume(ctx: Context) = send(ctx, ACTION_RESUME)

        private fun send(ctx: Context, action: String, foreground: Boolean = false) {
            val i = Intent(ctx, RecordingService::class.java).setAction(action)
            if (foreground) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
        }
    }

    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var current: Recording? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> { paused = true; _state.value = _state.value.copy(paused = true); updateNotification() }
            ACTION_RESUME -> { paused = false; _state.value = _state.value.copy(paused = false); updateNotification() }
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (running) return
        startForeground(NOTI_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val title = SimpleDateFormat("M월 d일 a h:mm 녹음", Locale.KOREAN).format(Date())
        val rec = RecordingRepository.newRecording(title, "audio.wav")
        current = rec
        running = true
        paused = false
        _state.value = RecorderState(active = true, recordingId = rec.id)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MalNote:record").apply { acquire(6 * 60 * 60 * 1000L) }

        val file = RecordingRepository.audioFile(rec)
        thread = Thread({ recordLoop(file) }, "recorder").apply { start() }
    }

    @SuppressLint("MissingPermission") // 권한은 UI 에서 확인 후 서비스를 시작한다
    private fun recordLoop(file: java.io.File) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2)
        )
        val chunk = ShortArray(SAMPLE_RATE / 10) // 100ms
        var written = 0L
        val levels = ArrayDeque<Float>()
        var lastUi = 0L
        WavWriter(file, SAMPLE_RATE).use { wav ->
            recorder.startRecording()
            try {
                while (running) {
                    val n = recorder.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    if (paused) continue
                    wav.write(chunk, n)
                    written += n
                    var sum = 0.0
                    for (i in 0 until n) sum += chunk[i] * chunk[i].toDouble()
                    val rms = sqrt(sum / n) / 32768.0
                    val db = if (rms > 0) 20 * log10(rms) else -90.0
                    levels.addLast(((db + 60) / 60).toFloat().coerceIn(0f, 1f))
                    while (levels.size > 120) levels.removeFirst()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi > 90) {
                        lastUi = now
                        _state.value = _state.value.copy(
                            elapsedMs = written * 1000 / SAMPLE_RATE,
                            levels = levels.toList(),
                        )
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
            }
        }
        current = current?.copy(durationMs = written * 1000 / SAMPLE_RATE)
    }

    private fun stopRecording() {
        if (!running) { stopSelf(); return }
        running = false
        thread?.join(3000)
        thread = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        val rec = current
        current = null
        if (rec != null) {
            RecordingRepository.save(rec)
            if (SettingsStore.state.value.autoTranscribe && rec.durationMs > 500) {
                TranscriptionService.enqueue(this, rec.id)
            }
        }
        _state.value = RecorderState(lastFinishedId = rec?.id)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopRecording()
        super.onDestroy()
    }

    private fun updateNotification() {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTI_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, RecordingService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MalNoteApp.CH_RECORD)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (paused) "녹음 일시정지됨" else "녹음 중")
            .setContentText("탭하여 말노트 열기")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "계속" else "일시정지", toggle)
            .addAction(0, "중지", stop)
            .build()
    }
}
