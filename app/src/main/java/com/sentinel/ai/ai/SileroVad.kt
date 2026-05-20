package com.sentinel.ai.ai

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

class SileroVad(
    private val context: Context,
    private val threshold: Float = 0.5f
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String?
    private val sampleRateName: String?
    private val hName: String?
    private val cName: String?
    private val outputName: String?
    private val hnName: String?
    private val cnName: String?
    private var hState = FloatArray(STATE_SIZE)
    private var cState = FloatArray(STATE_SIZE)

    init {
        val modelPath = ensureModelFile()
        session = env.createSession(modelPath, OrtSession.SessionOptions())
        val inputs = session.inputNames.map { it.lowercase() to it }.toMap()
        val outputs = session.outputNames.map { it.lowercase() to it }.toMap()
        inputName = inputs["input"] ?: inputs.values.firstOrNull()
        sampleRateName = inputs["sr"] ?: inputs["sample_rate"]
        hName = inputs["h"]
        cName = inputs["c"]
        outputName = outputs["output"] ?: outputs.values.firstOrNull()
        hnName = outputs["hn"]
        cnName = outputs["cn"]
    }

    fun isSpeech(samples: ShortArray, length: Int, sampleRate: Int): Boolean {
        return speechProbability(samples, length, sampleRate) >= threshold
    }

    fun speechProbability(samples: ShortArray, length: Int, sampleRate: Int): Float {
        if (length <= 0 || inputName == null || outputName == null) return 1f
        if (sampleRate != TARGET_SAMPLE_RATE) return 1f

        var maxProbability = 0f
        var offset = 0
        while (offset + FRAME_SIZE <= length) {
            val prob = runFrame(samples, offset)
            if (prob > maxProbability) maxProbability = prob
            offset += FRAME_SIZE
        }
        return maxProbability
    }

    fun reset() {
        hState.fill(0f)
        cState.fill(0f)
    }

    override fun close() {
        runCatching { session.close() }
    }

    private fun runFrame(samples: ShortArray, offset: Int): Float {
        val floats = FloatArray(FRAME_SIZE)
        for (i in 0 until FRAME_SIZE) {
            floats[i] = samples[offset + i] / SHORT_MAX
        }
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), longArrayOf(1, FRAME_SIZE.toLong()))
        val hTensor = hName?.let {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(hState), longArrayOf(2, 1, 64))
        }
        val cTensor = cName?.let {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(cState), longArrayOf(2, 1, 64))
        }
        val srTensor = sampleRateName?.let {
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(TARGET_SAMPLE_RATE.toLong())), longArrayOf(1))
        }

        val inputMap = mutableMapOf<String, OnnxTensor>()
        inputMap[inputName!!] = inputTensor
        hTensor?.let { inputMap[hName!!] = it }
        cTensor?.let { inputMap[cName!!] = it }
        srTensor?.let { inputMap[sampleRateName!!] = it }

        return try {
            session.run(inputMap).use { result ->
                val outputTensor = result[outputName] as? OnnxTensor
                val outputValues = outputTensor?.value
                val prob = readFirstFloat(outputValues)

                val hn = hnName?.let { name -> result[name] as? OnnxTensor }
                val cn = cnName?.let { name -> result[name] as? OnnxTensor }
                readFloatArray(hn?.value)?.let { data ->
                    if (data.size >= STATE_SIZE) {
                        System.arraycopy(data, 0, hState, 0, STATE_SIZE)
                    }
                }
                readFloatArray(cn?.value)?.let { data ->
                    if (data.size >= STATE_SIZE) {
                        System.arraycopy(data, 0, cState, 0, STATE_SIZE)
                    }
                }
                prob
            }
        } catch (e: Exception) {
            Log.w(TAG, "Silero VAD inference failed: ${e.message}", e)
            0f
        } finally {
            inputTensor.close()
            hTensor?.close()
            cTensor?.close()
            srTensor?.close()
        }
    }

    private fun ensureModelFile(): String {
        val outFile = File(context.cacheDir, MODEL_FILE)
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath
        context.assets.open(MODEL_FILE).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile.absolutePath
    }

    private fun readFirstFloat(value: Any?): Float {
        return when (value) {
            is FloatArray -> value.firstOrNull() ?: 0f
            is Array<*> -> {
                val first = value.firstOrNull()
                if (first is FloatArray) {
                    first.firstOrNull() ?: 0f
                } else if (first is Array<*>) {
                    val nested = first.firstOrNull()
                    if (nested is FloatArray) nested.firstOrNull() ?: 0f else 0f
                } else {
                    0f
                }
            }
            is java.nio.FloatBuffer -> if (value.hasRemaining()) value.get() else 0f
            else -> 0f
        }
    }

    private fun readFloatArray(value: Any?): FloatArray? {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val flattened = mutableListOf<Float>()
                value.forEach { item ->
                    when (item) {
                        is FloatArray -> item.forEach { flattened.add(it) }
                        is Array<*> -> item.forEach { inner ->
                            if (inner is FloatArray) inner.forEach { flattened.add(it) }
                        }
                    }
                }
                if (flattened.isEmpty()) null else flattened.toFloatArray()
            }
            is java.nio.FloatBuffer -> {
                val copy = FloatArray(value.remaining())
                value.get(copy)
                copy
            }
            else -> null
        }
    }

    companion object {
        private const val TAG = "SileroVad"
        private const val MODEL_FILE = "silero_vad.onnx"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512
        private const val SHORT_MAX = 32768f
        private const val STATE_SIZE = 128
    }
}
