package com.sentinel.ai.ai

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * Offline STT using Vosk. Expects a model directory present on device.
 * Place a model at filesDir/vosk-model or add an asset zip at assets/models/vosk-model.zip.
 */
object OfflineStt {
    private const val TAG = "OfflineStt"
    private const val DEFAULT_MODEL_DIR = "vosk-model"
    private const val DEFAULT_ASSET_ZIP = "models/vosk-model.zip"
    private const val TARGET_SAMPLE_RATE = 16000
    private val modelRef = AtomicReference<Model?>()
    private val failed = AtomicBoolean(false)

    fun ensureModel(context: Context): Boolean {
        if (modelRef.get() != null) return true
        if (failed.get()) return false
        synchronized(this) {
            if (modelRef.get() != null) return true
            val modelDir = File(context.filesDir, DEFAULT_MODEL_DIR)
            if (!modelDir.exists()) {
                // Try unpack from asset zip if present.
                Log.d(TAG, "Model dir missing, attempting unpack from assets/${DEFAULT_ASSET_ZIP}")
                val unpacked = unpackAssetZip(context, DEFAULT_ASSET_ZIP, modelDir)
                if (!unpacked) {
                    Log.w(TAG, "Offline STT model missing. Put model at ${modelDir.absolutePath} or assets/$DEFAULT_ASSET_ZIP")
                    failed.set(true)
                    return false
                }
            }
            try {
                val actualDir = resolveModelDir(modelDir)
                Log.d(TAG, "Loading Vosk model from ${actualDir.absolutePath}")
                modelRef.set(Model(actualDir.absolutePath))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load Vosk model: ${e.message}")
                failed.set(true)
                return false
            }
        }
        return modelRef.get() != null
    }

    /**
     * Transcribe PCM 16-bit little-endian audio. Supports mono or stereo input; stereo is down-mixed.
     */
    fun transcribePcm16(
        context: Context,
        audio: ByteArray,
        sampleRate: Int = 16000,
        isStereo: Boolean = true
    ): String? {
        if (!ensureModel(context)) return null
        val model = modelRef.get() ?: return null
        val mono = if (isStereo) downmixStereoToMono(audio) else audio
        val processed = if (sampleRate != TARGET_SAMPLE_RATE) {
            resampleTo16k(mono, sampleRate)
        } else {
            mono
        }
        return try {
            Recognizer(model, TARGET_SAMPLE_RATE.toFloat()).use { rec ->
                val ok = rec.acceptWaveForm(processed, processed.size)
                val resultJson = if (ok) rec.result else rec.partialResult
                val transcript = parseTranscript(resultJson)
                Log.d(TAG, "STT ok=$ok bytes=${processed.size} sr=$sampleRate->${TARGET_SAMPLE_RATE} transcript=${transcript ?: ""}")
                transcript
            }
        } catch (e: Exception) {
            Log.w(TAG, "Offline STT failed: ${e.message}")
            null
        }
    }

    /**
     * Streaming recognizer holder so callers can feed multiple audio chunks without
     * recreating the native Recognizer each time (which tends to return empty results
     * on short buffers).
     */
    class StreamingSession internal constructor(
        private val recognizer: Recognizer,
        private val sourceSampleRate: Int,
        private val isStereoInput: Boolean
    ) {
        fun accept(audio: ByteArray, size: Int = audio.size): String? {
            if (size <= 0) return null
            val chunk = if (size == audio.size) audio else audio.copyOf(size)
            val mono = if (isStereoInput) downmixStereoToMono(chunk) else chunk
            val processed = if (sourceSampleRate != TARGET_SAMPLE_RATE) {
                resampleTo16k(mono, sourceSampleRate)
            } else {
                mono
            }
            val ok = recognizer.acceptWaveForm(processed, processed.size)
            val resultJson = if (ok) recognizer.result else recognizer.partialResult
            return parseTranscript(resultJson)
        }

        fun close() {
            try {
                recognizer.close()
            } catch (_: Exception) { }
        }
    }

    fun createStreamingSession(
        context: Context,
        sourceSampleRate: Int = TARGET_SAMPLE_RATE,
        isStereo: Boolean = true
    ): StreamingSession? {
        if (!ensureModel(context)) return null
        val model = modelRef.get() ?: return null
        return try {
            StreamingSession(
                recognizer = Recognizer(model, TARGET_SAMPLE_RATE.toFloat()),
                sourceSampleRate = sourceSampleRate,
                isStereoInput = isStereo
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create streaming recognizer: ${e.message}")
            null
        }
    }

    private fun parseTranscript(json: String?): String? {
        if (json.isNullOrBlank()) return null
        // Vosk result JSON: {"text":"..."} or partial {"partial":"..."}
        return try {
            val key = if (json.contains("\"text\"")) "\"text\"" else "\"partial\""
            val idx = json.indexOf(key)
            if (idx == -1) return null
            val start = json.indexOf(':', idx) + 1
            val end = json.indexOf('"', start + 1)
            val firstQuote = json.indexOf('"', start)
            if (firstQuote == -1 || end == -1 || end <= firstQuote) return null
            json.substring(firstQuote + 1, end)
        } catch (_: Exception) {
            null
        }
    }

    private fun downmixStereoToMono(stereo: ByteArray): ByteArray {
        val bb = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN)
        val samples = bb.asShortBuffer()
        val out = ByteArray(stereo.size / 2) // half size for mono
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val totalSamples = samples.remaining() / 2
        for (i in 0 until totalSamples) {
            val l = samples.get().toInt()
            val r = samples.get().toInt()
            val mixed = ((l + r) / 2).toShort()
            outBuf.putShort(mixed)
        }
        return out
    }

    private fun resolveModelDir(root: File): File {
        // Some zips contain a top-level "model" folder; handle that gracefully.
        val nestedModel = File(root, "model")
        if (nestedModel.exists()) return nestedModel
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        Log.d(TAG, "resolveModelDir inspecting ${root.absolutePath}, dirs=${dirs.joinToString { it.name }}")
        if (dirs.size == 1) return dirs.first()
        return root
    }

    private fun resampleTo16k(mono: ByteArray, srcRate: Int): ByteArray {
        if (srcRate == 16000) return mono
        if (srcRate <= 0) return mono
        val shortIn = ShortArray(mono.size / 2)
        ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortIn)
        val outLen = (shortIn.size.toLong() * 16000L / srcRate).toInt().coerceAtLeast(1)
        val outShort = ShortArray(outLen)
        val step = srcRate.toDouble() / 16000.0
        var pos = 0.0
        for (i in 0 until outLen) {
            val idx = pos.toInt().coerceIn(0, shortIn.lastIndex)
            val nextIdx = (idx + 1).coerceAtMost(shortIn.lastIndex)
            val frac = pos - idx
            val sample = shortIn[idx] * (1 - frac) + shortIn[nextIdx] * frac
            outShort[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pos += step
        }
        val outBytes = ByteArray(outShort.size * 2)
        ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outShort)
        return outBytes
    }

    private fun unpackAssetZip(context: Context, assetName: String, targetDir: File): Boolean {
        return try {
            context.assets.open(assetName).use { input ->
                targetDir.mkdirs()
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(targetDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                zis.copyTo(out)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not unpack asset $assetName: ${e.message}")
            false
        }
    }
}
