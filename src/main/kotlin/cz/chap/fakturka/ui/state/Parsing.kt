package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.money
import java.math.BigDecimal

private val DECIMAL = Regex("""\d+(\.\d+)?""")

/** True when the value has at most two meaningful decimal places. */
internal fun BigDecimal.hasMoneyScale(): Boolean = stripTrailingZeros().scale() <= 2

/** Parses "1250.00" or "1 250,00" into a non-negative decimal; null when blank or malformed. */
fun parseNonNegative(text: String): BigDecimal? {
    val normalized = text.filterNot { it.isWhitespace() || it == '\u00A0' }.replace(',', '.')
    if (!DECIMAL.matches(normalized)) return null
    return BigDecimal(normalized)
}

/** Like [parseNonNegative] but the result must be strictly positive. */
fun parsePositiveDecimal(text: String): BigDecimal? = parseNonNegative(text)?.takeIf { it.signum() > 0 }

/** Non-negative amount with at most two decimals (more are rejected, not rounded), at scale 2; null otherwise. */
fun parseMoney(text: String): BigDecimal? = parseNonNegative(text)?.takeIf { it.hasMoneyScale() }?.money()
