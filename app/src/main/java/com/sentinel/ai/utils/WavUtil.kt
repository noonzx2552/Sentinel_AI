package com.sentinel.ai.utils

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File

/**
 * Utility object for handling WAV file operations.
 */
object WavUtil {

    /**
     * Reads PCM bytes, sample rate, and channel count from a WAV file.
     * @return Triple(pcmBytes, sampleRate, channels) or null if not a valid PCM WAV.
     */
    fun readPcmFromWav(file: File): Triple<ByteArray, Int, Int>? {
        if (!file.exists() || file.length() < 44) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(44)
                if (raf.read(header) < 44) return null
                if (!String(header, 0, 4).equals("RIFF", true) ||
                    !String(header, 8, 4).equals("WAVE", true)) return null
                val channels = ByteBuffer.wrap(header, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt().and(0xFFFF)
                val sampleRate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val dataLen = ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int and 0x7FFF_FFFF
                if (dataLen <= 0 || dataLen > file.length() - 44) return null
                val pcm = ByteArray(dataLen)
                raf.seek(44)
                if (raf.read(pcm) != dataLen) return null
                Triple(pcm, sampleRate, channels)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Wraps raw PCM audio data in a WAV file container.
     *
     * @param pcmData The raw audio data (PCM 16-bit).
     * @param sampleRate The sample rate of the audio (e.g., 44100).
     * @param channels The number of channels (1 for mono, 2 for stereo).
     * @param bitDepth The bit depth of the audio (e.g., 16).
     * @return A ByteArray representing the complete WAV file.
     * @throws IOException If there is an error writing to the byte stream.
     */
    @Throws(IOException::class)
    fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitDepth: Int): ByteArray {
        val wavOutputStream = ByteArrayOutputStream()
        val dataSize = pcmData.size
        val fileSize = dataSize + 36 // 44 bytes for header - 8 bytes for RIFF/WAVE chunks

        // Write WAV file header
        writeString(wavOutputStream, "RIFF") // RIFF chunk identifier
        writeInt(wavOutputStream, fileSize)     // RIFF chunk size
        writeString(wavOutputStream, "WAVE") // WAVE format
        writeString(wavOutputStream, "fmt ") // fmt sub-chunk identifier
        writeInt(wavOutputStream, 16)           // fmt chunk size (16 for PCM)
        writeShort(wavOutputStream, 1.toShort()) // Audio format (1 for PCM)
        writeShort(wavOutputStream, channels.toShort()) // Number of channels
        writeInt(wavOutputStream, sampleRate)   // Sample rate
        writeInt(wavOutputStream, sampleRate * channels * bitDepth / 8) // Byte rate
        writeShort(wavOutputStream, (channels * bitDepth / 8).toShort()) // Block align
        writeShort(wavOutputStream, bitDepth.toShort()) // Bits per sample
        writeString(wavOutputStream, "data") // data sub-chunk identifier
        writeInt(wavOutputStream, dataSize)     // data chunk size

        // Write the actual PCM data
        wavOutputStream.write(pcmData)

        val wavData = wavOutputStream.toByteArray()
        wavOutputStream.close()
        return wavData
    }

    @Throws(IOException::class)
    private fun writeInt(outputStream: ByteArrayOutputStream, value: Int) {
        val buffer = ByteBuffer.allocate(4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(value)
        outputStream.write(buffer.array())
    }

    @Throws(IOException::class)
    private fun writeShort(outputStream: ByteArrayOutputStream, value: Short) {
        val buffer = ByteBuffer.allocate(2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(value)
        outputStream.write(buffer.array())
    }

    @Throws(IOException::class)
    private fun writeString(outputStream: ByteArrayOutputStream, value: String) {
        outputStream.write(value.toByteArray(Charsets.UTF_8))
    }
}
