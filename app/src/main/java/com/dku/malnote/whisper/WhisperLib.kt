package com.dku.malnote.whisper

import java.io.Closeable

/** whisper.cpp JNI 바인딩 (app/src/main/cpp/jni.cpp) */
object WhisperLib {
    init {
        System.loadLibrary("malnote_whisper")
    }

    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(ptr: Long)
    external fun nativeCancel(ptr: Long)
    external fun nativeTranscribe(
        ptr: Long,
        pcm: FloatArray,
        length: Int,
        language: String,
        threads: Int,
        beamSize: Int,
        prompt: String?,
        callback: TranscribeCallback?,
    ): Int

    external fun nativeSystemInfo(): String
}

/** JNI 에서 호출된다. 메서드 이름/시그니처를 바꾸지 말 것. */
interface TranscribeCallback {
    fun onProgress(progress: Int)
    fun onSegment(startMs: Long, endMs: Long, text: ByteArray)
}

class WhisperContext private constructor(
    private var ptr: Long,
    val modelPath: String,
) : Closeable {

    fun transcribe(
        pcm: FloatArray,
        length: Int,
        language: String,
        threads: Int,
        beamSize: Int,
        prompt: String?,
        onProgress: (Int) -> Unit,
        onSegment: (Long, Long, String) -> Unit,
    ): Int {
        check(ptr != 0L) { "context released" }
        val cb = object : TranscribeCallback {
            override fun onProgress(progress: Int) = onProgress(progress)
            override fun onSegment(startMs: Long, endMs: Long, text: ByteArray) =
                onSegment(startMs, endMs, String(text, Charsets.UTF_8))
        }
        return WhisperLib.nativeTranscribe(ptr, pcm, length, language, threads, beamSize, prompt, cb)
    }

    /** 다른 스레드에서 호출해도 안전 (native atomic 플래그) */
    fun cancel() {
        if (ptr != 0L) WhisperLib.nativeCancel(ptr)
    }

    @Synchronized
    override fun close() {
        if (ptr != 0L) {
            WhisperLib.nativeFree(ptr)
            ptr = 0L
        }
    }

    companion object {
        fun load(modelPath: String): WhisperContext {
            val p = WhisperLib.nativeInit(modelPath)
            if (p == 0L) throw IllegalStateException("모델을 불러오지 못했습니다: $modelPath")
            return WhisperContext(p, modelPath)
        }
    }
}
