package com.poyara.crm

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.IOException

/**
 * Experimental Android 13+ recorded-file transcription via the installed
 * recognition service (often Google). NO simultaneous live dictation.
 *
 * IMPORTANT: EXTRA_AUDIO_SOURCE is optional. Some recognizers ignore the
 * supplied PCM and use the microphone instead. We explicitly warn in the UI;
 * this is not a guaranteed recorded-file API across Android devices.
 *
 * Original M4A is never modified. Conversion and temp-file handling are local.
 */
internal class RecordedFileTranscriber(
    private val context: Context,
    private val onStage: (String) -> Unit,
    private val onSuccess: (String) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private var finished = false
    private var recognizer: SpeechRecognizer? = null
    private var audioSource: ParcelFileDescriptor? = null
    private var temporaryPcm: File? = null
    private var timeout: Runnable? = null
    private val segments = mutableListOf<String>()

    fun start(m4a: File) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Start on main thread" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            fail("تبدیل فایل با موتور گفتار اندروید به اندروید ۱۳ یا بالاتر نیاز دارد.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            fail("سرویس تشخیص گفتار روی این گوشی موجود نیست. برنامه Google یا Speech Services را بررسی کن.")
            return
        }
        if (!m4a.isFile || m4a.length() < 64L) {
            fail("فایل ضبط‌شده پیدا نشد.")
            return
        }
        onStage("در حال آماده‌سازی فایل صوتی...")
        Thread({
            val decoded = try {
                decodeM4a(m4a)
            } catch (e: Exception) {
                handler.post { fail("خواندن فایل صوتی ممکن نشد: " + (e.localizedMessage ?: "فرمت ناسازگار")) }
                return@Thread
            }
            handler.post {
                if (cancelled) {
                    decoded.file.delete()
                } else {
                    startAndroidRecognizer(decoded)
                }
            }
        }, "poyara-audio-decode").start()
    }

    fun cancel() {
        cancelled = true
        handler.removeCallbacksAndMessages(null)
        cleanup()
    }

    private fun startAndroidRecognizer(pcm: DecodedAudio) {
        if (cancelled) {
            pcm.file.delete()
            return
        }
        temporaryPcm = pcm.file
        try {
            audioSource = ParcelFileDescriptor.open(pcm.file, ParcelFileDescriptor.MODE_READ_ONLY)
            val service = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = service
            service.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!cancelled) onStage("در حال بررسی فایل توسط سرویس گفتار گوشی...")
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    if (finished || cancelled) return
                    if (segments.isNotEmpty() && error == SpeechRecognizer.ERROR_NO_MATCH) {
                        succeed(segments.joinToString(" "))
                        return
                    }
                    val message = when (error) {
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "سرویس گفتار این گوشی زبان فارسی را پشتیبانی نمی‌کند."
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "از فایل صوتی متنی تشخیص داده نشد. ممکن است سرویس گوگل ورودی فایل را پشتیبانی نکند."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "سرویس تشخیص گفتار به اینترنت نیاز دارد یا اتصال برقرار نیست."
                        else -> "سرویس تشخیص گفتار فایل را نپذیرفت (کد خطا: " + error + ")."
                    }
                    fail(message)
                }
                override fun onResults(results: Bundle?) {
                    if (finished || cancelled) return
                    val finalText = extractText(results)
                    if (finalText.isNotBlank()) {
                        if (segments.isEmpty()) segments.add(finalText)
                        else if (segments.last() != finalText) segments.add(finalText)
                    }
                    if (segments.isNotEmpty()) succeed(segments.joinToString(" "))
                    else fail("سرویس گفتار متنی برنگرداند. ممکن است فایل صوتی پشتیبانی نشود.")
                }
                override fun onSegmentResults(segmentResults: Bundle) {
                    if (finished || cancelled) return
                    val segment = extractText(segmentResults)
                    if (segment.isNotBlank() && segments.lastOrNull() != segment) segments.add(segment)
                }
                override fun onEndOfSegmentedSession() {
                    if (finished || cancelled) return
                    if (segments.isNotEmpty()) succeed(segments.joinToString(" "))
                    else fail("جلسه تشخیص تمام شد اما متنی از فایل دریافت نشد.")
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, pcm.channels)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.sampleRate)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            onStage("فایل به سرویس تشخیص گفتار گوشی ارسال شد؛ منتظر نتیجه...")
            timeout = Runnable {
                if (!finished && !cancelled) {
                    if (segments.isNotEmpty()) succeed(segments.joinToString(" "))
                    else fail("سرویس تشخیص در زمان مشخص پاسخ نداد؛ احتمالاً دریافت فایل را پشتیبانی نمی‌کند.")
                }
            }
            handler.postDelayed(timeout!!, (pcm.durationMs * 2 + 35_000L).coerceIn(60_000L, 300_000L))
            service.startListening(intent)
        } catch (e: Exception) {
            fail("امکان شروع تشخیص گفتار وجود ندارد: " + (e.localizedMessage ?: "خطا"))
        }
    }

    private fun extractText(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()

    private fun succeed(text: String) {
        if (finished || cancelled) return
        finished = true
        cleanup()
        if (text.isBlank()) onFailure("متن قابل‌تشخیص نبود.")
        else onSuccess(text)
    }

    private fun fail(message: String) {
        if (finished || cancelled) return
        finished = true
        cleanup()
        onFailure(message)
    }

    private fun cleanup() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { audioSource?.close() }
        audioSource = null
        temporaryPcm?.delete()
        temporaryPcm = null
    }

    private data class DecodedAudio(
        val file: File,
        val sampleRate: Int,
        val channels: Int,
        val durationMs: Long
    )

    private fun decodeM4a(file: File): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val pcm = File.createTempFile("poyara_recognizer_", ".pcm", context.cacheDir)
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("ترک صوتی در فایل یافت نشد")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("فرمت صوت مشخص نیست")
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) / 1000L else 120_000L
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var inputEnded = false
            var outputEnded = false
            var bytesWritten = 0L
            val bufferInfo = MediaCodec.BufferInfo()
            pcm.outputStream().use { output ->
                while (!outputEnded) {
                    if (cancelled) throw IOException("لغو شد")
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex) ?: throw IOException("بافر ورودی موجود نیست")
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = codec.outputFormat
                            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                                throw IOException("خروجی PCM16 پشتیبانی نشد")
                            }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (index >= 0) {
                            val byteBuffer = codec.getOutputBuffer(index)
                                ?: throw IOException("بافر خروجی موجود نیست")
                            if (bufferInfo.size > 0) {
                                byteBuffer.position(bufferInfo.offset)
                                byteBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val bytes = ByteArray(bufferInfo.size)
                                byteBuffer.get(bytes)
                                output.write(bytes)
                                bytesWritten += bytes.size
                                if (bytesWritten > 96L * 1024 * 1024)
                                    throw IOException("فایل برای آزمایش تبدیل محلی بیش از حد طولانی است")
                            }
                            outputEnded = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            if (pcm.length() < 1024L) throw IOException("هیچ صدایی برای تبدیل پیدا نشد")
            return DecodedAudio(pcm, sampleRate, channels, durationMs)
        } catch (e: Exception) {
            pcm.delete()
            throw e
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
