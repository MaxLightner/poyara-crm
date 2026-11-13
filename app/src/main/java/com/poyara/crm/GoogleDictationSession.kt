package com.poyara.crm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Uses the installed Android/Google microphone recognizer directly.
 * No prerecorded-file injection: Google's recognizer on many phones ignores it.
 * Speech is recognized DURING dictation, but NO partial or final text is shown
 * until the user taps Stop. This mode produces text, not an audio backup.
 *
 * Recognition implementations may use network and may end a session after a
 * pause. Each completed session is buffered and a new session is started if
 * the user is still dictating. This still is not a continuous-call guarantee.
 */
internal class GoogleDictationSession(
    private val context: Context,
    private val onStage: (String) -> Unit,
    private val onFinished: (String?, String?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val completed = mutableListOf<String>()
    private var lastPartial = ""
    private var stopped = false
    private var done = false
    private var running = false
    private var restartsWithoutResult = 0
    private var restartTask: Runnable? = null
    private var stopTimeout: Runnable? = null
    private var lastError: String? = null

    fun start(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Must start on main thread" }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFinished(null, "سرویس تشخیص گفتار گوگل/اندروید روی گوشی فعال نیست.")
            return false
        }
        stopped = false
        done = false
        running = true
        startListening()
        return running
    }

    private fun startListening() {
        if (stopped || done) return
        runCatching { recognizer?.destroy() }
        recognizer = null
        try {
            val recognizerInstance = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = recognizerInstance
            lastPartial = ""
            recognizerInstance.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!stopped && !done) onStage("در حال دریافت گفتار؛ متن تا پایان پنهان است")
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (!stopped && !done) onStage("مکث در گفتار؛ در حال آماده‌سازی ادامه")
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (done) return
                    lastPartial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onResults(results: Bundle?) {
                    if (done) return
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    append(text.ifBlank { lastPartial })
                    lastPartial = ""
                    restartsWithoutResult = 0
                    if (stopped) complete() else scheduleRestart()
                }
                override fun onError(error: Int) {
                    if (done) return
                    if (stopped) {
                        append(lastPartial)
                        complete()
                        return
                    }
                    lastError = errorMessage(error)
                    // Some recognition providers deliver short silence timeouts;
                    // automatically reopen the mic for the next phrase.
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        restartsWithoutResult++
                        if (restartsWithoutResult <= 3) scheduleRestart(350L)
                        else fail("سرویس تشخیص گفتار چند بار متوالی پاسخ نداد. " + lastError)
                    } else fail(lastError ?: "خطای تشخیص گفتار")
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizerInstance.startListening(intent)
            onStage("در حال گوش‌دادن با سرویس گفتار گوشی؛ متن نمایش داده نمی‌شود")
        } catch (error: Exception) {
            fail("سرویس گفتار شروع نشد: " + (error.localizedMessage ?: "نامشخص"))
        }
    }

    private fun append(raw: String) {
        val clean = raw.trim()
        if (clean.isBlank()) return
        if (completed.lastOrNull() != clean) completed.add(clean)
    }

    private fun scheduleRestart(delay: Long = 200L) {
        runCatching { recognizer?.destroy() }
        recognizer = null
        if (stopped || done) { complete(); return }
        restartTask?.let { main.removeCallbacks(it) }
        val task = Runnable { if (!stopped && !done) startListening() }
        restartTask = task
        main.postDelayed(task, delay)
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stop() }
            return
        }
        if (done || !running || stopped) return
        stopped = true
        restartTask?.let { main.removeCallbacks(it) }
        restartTask = null
        onStage("ضبط پایان یافت؛ در حال دریافت متن نهایی گوگل...")
        if (recognizer == null) { complete(); return }
        try {
            recognizer?.stopListening()
        } catch (_: Exception) { complete(); return }
        val task = Runnable {
            if (!done) {
                append(lastPartial)
                complete()
            }
        }
        stopTimeout = task
        main.postDelayed(task, 7000L)
    }

    private fun complete() {
        if (done) return
        append(lastPartial)
        val text = completed.joinToString(" ").trim()
        val error = if (text.isNotBlank()) null
            else lastError ?: "متنی تشخیص داده نشد؛ سرویس گفتار گوشی پاسخ نداد."
        finish(text.takeIf { it.isNotBlank() }, error)
    }

    private fun fail(message: String) = finish(null, message)

    private fun finish(text: String?, error: String?) {
        if (done) return
        done = true
        running = false
        restartTask?.let { main.removeCallbacks(it) }
        stopTimeout?.let { main.removeCallbacks(it) }
        restartTask = null
        stopTimeout = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        onFinished(text, error)
    }

    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancel() }
            return
        }
        if (done) return
        done = true
        stopped = true
        running = false
        restartTask?.let { main.removeCallbacks(it) }
        stopTimeout?.let { main.removeCallbacks(it) }
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "سرویس گفتار این گوشی زبان فارسی را پشتیبانی نمی‌کند."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "اتصال اینترنت یا دسترسی سرویس گفتار گوگل برقرار نیست."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "دسترسی میکروفون لازم است."
        SpeechRecognizer.ERROR_AUDIO -> "امکان استفاده از میکروفون وجود ندارد."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "گفتاری دریافت نشد."
        SpeechRecognizer.ERROR_NO_MATCH -> "گفتار قابل‌تشخیص نبود."
        else -> "خطای سرویس گفتار گوشی: " + code
    }
}
