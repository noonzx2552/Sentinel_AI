package com.sentinel.ai.ai

import android.content.Context
import android.util.Log
import com.sentinel.ai.utils.WavUtil
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * Offline STT helper for Vosk.
 *
 * - Unpacks assets/models/vosk-model.zip into filesDir/vosk-model on first use.
 * - Provides a reusable Recognizer at 16 kHz for streaming audio.
 */
object OfflineStt {
    private const val TAG = "OfflineStt"
    private const val DEFAULT_MODEL_DIR = "vosk-model"
    private const val DEFAULT_ASSET_ZIP = "models/vosk-model.zip"
    private const val TARGET_SAMPLE_RATE = 16000

    private val modelRef = AtomicReference<Model?>()
    private val recognizerRef = AtomicReference<Recognizer?>()
    private val failed = AtomicBoolean(false)

    /**
     * Ensure model exists on disk (unpack if needed) and return it.
     */
    fun ensureModel(context: Context): Boolean {
        modelRef.get()?.let { return true }
        if (failed.get()) return false

        synchronized(this) {
            modelRef.get()?.let { return true }
            val modelDir = File(context.filesDir, DEFAULT_MODEL_DIR)
            if (!modelDir.exists()) {
                Log.d(TAG, "Model dir missing, unpacking assets/$DEFAULT_ASSET_ZIP -> ${modelDir.absolutePath}")
                if (!unpackAssetZip(context, DEFAULT_ASSET_ZIP, modelDir)) {
                    failed.set(true)
                    return false
                }
            }
            try {
                val actualDir = resolveModelDir(modelDir)
                Log.d(TAG, "Loading Vosk model from ${actualDir.absolutePath}")
                modelRef.set(Model(actualDir.absolutePath))
            } catch (e: Exception) {
                failed.set(true)
                Log.e(TAG, "Failed to load Vosk model: ${e.message}", e)
                return false
            }
        }
        return modelRef.get() != null
    }

    /**
     * Lazily create/reuse a Recognizer at the target sample rate.
     */
    fun recognizer(context: Context): Recognizer {
        recognizerRef.get()?.let { return it }
        synchronized(this) {
            recognizerRef.get()?.let { return it }
            if (!ensureModel(context)) throw IllegalStateException("Model not ready")
            val model = modelRef.get() ?: throw IllegalStateException("Model missing after load")
            val rec = Recognizer(model, TARGET_SAMPLE_RATE.toFloat())
            recognizerRef.set(rec)
            Log.d(TAG, "Recognizer created at $TARGET_SAMPLE_RATE Hz")
            return rec
        }
    }

    /** Drop the shared streaming recognizer (used by long-running capture) */
    fun resetRecognizer() {
        try { recognizerRef.getAndSet(null)?.close() } catch (_: Exception) { }
    }

    /**
     * Transcribe an audio file. Supports WAV (PCM) only; reads PCM and forwards to transcribePcm16.
     * For other formats use Whisper/cloud. Returns null if not WAV or on error.
     */
    fun transcribeFile(context: Context, file: File): String? {
        if (!file.exists() || file.length() == 0L) return null
        val parsed = WavUtil.readPcmFromWav(file) ?: return null
        val (pcm, sampleRate, channels) = parsed
        if (pcm.isEmpty()) return null
        return transcribePcm16(context, pcm, sampleRate, isStereo = channels >= 2)
    }

    /**
     * Compatibility helper: transcribe raw PCM bytes (little-endian). Accepts mono or stereo.
     */
    fun transcribePcm16(
        context: Context,
        audio: ByteArray,
        sampleRate: Int = TARGET_SAMPLE_RATE,
        isStereo: Boolean = true
    ): String? {
        if (audio.isEmpty()) return null
        if (!ensureModel(context)) return null
        val monoBytes = if (isStereo) downmixStereoToMono(audio) else audio
        val shortCount = monoBytes.size / 2
        val shorts = ShortArray(shortCount)
        ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return recognizePcm16(context, shorts, shortCount, sampleRate)
    }

