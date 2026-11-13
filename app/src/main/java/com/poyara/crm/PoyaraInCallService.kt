package com.poyara.crm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.Person

/**
 * Separate from optional caller identification. Only runs when the user explicitly
 * selects POYARA as their default phone app (ROLE_DIALER).
 */
internal object PoyaraCallState {
    var call by mutableStateOf<Call?>(null)
    var state by mutableIntStateOf(Call.STATE_DISCONNECTED)
    var number by mutableStateOf("")
    var muted by mutableStateOf(false)

    fun answer() { call?.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun decline() { call?.reject(false, null) }
    fun end() { call?.disconnect() }
}

class PoyaraInCallService : InCallService() {
    private val channel = "poyara_active_phone_calls"
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            PoyaraCallState.state = state
            if (state == Call.STATE_DISCONNECTED) getSystemService(NotificationManager::class.java)
                .cancel(89040)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        PoyaraCallState.call?.unregisterCallback(callback)
        PoyaraCallState.call = call
        PoyaraCallState.number = call.details.handle?.schemeSpecificPart.orEmpty()
        PoyaraCallState.state = call.state
        call.registerCallback(callback)
        showCall(call)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (PoyaraCallState.call == call) {
            PoyaraCallState.call = null
            PoyaraCallState.state = Call.STATE_DISCONNECTED
            PoyaraCallState.number = ""
        }
        getSystemService(NotificationManager::class.java).cancel(89040)
        super.onCallRemoved(call)
    }

    private fun showCall(call: Call) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel,
            "تماس‌های تلفنی پویارا", NotificationManager.IMPORTANCE_HIGH))
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        val contact = runCatching {
            CrmDb(applicationContext).use { it.findByPhone(number) }
        }.getOrNull()
        val label = contact?.name?.ifBlank { contact.company }?.ifBlank { number }
            ?: number.ifBlank { "تماس تلفنی" }
        val openIntent = Intent(this, PoyaraCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val open = PendingIntent.getActivity(this, 89041, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun control(action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(this, code,
                Intent(this, PoyaraCallControlReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val incoming = call.state == Call.STATE_RINGING
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(label)
            .setContentText(contact?.role?.ifBlank { contact.company } ?: "پویارا CRM")
            .setContentIntent(open)
            .setFullScreenIntent(open, incoming)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (incoming) {
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(
                Person.Builder().setName(label).build(),
                control("reject", 89042), control("answer", 89043)))
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                "پایان تماس", control("hangup", 89044))
        }
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) manager.notify(89040, builder.build())
        // Only a default dialer receives onCallAdded; this is never launched by screening alone.
        runCatching { startActivity(openIntent) }
    }

    fun switchMute() {
        val next = !PoyaraCallState.muted
        setMuted(next)
        PoyaraCallState.muted = next
    }

    fun speaker(enabled: Boolean) {
        @Suppress("DEPRECATION")
        setAudioRoute(if (enabled) android.telecom.CallAudioState.ROUTE_SPEAKER
            else android.telecom.CallAudioState.ROUTE_EARPIECE)
    }
}

class PoyaraCallControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "answer" -> PoyaraCallState.answer()
            "reject" -> PoyaraCallState.decline()
            "hangup" -> PoyaraCallState.end()
        }
    }
}
