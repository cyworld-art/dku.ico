package com.dku.malnote.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val downloaded: Long, val total: Long) : ModelState {
        val fraction: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }
    data class Installed(val sizeBytes: Long) : ModelState
    data class Failed(val reason: String) : ModelState
}

/**
 * ggml 모델 파일 관리. 모델은 앱 전용 외부 저장소 (Android/data/<pkg>/files/models) 에 저장된다.
 * 다운로드는 시스템 DownloadManager 를 사용하므로 앱을 닫아도 계속 진행된다.
 */
object ModelManager {
    private const val PREFS = "models"
    private const val PART = ".download"
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    /** 카탈로그 + 사용자가 가져온 모델 파일 이름 */
    private val _installedCustom = MutableStateFlow<List<String>>(emptyList())
    val installedCustom: StateFlow<List<String>> = _installedCustom.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
        if (prefs().all.isNotEmpty()) startPolling()
    }

    fun modelsDir(): File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models").apply { mkdirs() }

    fun fileOf(fileName: String) = File(modelsDir(), fileName)

    fun isInstalled(fileName: String) = fileOf(fileName).let { it.exists() && it.length() > 0 }

    /** 현재 선택된 (없으면 설치된 것 중 추천 순) 모델 파일 */
    fun activeModel(): File? {
        val sel = SettingsStore.state.value.selectedModel
        if (sel != null && isInstalled(sel)) return fileOf(sel)
        val all = WhisperModel.CATALOG.map { it.fileName } + _installedCustom.value
        return all.firstOrNull { isInstalled(it) }?.let { fileOf(it) }
    }

    fun refresh() {
        val downloading = prefs().all.keys
        val catalogNames = WhisperModel.CATALOG.map { it.fileName }.toSet()
        _installedCustom.value = modelsDir().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".bin") && it.name !in catalogNames }
            ?.map { it.name }.orEmpty().sorted()
        val m = mutableMapOf<String, ModelState>()
        (catalogNames + _installedCustom.value).forEach { name ->
            val prev = _states.value[name]
            m[name] = when {
                name in downloading -> prev as? ModelState.Downloading ?: ModelState.Downloading(0, 0)
                isInstalled(name) -> ModelState.Installed(fileOf(name).length())
                prev is ModelState.Failed -> prev
                else -> ModelState.NotInstalled
            }
        }
        _states.value = m
    }

    fun download(model: WhisperModel) {
        val dm = appContext.getSystemService(DownloadManager::class.java)
        File(modelsDir(), model.fileName + PART).delete()
        val req = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Whisper 모델: ${model.displayName}")
            .setDescription(model.fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setDestinationInExternalFilesDir(appContext, null, "models/${model.fileName}$PART")
        val id = dm.enqueue(req)
        prefs().edit().putLong(model.fileName, id).apply()
        refresh()
        startPolling()
    }

    fun cancelDownload(fileName: String) {
        val id = prefs().getLong(fileName, -1)
        if (id >= 0) appContext.getSystemService(DownloadManager::class.java).remove(id)
        prefs().edit().remove(fileName).apply()
        File(modelsDir(), fileName + PART).delete()
        _states.value = _states.value - fileName
        refresh()
    }

    fun delete(fileName: String) {
        fileOf(fileName).delete()
        if (SettingsStore.state.value.selectedModel == fileName) {
            SettingsStore.update { it.copy(selectedModel = null) }
        }
        _states.value = _states.value - fileName
        refresh()
    }

    /** SAF 로 고른 ggml .bin 파일을 모델 폴더로 복사 (한국어 파인튜닝 ggml 모델 등) */
    suspend fun import(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cr = appContext.contentResolver
            var name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "custom-model.bin"
            name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (!name.endsWith(".bin")) name += ".bin"
            val magic = ByteArray(4)
            cr.openInputStream(uri)!!.use { it.read(magic) }
            // ggml 파일 매직 0x67676d6c ("lmgg" little-endian)
            require(String(magic) == "lmgg") { "ggml 형식의 whisper 모델(.bin)이 아닙니다" }
            val dst = fileOf(name)
            val tmp = File(dst.path + ".tmp")
            cr.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
            tmp.renameTo(dst)
            refresh()
            name
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            val dm = appContext.getSystemService(DownloadManager::class.java)
            while (isActive) {
                val pending = prefs().all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }
                if (pending.isEmpty()) break
                val updated = _states.value.toMutableMap()
                for ((name, id) in pending) {
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (!c.moveToFirst()) {
                            prefs().edit().remove(name).apply()
                            updated[name] = ModelState.Failed("다운로드가 취소되었습니다")
                            return@use
                        }
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                File(modelsDir(), name + PART).renameTo(fileOf(name))
                                prefs().edit().remove(name).apply()
                                updated[name] = ModelState.Installed(fileOf(name).length())
                                if (SettingsStore.state.value.selectedModel == null) {
                                    SettingsStore.update { it.copy(selectedModel = name) }
                                }
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                prefs().edit().remove(name).apply()
                                dm.remove(id)
                                updated[name] = ModelState.Failed("다운로드 실패 (코드 $reason)")
                            }
                            else -> updated[name] = ModelState.Downloading(done, total)
                        }
                    }
                }
                _states.value = updated
                delay(700)
            }
            refresh()
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
