package com.poyara.crm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Required outgoing dial pad for an app that requests ROLE_DIALER. */
class PoyaraDialerActivity : Activity() {
    private lateinit var number: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
            setBackgroundColor(0xFFF4F7F8.toInt())
        }
        val heading = TextView(this).apply {
            text = "شماره‌گیر پویارا"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        column.addView(heading)
        number = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "شماره تماس"
            setSingleLine(true)
            setText(intent?.data?.schemeSpecificPart.orEmpty())
            textDirection = android.view.View.TEXT_DIRECTION_LTR
        }
        column.addView(number, LinearLayout.LayoutParams(-1, -2))
        val call = Button(this).apply {
            text = "تماس"
            setOnClickListener { place() }
        }
        column.addView(call)
        setContentView(column)
    }

    private fun place() {
        val value = number.text.toString().trim()
        if (value.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 51)
            return
        }
        val telecom = getSystemService(TelecomManager::class.java)
        runCatching {
            telecom.placeCall(Uri.fromParts("tel", value, null), Bundle())
            finish()
        }.onFailure {
            number.error = "تماس برقرار نشد؛ برنامه تلفن گوشی را بررسی کن"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 51 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            place()
    }
}
