package com.poyara.crm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    const val CHANNEL_ID = "poyara_work_reminders"

    fun schedule(context: Context, reminder: WorkReminder) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("reminder_id", reminder.id)
            putExtra("entry_id", reminder.entryId)
            putExtra("title", reminder.title)
        }
        val pending = PendingIntent.getBroadcast(
            context, reminder.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val now = System.currentTimeMillis()
        val lead = TimeUnit.DAYS.toMillis(reminder.remindDaysBefore.coerceIn(0, 7).toLong())
        val requested = reminder.dueAt - lead
        val trigger = when {
            requested > now -> requested
            reminder.dueAt > now -> reminder.dueAt
            else -> now + TimeUnit.MINUTES.toMillis(1)
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    }

    fun cancel(context: Context, reminderId: Long) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, reminderId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "یادآوری‌های کاری", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "جلسه‌ها، تماس‌ها و پیگیری‌های پویارا"
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra("reminder_id", 0L)
        val entryId = intent.getLongExtra("entry_id", 0L)
        if (reminderId <= 0L || entryId <= 0L) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        ReminderScheduler.createChannel(context)
        val db = CrmDb(context.applicationContext)
        val contact = db.all().firstOrNull { it.id == entryId }
        db.close()
        val person = contact?.name.orEmpty().ifBlank { contact?.company.orEmpty() }
            .ifBlank { "مخاطب پویارا" }
        val task = intent.getStringExtra("title").orEmpty().ifBlank { "برنامه کاری" }
        val open = PendingIntent.getActivity(
            context, reminderId.toInt(),
            Intent(context, MainActivity::class.java).putExtra("record_id", entryId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_poyara)
            .setContentTitle("یادآوری پویارا · $person")
            .setContentText(task)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$task با $person"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(reminderId.toInt(), notification)
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val db = CrmDb(context.applicationContext)
        db.allReminders().filter { it.dueAt > System.currentTimeMillis() }
            .forEach { ReminderScheduler.schedule(context, it) }
        db.close()
    }
}
