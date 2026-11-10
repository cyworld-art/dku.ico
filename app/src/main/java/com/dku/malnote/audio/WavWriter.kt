package com.dku.malnote.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM mono WAV 파일 작성기. close() 시 헤더의 크기 필드를 채운다. */
class WavWriter(file: File, private val sampleRate: Int = 16000) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var scratch = ByteArray(0)

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    fun write(samples: ShortArray, count: Int) {
        if (count <= 0) return
        val bytes = count * 2
        if (scratch.size < bytes) scratch = ByteArray(bytes)
        ByteBuffer.wrap(scratch, 0, bytes).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().put(samples, 0, count)
        raf.write(scratch, 0, bytes)
        dataBytes += bytes
    }

    override fun close() {
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.close()
    }

    private fun header(dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(sampleRate); b.putInt(sampleRate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        return b.array()
    }
}
