package com.poyara.crm

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Caller identification ONLY: does not answer, reject or control phone calls.
 * The stock Android phone UI continues to handle every incoming call.
 */
class CrmCallerInfoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryId = intent.getLongExtra("record_id", 0L)
        val calledNumber = intent.getStringExtra("caller_number").orEmpty()
        val direction = intent.getStringExtra("direction").orEmpty()
        val db = CrmDb(applicationContext)
        val entry = try { db.all().firstOrNull { it.id == entryId } }
            finally { db.close() }
        val image = entry?.let { record ->
            CrmDb(applicationContext).use { imageDb ->
                imageDb.attachments(record.id)
                    .firstOrNull { it.type == "photo" }
                    ?.let { attachment -> BitmapFactory.decodeFile(attachment.filePath) }
            }
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF08685F),
                background = Color(0xFFF4F7F8)
            )) {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .background(Color(0xFFF4F7F8))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text("پویارا CRM · شناسایی تماس کاری" +
                            if (direction.isBlank()) "" else " · " + direction,
                        color = Color(0xFF08685F),
                        style = MaterialTheme.typography.titleMedium)
                    if (image != null) {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = "عکس مخاطب",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(130.dp)
                                .background(Color(0xFFE0EFE9), CircleShape)
                        )
                    } else {
                        Surface(shape = CircleShape, color = Color(0xFFD8F3E9),
                            modifier = Modifier.size(130.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(entry?.name.orEmpty().take(1).ifBlank { "☎" },
                                    style = MaterialTheme.typography.displayLarge,
                                    color = Color(0xFF08685F))
                            }
                        }
                    }
                    Text(entry?.let { it.name.ifBlank { it.company } } ?: "مخاطب ثبت‌شده",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold)
                    Text(calledNumber.ifBlank { entry?.phone.orEmpty() },
                        style = MaterialTheme.typography.titleMedium)
                    Card(shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            entry?.company?.takeIf { it.isNotBlank() }?.let {
                                Text("مجموعه: " + it)
                            }
                            entry?.role?.takeIf { it.isNotBlank() }?.let {
                                Text("سمت: " + it)
                            }
                            entry?.category?.takeIf { it.isNotBlank() }?.let {
                                Text("دسته‌بندی: " + it)
                            }
                            entry?.note?.takeIf { it.isNotBlank() }?.let {
                                Text("یادداشت: " + it.take(240), maxLines = 5,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text("پاسخ، رد و قطع تماس همچنان در صفحه تماس معمولی گوشی انجام می‌شود.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { finish() }) {
                            Text("بازگشت به تماس گوشی")
                        }
                        Button(onClick = {
                            val open = Intent(this@CrmCallerInfoActivity,
                                MainActivity::class.java).apply {
                                putExtra("record_id", entryId)
                                putExtra("complete_profile",
                                    entry != null && (entry.role.isBlank() ||
                                        entry.category.isBlank()))
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            startActivity(open)
                            finish()
                        }, enabled = entryId > 0L) {
                            Text("پرونده کامل")
                        }
                    }
                }
            }
        }
    }
}
