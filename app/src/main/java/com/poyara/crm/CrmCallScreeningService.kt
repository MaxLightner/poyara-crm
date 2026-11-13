package com.poyara.crm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.app.NotificationCompat

/**
 * Opt-in caller context, not a dialer and never blocks calls.
 * Android invokes it only after the user selects POYARA as caller-ID / screening app.
 */
class CrmCallScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val incoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            details.callDirection == Call.Details.DIRECTION_INCOMING
        } else true

        // Telecom requires a prompt decision for incoming calls; always allow.
        if (incoming) {
            respondToCall(details, CallResponse.Builder().build())
        }

        val number = details.handle?.schemeSpecificPart ?: return
        val db = CrmDb(applicationContext)
        try {
            val match = db.findByPhone(number) ?: return
            val direction = if (incoming) "ورودی" else "خروجی"
            db.addCallEvent(match.id, direction, number)
            showInfo(match, direction, number)
        } finally {
            db.close()
        }
    }

    private fun showInfo(entry: ContactEntry, direction: String, number: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = getSystemService(NotificationManager::class.java)
        val channelId = "poyara_caller_context_v2"
        manager.createNotificationChannel(
            NotificationChannel(channelId, "اطلاعات تماس کاری", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = Intent(this, CrmCallerInfoActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("record_id", entry.id)
            putExtra("caller_number", number)
            putExtra("direction", direction)
        }
        val pending = PendingIntent.getActivity(
            this, entry.id.toInt(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = entry.name.ifBlank { entry.company.ifBlank { entry.phone } }
        val facts = listOfNotNull(
            entry.role.takeIf { it.isNotBlank() }?.let { "سمت: " + it },
            entry.company.takeIf { it.isNotBlank() }?.let { "مجموعه: " + it },
            entry.category.takeIf { it.isNotBlank() }?.let { "حوزه: " + it }
        )
        val missing = listOfNotNull(
            "سمت".takeIf { entry.role.isBlank() },
            "گروه فعالیت".takeIf { entry.category.isBlank() }
        )
        val summary = if (missing.isNotEmpty())
            "اطلاعات ناقص: " + missing.joinToString("، ") + " · " +
                facts.joinToString(" · ")
        else facts.joinToString(" · ")
            .ifBlank { "مشتری ثبت‌شده در پویارا" }
        val info = listOf(
            "شماره: " + number,
            facts.joinToString("\n"),
            entry.note.takeIf { it.isNotBlank() }?.let { "یادداشت: " + it.take(250) }.orEmpty()
        ).filter { it.isNotBlank() }.joinToString("\n")
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("تماس کاری $direction · $title")
            .setContentText(summary.take(110))
            .setStyle(NotificationCompat.BigTextStyle().bigText(info))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(10000 + (entry.id % 100000).toInt(), notification)
    }
}
