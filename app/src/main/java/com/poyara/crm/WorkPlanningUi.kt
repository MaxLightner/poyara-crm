package com.poyara.crm

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ReminderComposer(
    db: CrmDb,
    entryId: Long,
    refreshKey: Int,
    ensureContact: () -> Long,
    onSaved: () -> Unit,
    onRequestNotification: () -> Unit
) {
    val context = LocalContext.current
    var dueAt by remember(entryId) { mutableLongStateOf(0L) }
    var title by remember(entryId) { mutableStateOf("") }
    var daysBefore by remember(entryId) { mutableIntStateOf(2) }
    var daysMenu by remember { mutableStateOf(false) }
    val dateLabel = if (dueAt > 0L) {
        SimpleDateFormat("EEEE d MMM yyyy · HH:mm", Locale("fa", "IR")).format(Date(dueAt))
    } else "انتخاب روز و ساعت"
    val existing = remember(entryId, refreshKey) {
        if (entryId > 0L) db.allReminders().filter { it.entryId == entryId }
            .sortedBy { it.dueAt }.take(4) else emptyList()
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("یادآوری و قرار کاری", fontWeight = FontWeight.Bold)
            // Existing events first: delete/edit are never buried underneath the save FAB.
            if (existing.isNotEmpty()) {
                Text("قرارهای ثبت‌شده برای این مخاطب", fontWeight = FontWeight.SemiBold)
                existing.forEach { reminder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(reminder.title, fontWeight = FontWeight.Medium)
                            Text(
                                SimpleDateFormat("EEE d MMM · HH:mm", Locale("fa", "IR")).format(Date(reminder.dueAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        TextButton(onClick = {
                            ReminderScheduler.cancel(context, reminder.id)
                            db.deleteReminder(reminder.id)
                            onSaved()
                        }) { Text("حذف") }
                    }
                }
            }
            if (existing.isNotEmpty()) {
                androidx.compose.material3.HorizontalDivider()
                Text("افزودن قرار جدید", style = MaterialTheme.typography.titleSmall)
            }
            OutlinedButton(
                onClick = {
                    val now = Calendar.getInstance()
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            val selected = Calendar.getInstance().apply {
                                set(Calendar.DAY_OF_MONTH, 1)
                                set(Calendar.YEAR, year)
                                set(Calendar.MONTH, month)
                                set(Calendar.DAY_OF_MONTH, day)
                            }
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    selected.set(Calendar.HOUR_OF_DAY, hour)
                                    selected.set(Calendar.MINUTE, minute)
                                    selected.set(Calendar.SECOND, 0)
                                    selected.set(Calendar.MILLISECOND, 0)
                                    dueAt = selected.timeInMillis
                                },
                                now.get(Calendar.HOUR_OF_DAY),
                                now.get(Calendar.MINUTE),
                                true
                            ).show()
                        },
                        now.get(Calendar.YEAR),
                        now.get(Calendar.MONTH),
                        now.get(Calendar.DAY_OF_MONTH)
                    ).apply {
                        datePicker.minDate = System.currentTimeMillis() - 1000L
                    }.show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(dateLabel) }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("موضوع کار؛ مثلاً جلسه یا تماس") },
                placeholder = { Text("جلسه ساعت ۳:۳۰ با مدیر مجموعه") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("یادآوری قبل از موعد:")
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Box {
                    OutlinedButton(onClick = { daysMenu = true }) {
                        Text(if (daysBefore == 0) "همان روز" else "$daysBefore روز قبل")
                    }
                    DropdownMenu(expanded = daysMenu, onDismissRequest = { daysMenu = false }) {
                        listOf(0, 1, 2, 3, 5, 7).forEach { days ->
                            DropdownMenuItem(
                                text = { Text(if (days == 0) "همان روز" else "$days روز قبل") },
                                onClick = { daysBefore = days; daysMenu = false }
                            )
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val contactId = ensureContact()
                    when {
                        contactId <= 0L -> Toast.makeText(context, "ابتدا نام و شماره مخاطب را کامل کن", Toast.LENGTH_SHORT).show()
                        dueAt <= System.currentTimeMillis() -> Toast.makeText(context, "زمان قرار باید در آینده باشد", Toast.LENGTH_SHORT).show()
                        title.isBlank() -> Toast.makeText(context, "موضوع قرار را بنویس", Toast.LENGTH_SHORT).show()
                        else -> {
                            val id = db.addReminder(contactId, title.trim(), dueAt, daysBefore)
                            val reminder = db.allReminders(includeDone = true).first { it.id == id }
                            ReminderScheduler.createChannel(context)
                            ReminderScheduler.schedule(context, reminder)
                            onRequestNotification()
                            dueAt = 0L
                            title = ""
                            onSaved()
                            Toast.makeText(context, "یادآوری ثبت شد", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ثبت یادآوری") }


        }
    }
}

@Composable
fun WeeklyAgenda(
    db: CrmDb,
    allEntries: List<ContactEntry>,
    refreshKey: Int,
    onOpenContact: (ContactEntry) -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    var showOnlyWeek by remember { mutableStateOf(false) }
    // Re-read database on refresh and keep next week's events visible by default.
    val reminders = remember(refreshKey, showOnlyWeek) {
        AgendaFilters.visible(
            db.allReminders(includeDone = true),
            System.currentTimeMillis(),
            showOnlyWeek
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("برنامه کاری", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showOnlyWeek, onClick = { showOnlyWeek = false },
                label = { Text("همه قرارهای پیش‌رو") }
            )
            FilterChip(
                selected = showOnlyWeek, onClick = { showOnlyWeek = true },
                label = { Text("فقط این هفته") }
            )
        }
        Text(
            if (showOnlyWeek) "قرارهای هفته جاری، از شنبه تا جمعه"
            else "قرارهای آینده حتی اگر هفته بعد یا ماه آینده باشند",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        if (reminders.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Text(if (showOnlyWeek) "برای این هفته برنامه‌ای ثبت نشده است؛ بخش «همه قرارهای پیش‌رو» را ببین."
                    else "قرار آینده‌ای ثبت نشده است؛ پس از ثبت قرار، همین‌جا نمایش داده می‌شود.",
                    Modifier.padding(18.dp))
            }
        } else {
            reminders.groupBy {
                SimpleDateFormat("EEEE · d MMMM", Locale("fa", "IR")).format(Date(it.dueAt))
            }.forEach { (day, items) ->
                Text(day, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                items.forEach { reminder ->
                    val contact = allEntries.firstOrNull { it.id == reminder.entryId }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = reminder.done,
                                onCheckedChange = { checked ->
                                    db.completeReminder(reminder.id, checked)
                                    if (checked) {
                                        ReminderScheduler.cancel(context, reminder.id)
                                    } else {
                                        db.allReminders(includeDone = true)
                                            .firstOrNull { it.id == reminder.id }
                                            ?.let { ReminderScheduler.schedule(context, it) }
                                    }
                                    onChanged()
                                }
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(reminder.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    SimpleDateFormat("HH:mm", Locale("fa", "IR")).format(Date(reminder.dueAt)) +
                                        " · " + (contact?.name?.ifBlank { contact.company } ?: "مخاطب حذف‌شده"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                contact?.company?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (contact != null) {
                                    TextButton(onClick = { onOpenContact(contact) }) {
                                        Text("پرونده")
                                    }
                                }
                                TextButton(onClick = {
                                    ReminderScheduler.cancel(context, reminder.id)
                                    db.deleteReminder(reminder.id)
                                    onChanged()
                                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
}
