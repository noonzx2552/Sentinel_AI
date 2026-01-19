package com.sentinel.ai.ai

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.exp
import kotlin.math.min

/**
 * Debug-only ONNX classifier that loads scam_model.zip from debug assets.
 * Large model loading is kept off the main thread to avoid UI stalls.
 */
class DebugScamModel(private val context: Context) : ScamModel {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val mutex = Mutex()
    private var session: OrtSession? = null
    private var tokenizer: HuggingFaceTokenizer? = null

    override suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (session != null && tokenizer != null) return@withLock true
            val modelDir = unpackIfNeeded() ?: return@withLock false
            val tokenizerFile = File(modelDir, "tokenizer.json")
            val modelFile = File(modelDir, "scam_model_int8.onnx")
            if (!tokenizerFile.exists() || !modelFile.exists()) {
                Log.e(TAG, "Model assets missing in ${modelDir.absolutePath}")
                return@withLock false
            }
            return@withLock try {
                tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())
                session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
                Log.i(TAG, "Scam model loaded from ${modelFile.absolutePath}")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize ONNX session: ${e.message}", e)
                false
            }
        }
    }

    private suspend fun unpackIfNeeded(): File? = withContext(Dispatchers.IO) {
        val targetRoot = File(context.filesDir, "scam_model")
        val modelRoot = File(targetRoot, "out/scam_model")
        val modelFile = File(modelRoot, "scam_model_int8.onnx")
        if (modelFile.exists()) return@withContext modelRoot
        try {
            context.assets.open("models/scam_model.zip").use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(targetRoot, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out -> zis.copyTo(out) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            Log.d(TAG, "Unpacked scam_model.zip into ${targetRoot.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unpack scam_model.zip: ${e.message}", e)
            return@withContext null
        }
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file missing after unzip: ${modelFile.absolutePath}")
            return@withContext null
        }
        modelRoot
    }

    override suspend fun predict(text: String): ScamModel.Prediction? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        if (!ensureLoaded()) return@withContext null
        val tokenizer = tokenizer ?: return@withContext null
        val session = session ?: return@withContext null
        try {
            val encoding = tokenizer.encode(text)
            val ids = encoding.ids
            val mask = encoding.attentionMask
            val maxLen = 128
            val inputIds = LongArray(maxLen) { 0L }
            val attentionMask = LongArray(maxLen) { 0L }
            val length = min(maxLen, ids.size)
            for (i in 0 until length) {
                inputIds[i] = ids[i].toLong()
                attentionMask[i] = mask[i].toLong()
            }

            val inputName = session.inputNames.find { it.contains("input", ignoreCase = true) }
                ?: session.inputNames.first()
            val maskName = session.inputNames.find { it.contains("mask", ignoreCase = true) }
                ?: session.inputNames.last()

            val idsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
            val maskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask))
            try {
                session.run(mapOf(inputName to idsTensor, maskName to maskTensor)).use { result ->
                    val raw = result[0].value as? Array<FloatArray> ?: return@withContext null
                    if (raw.isEmpty()) return@withContext null
                    val scores = raw[0]
                    val probs = softmax(scores)
                    val scamProb = if (probs.size > 1) probs[1] else probs.firstOrNull() ?: 0f
                    val label = if (scamProb >= 0.5f) "SCAM" else "SAFE"
                    return@withContext ScamModel.Prediction(label, scamProb.coerceIn(0f, 1f))
                }
            } finally {
                idsTensor.close()
                maskTensor.close()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            null
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        if (logits.isEmpty()) return FloatArray(0)
        val max = logits.maxOrNull() ?: 0f
        val exps = logits.map { exp((it - max).toDouble()) }
        val sum = exps.sum()
        return FloatArray(exps.size) { idx ->
            (exps[idx] / sum).toFloat()
        }
    }

    companion object {
        private const val TAG = "DebugScamModel"
    }
}
