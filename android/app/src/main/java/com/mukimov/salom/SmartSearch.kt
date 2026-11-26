package com.mukimov.salom

import java.text.Normalizer
import java.util.Locale

internal data class SalomAccount(val id: String, val name: String, val username: String, val phone: String, val is_active: Boolean,
    val avatarUrl: String = "", val about: String? = null, val joinedAt: Long? = null)
internal enum class ContactDestination { CHAT, INVITE, UNKNOWN }
internal fun contactDestination(account: SalomAccount?): ContactDestination = when {
    account == null -> ContactDestination.UNKNOWN
    account.is_active -> ContactDestination.CHAT
    else -> ContactDestination.INVITE
}

private val letters = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'ҳ' to "h", 'қ' to "q", 'ҷ' to "j", 'ӯ' to "u", 'ӣ' to "i", 'ғ' to "gh")
internal fun normalizedSearch(value: String): String = buildString {
    value.trim().lowercase(Locale.ROOT).forEach { append(letters[it] ?: it.toString()) }
}.let { Normalizer.normalize(it, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") }

internal fun canonicalPhone(value: String): String = value.filter { it.isDigit() }.let { if (it.length == 12 && it.startsWith("992")) it.drop(3) else it }

/** Lower score sorts first. Username matching always uses a prefix, never an arbitrary substring. */
internal fun smartSearchScore(query: String, name: String, username: String = "", phone: String = ""): Int? {
    val raw = query.trim()
    val q = normalizedSearch(raw.removePrefix("@"))
    if (q.isEmpty()) return 100
    val user = normalizedSearch(username.removePrefix("@"))
    if (user.isNotEmpty() && user == q) return 0
    if (user.isNotEmpty() && user.startsWith(q)) return 1
    if (raw.startsWith("@")) return null
    val person = normalizedSearch(name)
    if (person == q) return 2
    if (person.split(Regex("\\s+")).any { it.startsWith(q) }) return 3
    val digits = raw.filter { it.isDigit() }
    if (digits.isNotEmpty() && raw.all { it.isDigit() || it in "+()- " } && phone.filter { it.isDigit() }.contains(digits)) return 4
    if (person.contains(q)) return 5
    return null
}
