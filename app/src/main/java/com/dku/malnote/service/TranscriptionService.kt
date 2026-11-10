package com.dku.malnote.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dku.malnote.MainActivity
import com.dku.malnote.MalNoteApp
import com.dku.malnote.R
import com.dku.malnote.audio.AudioDecoder
import com.dku.malnote.data.ModelManager
import com.dku.malnote.data.RecordingRepository
import com.dku.malnote.data.Segment
import com.dku.malnote.data.SettingsStore
import com.dku.malnote.data.TranscriptStatus
import com.dku.malnote.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 현재 전사 중인 항목의 실시간 상태 */
data class LiveTranscription(
    val recordingId: String,
    val phase: String,
    val progress: Int = 0,
    val segments: List<Segment> = emptyList(),
)

/**
 * whisper.cpp 로 온디바이스 전사를 수행하는 포그라운드 서비스.
 * 대기열에 들어온 녹음을 순서대로 처리하며, 모델은 한 번 로드하면 재사용한다.
 */
class TranscriptionService : Service() {

    companion object {
        private const val TAG = "Transcription"
        private const val ACTION_ENQUEUE = "enqueue"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_ID = "id"
        private const val NOTI_ID = 2001

        private val _live = MutableStateFlow<LiveTranscription?>(null)
        val live: StateFlow<LiveTranscription?> = _live.asStateFlow()

        fun enqueue(ctx: Context, id: String) {
            RecordingRepository.update(id) {
                it.copy(status = TranscriptStatus.QUEUED, error = null, segments = emptyList())
            }
            startSafely(ctx, Intent(ctx, TranscriptionService::class.java).setAction(ACTION_ENQUEUE).putExtra(EXTRA_ID, id))
        }

        fun cancel(ctx: Context, id: String) {
            ctx.startService(Intent(ctx, TranscriptionService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id))
        }

        /** 앱이 켜질 때 남아있는 대기열을 다시 처리 */
        fun resumePending(ctx: Context) {
            RecordingRepository.items.value
                .filter { it.status == TranscriptStatus.QUEUED }
                .sortedBy { it.createdAt }
                .forEach {
                    startSafely(ctx, Intent(ctx, TranscriptionService::class.java).setAction(ACTION_ENQUEUE).putExtra(EXTRA_ID, it.id))
                }
        }

        private fun startSafely(ctx: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // 백그라운드에서는 시작할 수 없음 → QUEUED 로 남겨두고 앱 실행 시 재개
                Log.w(TAG, "deferred: ${e.message}")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ArrayDeque<String>()
    private var worker: Job? = null
    private var whisper: WhisperContext? = null
    @Volatile private var currentId: String? = null
    @Volatile private var cancelRequested = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                startForeground(NOTI_ID, notification("전사 준비 중", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                if (id != null) synchronized(queue) {
                    if (id != currentId && id !in queue) queue.addLast(id)
                }
                if (worker?.isActive != true) worker = scope.launch { runQueue() }
            }
            ACTION_CANCEL -> if (id != null) {
                if (id == currentId) {
                    cancelRequested = true
                    whisper?.cancel()
                } else {
                    synchronized(queue) { queue.remove(id) }
                    RecordingRepository.update(id) { it.copy(status = TranscriptStatus.CANCELLED) }
                }
                if (worker?.isActive != true) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runQueue() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MalNote:transcribe").apply { acquire(6 * 60 * 60 * 1000L) }
        try {
            while (true) {
                val id = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                process(id)
            }
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
            _live.value = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun process(id: String) {
        val rec = RecordingRepository.get(id) ?: return
        currentId = id
        cancelRequested = false
        val settings = SettingsStore.state.value
        val started = SystemClock.elapsedRealtime()
        val segments = mutableListOf<Segment>()
        try {
            val model = ModelManager.activeModel()
                ?: throw IllegalStateException("설치된 모델이 없습니다. 설정에서 모델을 내려받아 주세요.")
            RecordingRepository.update(id) { it.copy(status = TranscriptStatus.RUNNING, error = null, modelName = model.name) }

            setPhase(id, "오디오 디코딩 중", 0, segments)
            val pcm = AudioDecoder.decode(RecordingRepository.audioFile(rec))
            if (rec.durationMs == 0L) RecordingRepository.update(id) { it.copy(durationMs = pcm.durationMs) }
            if (cancelRequested) throw CancelledException()

            val ctx = whisper?.takeIf { it.modelPath == model.absolutePath } ?: run {
                whisper?.close()
                whisper = null
                setPhase(id, "모델 불러오는 중 (${model.name})", 0, segments)
                WhisperContext.load(model.absolutePath).also { whisper = it }
            }
            if (cancelRequested) throw CancelledException()

            setPhase(id, "전사 중", 0, segments)
            var lastNoti = 0L
            val rc = ctx.transcribe(
                pcm.data, pcm.length,
                language = settings.language,
                threads = settings.threads,
                beamSize = settings.beamSize,
                prompt = settings.prompt.takeIf { it.isNotBlank() },
                onProgress = { p ->
                    _live.value = _live.value?.copy(progress = p)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNoti > 1000) {
                        lastNoti = now
                        notify(notification("전사 중: ${rec.title}", p))
                    }
                },
                onSegment = { s, e, text ->
                    if (text.isNotBlank()) {
                        segments += Segment(s, e, text.trim())
                        _live.value = _live.value?.copy(segments = segments.toList())
                    }
                },
            )
            if (cancelRequested || rc == -1) throw CancelledException()
            if (rc != 0) throw IllegalStateException("whisper 오류 코드 $rc")

            val elapsed = SystemClock.elapsedRealtime() - started
            RecordingRepository.update(id) {
                it.copy(status = TranscriptStatus.DONE, segments = segments.toList(), transcribeTimeMs = elapsed)
            }
        } catch (e: CancelledException) {
            RecordingRepository.update(id) { it.copy(status = TranscriptStatus.CANCELLED, segments = segments.toList()) }
        } catch (t: Throwable) {
            Log.e(TAG, "transcription failed", t)
            RecordingRepository.update(id) {
                it.copy(status = TranscriptStatus.FAILED, error = t.message ?: t.javaClass.simpleName, segments = segments.toList())
            }
        } finally {
            currentId = null
        }
    }

    private fun setPhase(id: String, phase: String, progress: Int, segments: List<Segment>) {
        _live.value = LiveTranscription(id, phase, progress, segments.toList())
        notify(notification(phase, progress))
    }

    private class CancelledException : RuntimeException()

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15: dataSync 포그라운드 서비스 시간 제한 도달
        cancelRequested = true
        whisper?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        whisper?.cancel()
        scope.cancel()
        // 네이티브 컨텍스트는 전사 스레드가 끝난 뒤 해제
        val w = whisper
        whisper = null
        if (w != null) Thread { worker?.let { while (it.isActive) Thread.sleep(50) }; w.close() }.start()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notify(n: Notification) = getSystemService(NotificationManager::class.java).notify(NOTI_ID, n)

    private fun notification(text: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MalNoteApp.CH_TRANSCRIBE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("음성 전사")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(open)
            .build()
    }
}
