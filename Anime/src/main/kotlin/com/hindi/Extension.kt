package com.hindi.providers

fun String.toSansSerifBold(): String {
    val map = mapOf(
        'A' to '𝗔','B' to '𝗕','C' to '𝗖','D' to '𝗗','E' to '𝗘','F' to '𝗙',
        'G' to '𝗚','H' to '𝗛','I' to '𝗜','J' to '𝗝','K' to '𝗞','L' to '𝗟',
        'M' to '𝗠','N' to '𝗡','O' to '𝗢','P' to '𝗣','Q' to '𝗤','R' to '𝗥',
        'S' to '𝗦','T' to '𝗧','U' to '𝗨','V' to '𝗩','W' to '𝗪','X' to '𝗫',
        'Y' to '𝗬','Z' to '𝗭',
        'a' to '𝗮','b' to '𝗯','c' to '𝗰','d' to '𝗱','e' to '𝗲','f' to '𝗳',
        'g' to '𝗴','h' to '𝗵','i' to '𝗶','j' to '𝗷','k' to '𝗸','l' to '𝗹',
        'm' to '𝗺','n' to '𝗻','o' to '𝗼','p' to '𝗽','q' to '𝗾','r' to '𝗿',
        's' to '𝘀','t' to '𝘁','u' to '𝘂','v' to '𝘃','w' to '𝘄','x' to '𝘅',
        'y' to '𝘆','z' to '𝘇'
    )
    return this.map { map[it] ?: it }.joinToString("")
}

fun String.toSansSerifItalic(): String {
    val map = mapOf(
        'A' to '𝘈','B' to '𝘉','C' to '𝘊','D' to '𝘋','E' to '𝘌','F' to '𝘍',
        'G' to '𝘎','H' to '𝘏','I' to '𝘐','J' to '𝘑','K' to '𝘒','L' to '𝘓',
        'M' to '𝘔','N' to '𝘕','O' to '𝘖','P' to '𝘗','Q' to '𝘘','R' to '𝘙',
        'S' to '𝘚','T' to '𝘛','U' to '𝘜','V' to '𝘝','W' to '𝘞','X' to '𝘟',
        'Y' to '𝘠','Z' to '𝘡',
        'a' to '𝘢','b' to '𝘣','c' to '𝘤','d' to '𝘥','e' to '𝘦','f' to '𝘧',
        'g' to '𝘨','h' to '𝘩','i' to '𝘪','j' to '𝘫','k' to '𝘬','l' to '𝘭',
        'm' to '𝘮','n' to '𝘯','o' to '𝘰','p' to '𝘱','q' to '𝘲','r' to '𝘳',
        's' to '𝘴','t' to '𝘵','u' to '𝘶','v' to '𝘷','w' to '𝘸','x' to '𝘹',
        'y' to '𝘺','z' to '𝘻'
    )
    return this.map { map[it] ?: it }.joinToString("")
}

fun String.toFlagEmoji(): String {
    if (this.length != 2) return this
    val upper = this.uppercase()
    return try {
        val first = Character.toChars(Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6)
        val second = Character.toChars(Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6)
        String(first) + String(second)
    } catch (e: Exception) { this }
}
