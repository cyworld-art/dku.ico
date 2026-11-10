package com.dku.malnote.data

data class Segment(val startMs: Long, val endMs: Long, val text: String)

enum class TranscriptStatus { NONE, QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class Recording(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val audioFileName: String,
    val status: TranscriptStatus = TranscriptStatus.NONE,
    val modelName: String? = null,
    val error: String? = null,
    val transcribeTimeMs: Long = 0,
    val segments: List<Segment> = emptyList(),
) {
    val fullText: String get() = segments.joinToString("\n") { it.text.trim() }
}

/** 다운로드 가능한 whisper.cpp ggml 모델 목록 (Hugging Face: ggerganov/whisper.cpp) */
data class WhisperModel(
    val fileName: String,
    val displayName: String,
    val sizeMb: Int,
    val description: String,
    val recommended: Boolean = false,
) {
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        val CATALOG = listOf(
            WhisperModel(
                "ggml-large-v3-turbo-q5_0.bin", "Large-v3 Turbo (Q5_0)", 547,
                "추천 · large-v3 기반, 디코더 경량화로 약 5~8배 빠름. 한국어 품질 우수", recommended = true,
            ),
            WhisperModel(
                "ggml-large-v3-turbo-q8_0.bin", "Large-v3 Turbo (Q8_0)", 834,
                "Turbo 고정밀 양자화. Q5 보다 약간 정확하고 약간 느림",
            ),
            WhisperModel(
                "ggml-large-v3-q5_0.bin", "Large-v3 (Q5_0)", 1031,
                "원본 large-v3 전체 디코더. 가장 정확하지만 느림 (긴 녹음은 오래 걸림)",
            ),
            WhisperModel(
                "ggml-large-v3.bin", "Large-v3 (FP16 원본)", 2950,
                "양자화 없는 원본. 메모리 사용량 큼, 매우 느림",
            ),
            WhisperModel(
                "ggml-medium-q5_0.bin", "Medium (Q5_0)", 514,
                "중간 크기. 속도/정확도 절충",
            ),
            WhisperModel(
                "ggml-small-q5_1.bin", "Small (Q5_1)", 181,
                "빠른 테스트용. 한국어 정확도는 낮음",
            ),
        )
    }
}
