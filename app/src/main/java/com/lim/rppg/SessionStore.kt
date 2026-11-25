package com.lim.rppg

import android.content.ContentValues
import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 측정 중 모든 프레임의 ROI 평균 RGB를 기록 (MATLAB 재분석용 원시 데이터).
 * 카메라 스레드에서 add, UI 스레드에서 읽기 → synchronized
 */
class SessionRecorder {
    private val sb = StringBuilder()
    private var firstNs = -1L
    private var startWall = 0L
    private var lastT = 0.0
    var frames = 0
        private set

    @Synchronized
    fun reset(startWallMs: Long) {
        sb.setLength(0); firstNs = -1L; startWall = startWallMs; lastT = 0.0; frames = 0
    }

    @Synchronized
    fun add(f: FrameSample) {
        if (firstNs < 0) firstNs = f.timestampNs
        val t = (f.timestampNs - firstNs) / 1e9
        val w = (System.currentTimeMillis() - startWall) / 1000.0
        if (f.faceFound) {
            sb.append(String.format(Locale.US, "%.4f,%.3f,%.4f,%.4f,%.4f,1\n", t, w, f.r, f.g, f.b))
        } else {
            sb.append(String.format(Locale.US, "%.4f,%.3f,0,0,0,0\n", t, w))
        }
        lastT = t; frames++
    }

    @Synchronized
    fun durationSec(): Double = lastT

    @Synchronized
    fun rawCsv(): String = "t_s,wall_s,R,G,B,face\n$sb"
}

/** 휴대폰 '다운로드/rPPG' 폴더에 세션 파일 저장 */
object SessionStore {
    const val FOLDER = "rPPG"

    fun baseName(startWallMs: Long): String =
        "rppg_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startWallMs))

    /** rPPG 표시값(source=1)과 워치(source=2) 심박 시계열 */
    fun hrCsv(rppg: List<PointF>, watch: List<PointF>): String {
        val sb = StringBuilder("t_s,bpm,source\n")
        for (p in rppg) sb.append(String.format(Locale.US, "%.3f,%.2f,1\n", p.x, p.y))
        for (p in watch) sb.append(String.format(Locale.US, "%.3f,%.0f,2\n", p.x, p.y))
        return sb.toString()
    }

    /** @return 사용자에게 보여줄 저장 위치 */
    @Throws(IOException::class)
    fun save(ctx: Context, files: List<Pair<String, String>>): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            for ((name, text) in files) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".csv")) "text/csv" else "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("파일을 만들 수 없습니다: $name")
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: throw IOException("파일에 쓸 수 없습니다: $name")
            }
            "다운로드/$FOLDER"
        } else {
            // Android 9 이하: 권한 없이 쓸 수 있는 앱 전용 외부 폴더
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
            for ((name, text) in files) File(dir, name).writeText(text, Charsets.UTF_8)
            dir.absolutePath
        }
    }
}
