package cz.chap.fakturka.i18n

import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

object Messages {
    val CS: Locale = Locale.of("cs")
    val EN: Locale = Locale.of("en")

    private const val BUNDLE = "i18n.messages"

    /** Throws [MissingResourceException] when [key] is unknown. */
    fun get(key: String, locale: Locale = CS): String =
        ResourceBundle.getBundle(BUNDLE, locale).getString(key)
}
