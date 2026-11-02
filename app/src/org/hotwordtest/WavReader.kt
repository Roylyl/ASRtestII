package org.hotwordtest

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.IOException

/** Reads PCM WAV without closing the caller's stream. No more than 50 MiB of WAV is consumed. */
object WavReader {
    private const val MAX_BYTES = 50L * 1024 * 1024
    private const val TARGET_RATE = 16000

    fun read16k(input: InputStream): FloatArray {
        val source = BufferedInputStream(input, 32768)
        var consumed = 0L
        fun byte(): Int {
            if (consumed >= MAX_BYTES) throw IOException("WAV文件超过50MiB限制")
            val b = source.read()
            if (b < 0) throw EOFException("WAV文件不完整")
            consumed++
            return b
        }
        fun u16(): Int = byte() or (byte() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        fun tag(): String = CharArray(4) { byte().toChar() }.concatToString()
        fun skip(count: Long) {
            var n = count
            if (n > MAX_BYTES - consumed) throw IOException("WAV文件超过50MiB限制")
            val buf = ByteArray(8192)
            while (n > 0) {
                val read = source.read(buf, 0, minOf(n, buf.size.toLong()).toInt())
                if (read < 0) throw EOFException("WAV数据块不完整")
                if (read == 0) { byte(); n-- } else { consumed += read; n -= read }
            }
        }
        require(tag() == "RIFF") { "不是RIFF格式的WAV文件" }
        val riffSize = u32()
        require(riffSize >= 4) { "WAV的RIFF长度无效" }
        val riffEnd = riffSize + 8
        if (riffEnd > MAX_BYTES) throw IOException("WAV文件超过50MiB限制")
        require(tag() == "WAVE") { "不是WAVE音频文件" }
        var sampleRate = 0
        var channels = 0
        var blockAlign = 0
        var result: FloatArray? = null
        while (consumed < riffEnd) {
            require(riffEnd - consumed >= 8) { "WAV数据块头不完整" }
            val id = tag()
            val length = u32()
            val padded = length + (length and 1L)
            require(padded <= riffEnd - consumed) { "WAV数据块长度超出RIFF范围" }
            if (id == "fmt ") {
                require(length >= 16) { "WAV格式信息不完整" }
                val format = u16()
                channels = u16()
                val rate = u32()
                val byteRate = u32()
                blockAlign = u16()
                val bits = u16()
                require(format == 1 && bits == 16) { "仅支持16位PCM WAV" }
                require(channels == 1 || channels == 2) { "仅支持单声道或双声道WAV" }
                require(rate == 16000L || rate == 44100L || rate == 48000L) { "仅支持16kHz、44.1kHz或48kHz采样率" }
                require(blockAlign == channels * 2 && byteRate == rate * blockAlign) { "WAV声道或字节率信息无效" }
                sampleRate = rate.toInt()
                skip(length - 16)
            } else if (id == "data") {
                require(sampleRate != 0) { "WAV缺少位于音频前的格式信息" }
                require(result == null) { "不支持包含多个音频数据块的WAV" }
                require(length > 0 && length % blockAlign == 0L) { "WAV音频数据为空或帧长度不完整" }
                val frames = length / blockAlign
                require(frames <= sampleRate.toLong() * 1200) { "单个WAV不能超过20分钟" }
                val outputCount = (frames * TARGET_RATE / sampleRate).toInt()
                require(outputCount > 0) { "WAV音频太短" }
                val output = FloatArray(outputCount)
                var outputIndex = 0
                var previous = 0f
                for (index in 0 until frames.toInt()) {
                    var sum = 0f
                    repeat(channels) { sum += u16().toShort().toInt() / 32768f }
                    val current = sum / channels
                    if (index == 0) {
                        output[outputIndex++] = current
                    } else {
                        while (outputIndex < outputCount && outputIndex.toLong() * sampleRate <= index.toLong() * TARGET_RATE) {
                            val fraction = (outputIndex.toLong() * sampleRate - (index - 1L) * TARGET_RATE).toFloat() / TARGET_RATE
                            output[outputIndex++] = previous + (current - previous) * fraction
                        }
                    }
                    previous = current
                }
                while (outputIndex < outputCount) output[outputIndex++] = previous
                result = output
            } else {
                skip(length)
            }
            if ((length and 1L) != 0L) skip(1)
        }
        return result ?: throw IllegalArgumentException("WAV缺少音频数据块")
    }
}
