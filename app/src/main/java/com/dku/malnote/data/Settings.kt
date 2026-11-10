package com.dku.malnote.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val selectedModel: String? = null,
    val threads: Int = defaultThreads(),
    val beamSize: Int = 1,
    val language: String = "ko",
    val prompt: String = DEFAULT_PROMPT,
    val autoTranscribe: Boolean = true,
) {
    companion object {
        /** 문장부호/띄어쓰기를 유도하는 한국어 초기 프롬프트 */
        const val DEFAULT_PROMPT = "안녕하세요. 오늘 회의 내용을 정리하겠습니다."

        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    }
}

object SettingsStore {
    private const val PREFS = "settings"
    private lateinit var appContext: Context
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = AppSettings(
            selectedModel = p.getString("selectedModel", null),
            threads = p.getInt("threads", AppSettings.defaultThreads()),
            beamSize = p.getInt("beamSize", 1),
            language = p.getString("language", "ko") ?: "ko",
            prompt = p.getString("prompt", AppSettings.DEFAULT_PROMPT) ?: AppSettings.DEFAULT_PROMPT,
            autoTranscribe = p.getBoolean("autoTranscribe", true),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        _state.value = s
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("selectedModel", s.selectedModel)
            .putInt("threads", s.threads)
            .putInt("beamSize", s.beamSize)
            .putString("language", s.language)
            .putString("prompt", s.prompt)
            .putBoolean("autoTranscribe", s.autoTranscribe)
            .apply()
    }
}
