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
                val unpacked = unpackAssetZip(context, DEFAULT_ASSET_ZIP, modelDir)
                if (!unpacked) {
                    Log.w(TAG, "Offline STT model missing. Put model at ${modelDir.absolutePath} or assets/$DEFAULT_ASSET_ZIP")
                    failed.set(true)
                    return false
                }
            }
            try {
                modelRef.set(Model(modelDir.absolutePath))
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
    fun transcribePcm16(context: Context, audio: ByteArray, sampleRate: Int = 16000, isStereo: Boolean = true): String? {
        if (!ensureModel(context)) return null
        val model = modelRef.get() ?: return null
        val mono = if (isStereo) downmixStereoToMono(audio) else audio
        return try {
            Recognizer(model, sampleRate.toFloat()).use { rec ->
                val ok = rec.acceptWaveForm(mono, mono.size)
                val resultJson = if (ok) rec.result else rec.partialResult
                parseTranscript(resultJson)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Offline STT failed: ${e.message}")
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
