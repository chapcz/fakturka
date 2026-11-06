package cz.chap.fakturka.domain

import java.time.LocalDate

/**
 * Invoice number pattern, e.g. `{RR}{NNNNN}` → 2600023 or `FV-{RRRR}-{NNN}` → FV-2026-023.
 * Tokens: `{RRRR}` / `{RR}` year, `{MM}` month, `{N…}` sequence padded to the number of N; anything else is literal.
 * The sequence restarts every year, so the pattern must contain the year and exactly one sequence token.
 */
class NumberFormat private constructor(val pattern: String, private val parts: List<Part>) {
    private sealed interface Part
    private data class Literal(val text: String) : Part
    private data object Year4 : Part
    private data object Year2 : Part
    private data object Month : Part
    private data class Sequence(val width: Int) : Part

    val usesMonth: Boolean get() = Month in parts

    fun format(date: LocalDate, sequence: Int): String = parts.joinToString("") {
        when (it) {
            is Literal -> it.text
            Year4 -> "%04d".format(date.year)
            Year2 -> "%02d".format(date.year % 100)
            Month -> "%02d".format(date.monthValue)
            is Sequence -> "%0${it.width}d".format(sequence)
        }
    }

    /** Sequence of [number] when it has this shape for [year] (any month), else null. */
    fun sequenceInYear(number: String, year: Int): Int? = match(number, year, null)

    /** Sequence of [number] when it has this shape for the year and month of [date], else null. */
    fun sequenceFor(number: String, date: LocalDate): Int? = match(number, date.year, date.monthValue)

    private fun match(number: String, year: Int, month: Int?): Int? {
        val regex = parts.joinToString("") {
            when (it) {
                is Literal -> Regex.escape(it.text)
                Year4 -> "%04d".format(year)
                Year2 -> "%02d".format(year % 100)
                Month -> month?.let { m -> "%02d".format(m) } ?: "(?:0[1-9]|1[0-2])"
                is Sequence -> "(\\d{${it.width},9})"
            }
        }.toRegex()
        return regex.matchEntire(number.trim())?.groupValues?.get(1)?.toInt()?.takeIf { it > 0 }
    }

    companion object {
        const val DEFAULT = "{RR}{NNNNN}"
        private const val MAX_VS_DIGITS = 10
        private val TOKEN = Regex("""\{([^{}]*)}""")

        /** The parsed pattern, or a localized-message key explaining why it is invalid. */
        fun parse(pattern: String): Result<NumberFormat> {
            val parts = mutableListOf<Part>()
            var last = 0
            for (m in TOKEN.findAll(pattern)) {
                if (m.range.first > last) parts += Literal(pattern.substring(last, m.range.first))
                parts += when (val t = m.groupValues[1]) {
                    "RRRR" -> Year4
                    "RR" -> Year2
                    "MM" -> Month
                    else -> if (t.isNotEmpty() && t.all { it == 'N' }) Sequence(t.length) else return invalid("ui.settings.error.numberFormat.token")
                }
                last = m.range.last + 1
            }
            if (last < pattern.length) parts += Literal(pattern.substring(last))
            if (parts.filterIsInstance<Literal>().any { '{' in it.text || '}' in it.text }) return invalid("ui.settings.error.numberFormat.token")
            if (parts.count { it is Sequence } != 1) return invalid("ui.settings.error.numberFormat.sequence")
            if (parts.none { it == Year4 || it == Year2 }) return invalid("ui.settings.error.numberFormat.year")
            val format = NumberFormat(pattern, parts)
            // The variable symbol is the digits of the number; check the longest one this pattern produces.
            val width = parts.filterIsInstance<Sequence>().single().width
            val longest = format.format(LocalDate.of(2099, 12, 31), "9".repeat(maxOf(width, 4)).toInt())
            if (variableSymbol(longest).length > MAX_VS_DIGITS) return invalid("ui.settings.error.numberFormat.vs")
            return Result.success(format)
        }

        fun of(pattern: String): NumberFormat = parse(pattern).getOrElse { parse(DEFAULT).getOrThrow() }

        /** The variable symbol of an invoice number: its digits ("FV-2026-023" → "2026023"). */
        fun variableSymbol(number: String): String = number.filter { it in '0'..'9' }

        private fun invalid(key: String) = Result.failure<NumberFormat>(IllegalArgumentException(key))
    }
}

object InvoiceNumbering {
    /** Sequence of an own invoice: by the configured pattern, or by the default one for numbers from before a change. */
    fun sequenceOf(number: String, year: Int, format: NumberFormat): Int? =
        format.sequenceInYear(number, year) ?: NumberFormat.of(NumberFormat.DEFAULT).sequenceInYear(number, year)

    fun next(date: LocalDate, existing: List<IssuedInvoice>, seller: SellerConfig): String {
        val format = NumberFormat.of(seller.numberFormat)
        val year = date.year
        val own = existing.filter { !it.external }.mapNotNull { sequenceOf(it.number, year, format) }.maxOrNull()
        val start = if (seller.numberingStartYear == year) (seller.numberingStartSequence ?: 1) - 1 else 0
        val taken = existing.map { it.number.trim() }.toSet()
        var candidate = maxOf(own ?: 0, start) + 1
        while (format.format(date, candidate) in taken) candidate++
        return format.format(date, candidate)
    }
}
