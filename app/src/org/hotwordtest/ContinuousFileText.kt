package org.hotwordtest

import android.content.res.AssetManager
import java.io.File

/** Owns the lazy text view and the immutable audio staged for this import. */
internal class ContinuousFileText(
    private val inputFolder: File?,
    private val modelDir: File,
    private val hotwords: String,
    private val inputs: List<Input>,
    original: String? = null
) {
    data class Input(
        val wave: File,
        val sampleCount: Int,
        val regions: List<TimeAlignedAsr.ContinuousRegion>
    )

    @Volatile var text: String? = original
        private set

    @Synchronized fun load(assets: AssetManager, cancelled: () -> Boolean): String {
        text?.let { return it }
        if (cancelled()) throw InterruptedException("已取消")
        val parts = mutableListOf<String>()
        if (inputs.any { it.regions.isNotEmpty() }) {
            TimeAlignedAsr.Session(assets, modelDir, SpeakerModels.CAM, hotwords).use { session ->
                for (input in inputs) {
                    if (cancelled()) throw InterruptedException("已取消")
                    if (input.regions.isEmpty()) continue
                    val samples = input.wave.inputStream().use(WavReader::read16k)
                    check(samples.size == input.sampleCount) { "原音频缓存已失效，请重新导入" }
                    val rows = session.transcribeContinuous(samples, input.regions, cancelled)
                    for (i in 0 until rows.length()) parts.add(rows.getJSONObject(i).getString("text"))
                }
            }
        }
        if (cancelled()) throw InterruptedException("已取消")
        return originalText(parts).also {
            text = it
            releaseAudio()
        }
    }

    fun releaseAudio() {
        val folder = inputFolder ?: return
        folder.listFiles()?.forEach { it.delete() }
        folder.delete()
    }

    companion object {
        fun originalText(parts: Iterable<String>): String =
            parts.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