    fun createRecognizer(context: Context, sampleRate: Int = TARGET_SAMPLE_RATE): Recognizer? {
        return try {
            if (!ensureModel(context)) return null
            val model = modelRef.get() ?: return null
            Recognizer(model, sampleRate.toFloat())
        } catch (e: Exception) {
            Log.w(TAG, "createRecognizer failed: ${e.message}", e)
            null
        }
    }

    /**
     * Feed PCM 16-bit little-endian audio into Vosk.
     * If sampleRate != 16k, audio is downsampled.
     * Returns partial/final transcript (no punctuation) or null.
     */
    fun recognizePcm16(
        context: Context,
        audio: ShortArray,
        size: Int,
        sampleRate: Int
    ): String? {
        if (size <= 0) return null
        val rec = createRecognizer(context, TARGET_SAMPLE_RATE) ?: return null
        val mono16k = if (sampleRate == TARGET_SAMPLE_RATE) {
            audio.copyOfRange(0, size)
        } else {
            resampleTo16k(audio, size, sampleRate)
        }
        val bytes = shortArrayToLeBytes(mono16k)
        return try {
            val ok = rec.acceptWaveForm(bytes, bytes.size)
            val json = if (ok) rec.result else rec.partialResult
            val text = parseTranscript(json)
            Log.d(TAG, "Vosk accept ok=$ok inBytes=${bytes.size} srcSr=$sampleRate ->16k text=${text.orEmpty()}")
            text
        } finally {
            try { rec.close() } catch (_: Exception) { }
        }
    }

    /**
     * Optional cleanup.
     */
    fun reset() {
        try { recognizerRef.getAndSet(null)?.close() } catch (_: Exception) { }
        try { modelRef.getAndSet(null)?.close() } catch (_: Exception) { }
        failed.set(false)
    }

    private fun parseTranscript(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val key = if (json.contains("\"text\"")) "\"text\"" else "\"partial\""
            val keyPos = json.indexOf(key)
            if (keyPos == -1) return null
            val startQuote = json.indexOf('"', keyPos + key.length)
            val endQuote = json.indexOf('"', startQuote + 1)
            if (startQuote == -1 || endQuote == -1 || endQuote <= startQuote) return null
            json.substring(startQuote + 1, endQuote)
        } catch (e: Exception) {
            Log.w(TAG, "parseTranscript failed: ${e.message}", e)
            null
        }
    }

    private fun resampleTo16k(input: ShortArray, size: Int, srcRate: Int): ShortArray {
        if (srcRate <= 0 || srcRate == TARGET_SAMPLE_RATE) return input.copyOfRange(0, size)
        val inSize = size.coerceAtMost(input.size)
        val outLen = (inSize.toLong() * TARGET_SAMPLE_RATE / srcRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        var pos = 0.0
        for (i in 0 until outLen) {
            val idx = pos.toInt().coerceIn(0, inSize - 1)
            val nextIdx = (idx + 1).coerceAtMost(inSize - 1)
            val frac = pos - idx
            val sample = input[idx] * (1 - frac) + input[nextIdx] * frac
            out[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pos += step
        }
        return out
    }

    private fun shortArrayToLeBytes(data: ShortArray): ByteArray {
        val out = ByteArray(data.size * 2)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(data)
        return out
    }

    private fun downmixStereoToMono(stereo: ByteArray): ByteArray {
        if (stereo.size < 4) return stereo
        val frames = stereo.size / 4 // 2 shorts per frame
        val out = ByteArray(frames * 2) // mono: 1 short per frame
        val inBb = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val outBb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        repeat(frames) {
            val l = inBb.get().toInt()
            val r = inBb.get().toInt()
            val mixed = ((l + r) / 2).toShort()
            outBb.put(mixed)
        }
        return out
    }

    private fun resolveModelDir(root: File): File {
        val nestedModel = File(root, "model")
        if (nestedModel.exists()) return nestedModel
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        Log.d(TAG, "resolveModelDir root=${root.absolutePath} dirs=${dirs.joinToString { it.name }}")
        return if (dirs.size == 1) dirs.first() else root
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
            Log.w(TAG, "unpackAssetZip failed for $assetName -> ${targetDir.absolutePath}: ${e.message}", e)
            false
        }
    }
}
