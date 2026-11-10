package com.dku.malnote.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 녹음 저장소. filesDir/recordings/<id>/ 아래에 오디오 파일과 meta.json 을 둔다.
 */
object RecordingRepository {
    private lateinit var root: File
    private val _items = MutableStateFlow<List<Recording>>(emptyList())
    val items: StateFlow<List<Recording>> = _items.asStateFlow()

    fun init(context: Context) {
        root = File(context.filesDir, "recordings").apply { mkdirs() }
        val loaded = root.listFiles()?.mapNotNull { dir ->
            runCatching { fromJson(JSONObject(File(dir, "meta.json").readText())) }.getOrNull()
        }.orEmpty().map {
            // 앱이 강제 종료되어 '진행 중' 으로 남은 항목은 대기열로 되돌린다
            if (it.status == TranscriptStatus.RUNNING) it.copy(status = TranscriptStatus.QUEUED) else it
        }
        _items.value = loaded.sortedByDescending { it.createdAt }
    }

    fun get(id: String): Recording? = _items.value.firstOrNull { it.id == id }

    fun dirOf(id: String): File = File(root, id)

    fun audioFile(rec: Recording): File = File(dirOf(rec.id), rec.audioFileName)

    /** 새 녹음 폴더를 만들고 (아직 저장되지 않은) Recording 을 반환 */
    fun newRecording(title: String, audioFileName: String): Recording {
        val id = UUID.randomUUID().toString()
        dirOf(id).mkdirs()
        return Recording(
            id = id, title = title, createdAt = System.currentTimeMillis(),
            durationMs = 0, audioFileName = audioFileName,
        )
    }

    @Synchronized
    fun save(rec: Recording) {
        val dir = dirOf(rec.id).apply { mkdirs() }
        val tmp = File(dir, "meta.json.tmp")
        tmp.writeText(toJson(rec).toString())
        tmp.renameTo(File(dir, "meta.json"))
        _items.value = (_items.value.filterNot { it.id == rec.id } + rec).sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun update(id: String, transform: (Recording) -> Recording): Recording? {
        val cur = get(id) ?: return null
        return transform(cur).also { save(it) }
    }

    @Synchronized
    fun delete(id: String) {
        dirOf(id).deleteRecursively()
        _items.value = _items.value.filterNot { it.id == id }
    }

    private fun toJson(r: Recording) = JSONObject().apply {
        put("id", r.id)
        put("title", r.title)
        put("createdAt", r.createdAt)
        put("durationMs", r.durationMs)
        put("audioFileName", r.audioFileName)
        put("status", r.status.name)
        put("modelName", r.modelName)
        put("error", r.error)
        put("transcribeTimeMs", r.transcribeTimeMs)
        put("segments", JSONArray().apply {
            r.segments.forEach { s ->
                put(JSONObject().put("s", s.startMs).put("e", s.endMs).put("t", s.text))
            }
        })
    }

    private fun fromJson(o: JSONObject): Recording {
        val segs = o.optJSONArray("segments") ?: JSONArray()
        return Recording(
            id = o.getString("id"),
            title = o.optString("title"),
            createdAt = o.optLong("createdAt"),
            durationMs = o.optLong("durationMs"),
            audioFileName = o.getString("audioFileName"),
            status = runCatching { TranscriptStatus.valueOf(o.optString("status")) }.getOrDefault(TranscriptStatus.NONE),
            modelName = o.optString("modelName").takeIf { it.isNotEmpty() && it != "null" },
            error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
            transcribeTimeMs = o.optLong("transcribeTimeMs"),
            segments = (0 until segs.length()).map {
                val s = segs.getJSONObject(it)
                Segment(s.getLong("s"), s.getLong("e"), s.getString("t"))
            },
        )
    }
}
