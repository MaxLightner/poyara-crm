package com.poyara.crm

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.net.Uri
import android.content.ComponentName
import android.provider.Settings
import androidx.core.content.FileProvider
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort prerecorded-file trial using Android's selected recognition service.
 * Requires API 33+ EXTRA_AUDIO_SOURCE. Google and other recognizers may ignore it.
 * Recording and PCM data always stay private to this app; only the selected
 * recognition service decides whether speech is analyzed online or on-device.
 */
internal class VoiceFileTranscriber(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var scratch: File? = null
    private var grantedUri: Uri? = null
    private var grantedPackage: String? = null
    private val finished = AtomicBoolean(false)

    fun transcribe(
        audio: File,
        onStage: (String) -> Unit,
        onFinish: (String?, String?) -> Unit
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (Build.VERSION.SDK_INT < 31) {
            onFinish(null, "موتور گفتار اندرویدِ این نسخه امکان خواندن فایل ضبط‌شده را ندارد. برای اندروید ۱۱ و قدیمی‌تر به روش تبدیل دیگری نیاز است؛ فایل اصلی محفوظ است.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFinish(null, "سرویس تشخیص گفتار روی این گوشی نصب یا فعال نیست.")
            return
        }
        if (!audio.isFile || audio.length() == 0L) {
            onFinish(null, "فایل صوتی پیدا نشد.")
            return
        }
        if (Build.VERSION.SDK_INT in 31..32) {
            // Android 12 has a *legacy*, optional URI injection hint. This is not
            // guaranteed to be honoured by Google's recognition provider.
            startLegacyRecognition(audio, onStage, onFinish)
            return
        }
        onStage("آماده‌سازی فایل ضبط‌شده…")
        Thread {
            try {
                val pcm = File.createTempFile("asr_", ".pcm", context.cacheDir)
                scratch = pcm
                val format = decodeM4aToPcm(audio, pcm)
                main.post {
                    if (!finished.get()) startRecognition(pcm, format.first, format.second, onStage, onFinish)
                }
            } catch (ex: Exception) {
                main.post {
                    finish(null, "آماده‌سازی فایل صوتی ناموفق بود: " + (ex.localizedMessage ?: "فرمت پشتیبانی نشد"), onFinish)
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun startLegacyRecognition(
        audio: File,
        onStage: (String) -> Unit,
        onFinish: (String?, String?) -> Unit
    ) {
        try {
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".files", audio
            )
            grantedUri = uri
            // A RecognitionService starts through Binder, so grant its package
            // explicit URI access; intent flags alone may not propagate.
            val serviceName = Settings.Secure.getString(
                context.contentResolver, "voice_recognition_service"
            )
            val packageName = serviceName?.let { ComponentName.unflattenFromString(it)?.packageName }
            if (!packageName.isNullOrBlank()) {
                context.grantUriPermission(
                    packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                grantedPackage = packageName
            }
            val service = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = service
            service.setRecognitionListener(makeListener(onStage,onFinish))
            val intent = baseRecognitionIntent().apply {
                putExtra(RecognizerIntent.EXTRA_AUDIO_INJECT_SOURCE, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onStage("در حال آزمایش خواندن فایل در اندروید ۱۲…")
            service.startListening(intent)
        } catch (ex: Exception) {
            finish(null, "سرویس گفتار اندروید ۱۲ فایل را نپذیرفت: " + (ex.localizedMessage ?: "خطای ناشناخته"), onFinish)
        }
    }

    private fun baseRecognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun makeListener(
        onStage: (String) -> Unit,
        onFinish: (String?, String?) -> Unit
    ): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { onStage("در حال بررسی صوت ضبط‌شده…") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onStage("در حال تکمیل متن…") }
        override fun onPartialResults(partialResults: Bundle?) {} // never show partials
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            finish(null, when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    "این سرویس گفتار از فارسی پشتیبانی نمی‌کند."
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "متنی پیدا نشد. ممکن است سرویس گوشی فایل ضبط‌شده را نپذیرد."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "تشخیص گفتار به اینترنت نیاز دارد یا اتصال قطع است. فایل اصلی محفوظ است."
                SpeechRecognizer.ERROR_AUDIO ->
                    "فایل توسط سرویس گفتار پشتیبانی نشد."
                else ->
                    "سرویس گفتار فایل را تبدیل نکرد (کد " + error + "). فایل محفوظ است."
            }, onFinish)
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isBlank()) finish(null,"متنی از فایل دریافت نشد.",onFinish)
            else finish(text,null,onFinish)
        }
    }

    private fun startRecognition(
        pcm: File, sampleRate: Int, channels: Int,
        onStage: (String) -> Unit, onFinish: (String?, String?) -> Unit
    ) {
        try {
            descriptor = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
            val service = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = service
            service.setRecognitionListener(makeListener(onStage,onFinish))
            val intent = baseRecognitionIntent().apply {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channels)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }
            onStage("در حال درخواست متن از سرویس تشخیص گفتار…")
            service.startListening(intent)
        } catch (ex: Exception) {
            finish(null, "سرویس تشخیص گفتار شروع نشد: " + (ex.localizedMessage ?: "خطای نامشخص"), onFinish)
        }
    }

    private fun finish(text: String?, error: String?, callback: (String?, String?) -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { descriptor?.close() }
        descriptor = null
        runCatching { scratch?.delete() }
        scratch = null
        grantedPackage?.let { pkg -> grantedUri?.let { uri ->
            runCatching { context.revokeUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        } }
        grantedPackage = null
        grantedUri = null
        callback(text, error)
    }

    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancel() }
            return
        }
        if (finished.compareAndSet(false, true)) {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            runCatching { descriptor?.close() }
            runCatching { scratch?.delete() }
            grantedPackage?.let { pkg -> grantedUri?.let { uri ->
                runCatching { context.revokeUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            } }
        }
        recognizer = null
        descriptor = null
        scratch = null
        grantedPackage = null
        grantedUri = null
    }

    private fun decodeM4aToPcm(input: File, target: File): Pair<Int, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; break
                }
            }
            require(track >= 0) { "ترک صوتی وجود ندارد" }
            extractor.selectTrack(track)
            val source = extractor.getTrackFormat(track)
            val mime = source.getString(MediaFormat.KEY_MIME) ?: error("فرمت مشخص نیست")
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(source, null, null, 0)
            decoder.start()
            var inputEnded = false
            var outputEnded = false
            var sampleRate = source.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = source.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var bytesWritten = 0L
            val info = MediaCodec.BufferInfo()
            FileOutputStream(target).use { output ->
                while (!outputEnded) {
                    if (!inputEnded) {
                        val slot = decoder.dequeueInputBuffer(10_000)
                        if (slot >= 0) {
                            val buffer = decoder.getInputBuffer(slot) ?: error("ورودی رمزگشا موجود نیست")
                            buffer.clear()
                            val count = extractor.readSampleData(buffer, 0)
                            if (count < 0) {
                                decoder.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(slot,0,count,extractor.sampleTime,0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val slot = decoder.dequeueOutputBuffer(info,10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val actual = decoder.outputFormat
                            sampleRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                actual.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT) { "خروجی PCM16 نیست" }
                            require(channels in 1..2) { "تعداد کانال صوت پشتیبانی نمی‌شود" }
                        }
                        in 0..Int.MAX_VALUE -> {
                            val bytes = decoder.getOutputBuffer(slot)
                            if (info.size > 0 && bytes != null) {
                                bytes.position(info.offset)
                                bytes.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size)
                                bytes.get(chunk)
                                if (channels == 2) {
                                    // Downmix interleaved stereo 16-bit signed samples to mono.
                                    val mono = ByteArray(chunk.size / 2)
                                    var from = 0
                                    var to = 0
                                    while (from + 3 < chunk.size) {
                                        val a = ((chunk[from + 1].toInt() shl 8) or (chunk[from].toInt() and 255)).toShort().toInt()
                                        val b = ((chunk[from + 3].toInt() shl 8) or (chunk[from + 2].toInt() and 255)).toShort().toInt()
                                        val avg = ((a.toLong() + b.toLong()) / 2L).toInt()
                                        mono[to] = avg.toByte()
                                        mono[to + 1] = (avg shr 8).toByte()
                                        from += 4; to += 2
                                    }
                                    output.write(mono)
                                    bytesWritten += mono.size
                                } else {
                                    output.write(chunk)
                                    bytesWritten += chunk.size
                                }
                            }
                            outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(slot,false)
                        }
                    }
                }
            }
            require(bytesWritten > 0) { "صدای قابل تشخیص استخراج نشد" }
            return sampleRate to 1 // output is always mono
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
