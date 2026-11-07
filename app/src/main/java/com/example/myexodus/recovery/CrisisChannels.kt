package com.example.myexodus.recovery

/**
 * The channel URIs the directory can open (plan G3). Pure strings so the mapping is testable on
 * the JVM; the screen only wraps them in an Intent. Digits-only normalization keeps a number
 * like "+254 722 178 177" dialable and makes `wa.me` accept it.
 */
object CrisisChannels {

    // RFC 3966: a leading '+' is the global-number prefix; dropping it makes the dialer treat
    // a foreign helpline as a local number. wa.me refuses the '+' instead.
    fun telUri(number: String): String? = digits(number)?.let { "tel:${plus(number)}$it" }

    fun smsUri(number: String): String? = digits(number)?.let { "smsto:${plus(number)}$it" }

    fun whatsappUri(number: String): String? = digits(number)?.let { "https://wa.me/$it" }

    fun digits(number: String): String? = number.filter(Char::isDigit).takeIf { it.isNotEmpty() }

    private fun plus(number: String): String = if (number.trimStart().startsWith("+")) "+" else ""
}
