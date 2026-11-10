package com.dku.malnote.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** whisper 입력용 16kHz mono float PCM. length 이후의 값은 무시한다. */
class Pcm(val data: FloatArray, val length: Int) {
    val durationMs: Long get() = length * 1000L / AudioDecoder.TARGET_RATE
}

object AudioDecoder {
    const val TARGET_RATE = 16000

    fun decode(file: File): Pcm {
        readWav16kMono(file)?.let { return it }
        return decodeWithMediaCodec(file)
    }

    /** 앱에서 녹음한 16kHz mono 16bit WAV 는 바로 읽는다. */
    private fun readWav16kMono(file: File): Pcm? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return null
            val head = ByteArray(44)
            raf.readFully(head)
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null
            val fmt = b.getShort(20).toInt()
            val ch = b.getShort(22).toInt()
            val rate = b.getInt(24)
            val bits = b.getShort(34).toInt()
            if (String(head, 36, 4) != "data") return null
            if (fmt != 1 || ch != 1 || rate != TARGET_RATE || bits != 16) return null
            val n = ((raf.length() - 44) / 2).toInt()
            val out = FloatArray(n)
            val buf = ByteArray(64 * 1024)
            var idx = 0
            while (idx < n) {
                val want = minOf(buf.size, (n - idx) * 2)
                raf.readFully(buf, 0, want)
                val sb = ByteBuffer.wrap(buf, 0, want).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (sb.hasRemaining()) out[idx++] = sb.get() / 32768f
            }
            return Pcm(out, n)
        }
    }

    /** m4a / mp3 / aac / ogg / flac / 동영상 음성 트랙 등 → 16kHz mono */
    private fun decodeWithMediaCodec(file: File): Pcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i; format = f; break
            }
        }
        require(track >= 0 && format != null) { "오디오 트랙을 찾을 수 없습니다" }
        extractor.selectTrack(track)

        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val estimate = (durationUs / 1_000_000.0 * TARGET_RATE).toInt() + TARGET_RATE * 2
        val out = FloatList(maxOf(estimate, TARGET_RATE * 10))

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var resampler: Resampler? = null
        var mono = FloatArray(0)

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bb = buf.slice().order(ByteOrder.nativeOrder())
                        val frames: Int
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = bb.asFloatBuffer()
                            frames = fb.remaining() / channels
                            if (mono.size < frames) mono = FloatArray(frames)
                            for (i in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += fb.get()
                                mono[i] = s / channels
                            }
                        } else {
                            val sb = bb.asShortBuffer()
                            frames = sb.remaining() / channels
                            if (mono.size < frames) mono = FloatArray(frames)
                            for (i in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += sb.get()
                                mono[i] = s / channels / 32768f
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (sampleRate == TARGET_RATE) {
                            out.addAll(mono, frames)
                        } else {
                            val r = resampler ?: Resampler(sampleRate, TARGET_RATE).also { resampler = it }
                            r.process(mono, frames, out, eos)
                        }
                        if (eos) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop(); codec.release(); extractor.release()
        }
        return Pcm(out.array, out.size)
    }
}

/** 크기를 미리 잡아두는 float 버퍼 (긴 오디오에서 복사를 줄이기 위함) */
class FloatList(capacity: Int) {
    var array = FloatArray(capacity)
        private set
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > array.size) {
            array = array.copyOf(maxOf(array.size + array.size / 2, size + extra))
        }
    }

    fun add(v: Float) { ensure(1); array[size++] = v }
    fun addAll(src: FloatArray, n: Int) { ensure(n); System.arraycopy(src, 0, array, size, n); size += n }
}

/** 스트리밍 windowed-sinc 리샘플러 (앨리어싱 방지 저역통과 포함) */
class Resampler(inRate: Int, outRate: Int, private val taps: Int = 16) {
    private val ratio = inRate.toDouble() / outRate
    private val cutoff = minOf(1.0, 1.0 / ratio) * 0.95
    private var buf = FloatArray(8192)
    private var bufLen = 0
    private var bufStart = 0L   // buf[0] 의 절대 입력 인덱스
    private var nextOut = 0L

    fun process(input: FloatArray, n: Int, out: FloatList, final: Boolean) {
        if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
        System.arraycopy(input, 0, buf, bufLen, n)
        bufLen += n
        val end = bufStart + bufLen
        while (true) {
            val t = nextOut * ratio
            val center = floor(t).toLong()
            if (!final && center + taps >= end) break
            if (final && center >= end) break
            var sum = 0.0
            for (j in center - taps + 1..center + taps) {
                val idx = (j - bufStart).toInt()
                if (idx < 0 || idx >= bufLen) continue
                val x = t - j
                val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
                val win = 0.5 + 0.5 * cos(PI * x / taps)
                sum += buf[idx] * sinc * win * cutoff
            }
            out.add(sum.toFloat())
            nextOut++
        }
        // 이미 사용한 입력은 버린다 (히스토리 taps 개는 남김)
        val keepFrom = floor(nextOut * ratio).toLong() - taps
        val drop = (keepFrom - bufStart).toInt().coerceIn(0, bufLen)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
    }
}
