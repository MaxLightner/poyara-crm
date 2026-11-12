package com.poyara.crm

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Records are locally durable before networking. WorkManager persists retry
 * scheduling across process death and starts work once Internet reconnects.
 */
internal object VoiceQueue {
    private const val TAG = "poyara_voice_upload"

    fun record(context: Context, file: File, entryId: Long) {
        val db = CrmDb(context.applicationContext)
        try {
            val job = db.queueVoiceUpload(file.absolutePath, entryId)
            if (VoiceServerSettings(context).configured && db.voiceReview(file.absolutePath) == null) {
                enqueue(context, job, replace = false)
            }
        } finally { db.close() }
    }

    fun schedulePending(context: Context) {
        if (!VoiceServerSettings(context).configured) return
        val db = CrmDb(context.applicationContext)
        try {
            db.pendingVoiceUploads().forEach { job ->
                if (db.voiceReview(job.audioPath) == null)
                    enqueue(context, job, replace = false)
            }
        } finally { db.close() }
    }

    fun retry(context: Context, audioPath: String) {
        if (!VoiceServerSettings(context).configured) return
        val db = CrmDb(context.applicationContext)
        try {
            db.voiceUpload(audioPath) ?: db.queueVoiceUpload(audioPath)
            db.retryVoiceUpload(audioPath)
            db.voiceUpload(audioPath)?.let { enqueue(context, it, replace = true) }
        } finally { db.close() }
    }

    private fun enqueue(context: Context, job: VoiceUploadJob, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<VoiceUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .setInputData(workDataOf("audio_path" to job.audioPath))
            .addTag(TAG)
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "poyara_voice_" + job.jobId,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }
}

internal class VoiceUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val path = inputData.getString("audio_path") ?: return Result.failure()
        val db = CrmDb(applicationContext)
        try {
            val job = db.voiceUpload(path) ?: return Result.success()
            if (job.status == "review" || db.voiceReview(path) != null) {
                db.markVoiceUpload(path, "review")
                return Result.success()
            }
            val file = File(path)
            if (!file.isFile) {
                db.markVoiceUpload(path, "failed", "فایل صوتی روی گوشی پیدا نشد")
                return Result.failure()
            }
            // No email account: only this device's explicitly paired HTTPS server.
            if (!VoiceServerSettings(applicationContext).configured) {
                db.markVoiceUpload(path, "pending", "سرور صوتی هنوز تنظیم نشده")
                return Result.success()
            }
            db.markVoiceUpload(path, "uploading")
            db.incrementVoiceAttempts(path)
            val candidates = db.all().filter { it.id == job.entryId }.take(1)
            val response: JSONObject = try {
                VoiceAgentClient(applicationContext).analyze(file, job.jobId, candidates) { stage, percent ->
                    val detail = when(stage) {
                        "uploading" -> "ارسال فایل: " + (percent ?: 0) + "٪"
                        "queued" -> "فایل ارسال شد؛ در صف تحلیل سرور"
                        "transcribing" -> "سرور: تبدیل گفتار به متن"
                        "extracting" -> "سرور: استخراج مخاطب و قرار از متن"
                        "complete" -> "نتیجه برای بازبینی آماده است"
                        else -> "سرور در حال پردازش است"
                    }
                    db.markVoiceUpload(path, "uploading", detail)
                }
            } catch (error: VoiceHttpException) {
                return when (error.statusCode) {
                    400, 413, 415, 422 -> {
                        db.markVoiceUpload(path, "failed", error.message.orEmpty())
                        Result.failure()
                    }
                    401, 403 -> {
                        db.markVoiceUpload(path, "failed", "کد اتصال سرور معتبر نیست")
                        Result.success()
                    }
                    else -> {
                        db.markVoiceUpload(path, "retry", error.message.orEmpty())
                        Result.retry()
                    }
                }
            } catch (error: IOException) {
                db.markVoiceUpload(path, "retry", error.message.orEmpty())
                return Result.retry()
            } catch (error: Exception) {
                db.markVoiceUpload(path, "retry", error.message.orEmpty())
                return Result.retry()
            }
            db.saveVoiceReview(job.jobId, path, response.toString())
            db.markVoiceUpload(path, "review")
            return Result.success()
        } finally {
            db.close()
        }
    }
}
