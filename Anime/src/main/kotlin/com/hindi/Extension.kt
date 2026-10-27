package com.hindi.providers

fun String.toSansSerifBold(): String = this
fun String.toSansSerifItalic(): String = this

fun String.toFlagEmoji(): String {
    if (this.length != 2) return this
    return try {
        val upper = this.uppercase()
        val first = Character.toChars(Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6)
        val second = Character.toChars(Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6)
        String(first) + String(second)
    } catch (e: Exception) {
        this
    }
}
