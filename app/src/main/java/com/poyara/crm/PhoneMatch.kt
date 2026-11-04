package com.poyara.crm

/** Keeps business numbers separate from system Contacts, while matching 09 / +98 / 0098. */
fun normalizePhone(input: String): String {
    val digits = input.mapNotNull { char ->
        when (char) {
            in '0'..'9' -> char
            in '۰'..'۹' -> ('0'.code + (char.code - '۰'.code)).toChar()
            in '٠'..'٩' -> ('0'.code + (char.code - '٠'.code)).toChar()
            else -> null
        }
    }.joinToString("")
    if (digits.isBlank()) return ""
    return when {
        digits.startsWith("0098") && digits.length >= 13 -> "0" + digits.drop(4)
        digits.startsWith("98") && digits.length >= 11 -> "0" + digits.drop(2)
        digits.length == 10 && digits.startsWith("9") -> "0$digits"
        else -> digits
    }
}
