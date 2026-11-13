package com.poyara.crm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Android brings this screen to foreground only if POYARA holds default-dialer role.
 * It does not replace the user's phone app just for adding a CRM contact.
 */
class PoyaraCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            val call = PoyaraCallState.call
            val number = PoyaraCallState.number
            val state = PoyaraCallState.state
            val entry = remember(number) {
                CrmDb(applicationContext).use { it.findByPhone(number) }
            }
            val picture = remember(entry?.id) {
                entry?.let { record ->
                    CrmDb(applicationContext).use { db ->
                        db.attachments(record.id).firstOrNull { it.type == "photo" }
                            ?.let { BitmapFactory.decodeFile(it.filePath) }
                    }
                }
            }
            val name = entry?.let { it.name.ifBlank { it.company } }
                ?: number.ifBlank { "تماس ناشناس" }
            val label = when (state) {
                Call.STATE_RINGING -> "تماس ورودی"
                Call.STATE_ACTIVE -> "در حال مکالمه"
                Call.STATE_DIALING, Call.STATE_CONNECTING -> "در حال شماره‌گیری"
                Call.STATE_HOLDING -> "تماس در حالت انتظار"
                else -> "تماس پایان یافت"
            }
            MaterialTheme {
                Column(Modifier.fillMaxSize().background(Color(0xFF102A32)).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly) {
                    Text("پویارا CRM · " + label, color = Color(0xFFB0D9CF),
                        style = MaterialTheme.typography.titleMedium)
                    if (picture != null) {
                        Image(picture.asImageBitmap(), contentDescription = "عکس مخاطب",
                            modifier = Modifier.size(148.dp).background(Color.DarkGray, CircleShape),
                            contentScale = ContentScale.Crop)
                    } else {
                        Surface(shape = CircleShape, color = Color(0xFF1C5A57),
                            modifier = Modifier.size(148.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(name.take(1), color = Color.White,
                                    style = MaterialTheme.typography.displayLarge)
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(name, color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                        Text(number, color = Color(0xFFD7E9E5))
                        if (!entry?.role.isNullOrBlank())
                            Text("سمت: " + entry!!.role, color = Color(0xFF9AE1CE))
                        if (!entry?.company.isNullOrBlank())
                            Text("مجموعه: " + entry!!.company, color = Color(0xFFB0D9CF))
                        if (!entry?.category.isNullOrBlank())
                            Text("گروه: " + entry!!.category, color = Color(0xFFB0D9CF))
                    }
                    if (call != null && state == Call.STATE_RINGING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Button(onClick = { PoyaraCallState.decline() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC03940))) {
                                Text("رد تماس")
                            }
                            Button(onClick = { PoyaraCallState.answer() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087866))) {
                                Text("پاسخ")
                            }
                        }
                    } else if (call != null && state != Call.STATE_DISCONNECTED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            OutlinedButton(onClick = {
                                PoyaraInCallService.current?.switchMute()
                            }) { Text(if (PoyaraCallState.muted) "وصل میکروفون" else "بی‌صدا") }
                            OutlinedButton(onClick = {
                                PoyaraInCallService.current?.speaker(true)
                            }) { Text("بلندگو") }
                        }
                        Button(onClick = { PoyaraCallState.end() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC03940))) {
                            Text("پایان تماس")
                        }
                    } else {
                        Button(onClick = { finish() }) { Text("بازگشت") }
                    }
                }
            }
        }
    }
}
