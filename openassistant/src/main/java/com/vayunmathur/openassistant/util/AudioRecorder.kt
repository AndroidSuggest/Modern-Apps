@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.openassistant.util
import kotlin.uuid.Uuid
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavRecorder(val context: Context, val outputFile: File, val scope: CoroutineScope) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun start() {
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return

        isRecording = true
        audioRecord?.startRecording()

        scope.launch(Dispatchers.IO) {
            val tempRaw = File(context.cacheDir, "temp_${System.currentTimeMillis()}.raw")
            FileOutputStream(tempRaw).use { fos ->
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) fos.write(buffer, 0, read)
                }
            }
            writeWavFile(tempRaw, outputFile)
            tempRaw.delete()
        }
    }

    fun stop() {
        isRecording = false
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                try { stop() } catch(_: Exception) {}
            }
            release()
        }
        audioRecord = null
    }

    private fun writeWavFile(rawFile: File, wavFile: File) {
        val rawData = rawFile.readBytes()
        val totalAudioLen = rawData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (16 * sampleRate * 1 / 8).toLong()

        FileOutputStream(wavFile).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(totalDataLen.toInt())
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)                 // Subchunk1 size (PCM)
            header.putShort(1.toShort())      // Audio format = PCM
            header.putShort(1.toShort())      // Num channels = mono
            header.putInt(sampleRate)
            header.putInt(byteRate.toInt())
            header.putShort(2.toShort())      // Block align
            header.putShort(16.toShort())     // Bits per sample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(totalAudioLen.toInt())
            out.write(header.array())
            out.write(rawData)
        }
    }
}

fun copyUriToFile(context: Context, uri: Uri): File? {
    val tempFile = File(context.cacheDir, "img_${System.currentTimeMillis()}_${Uuid.random()}.jpg")
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                tempFile
            } else {
                Log.e("AudioRecorder", "Copy failed: File is empty or does not exist for $uri")
                null
            }
        }
    } catch (e: Exception) {
        Log.e("AudioRecorder", "Error copying URI to file: $uri", e)
        if (tempFile.exists()) tempFile.delete()
        null
    }
}
