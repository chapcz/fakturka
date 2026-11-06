package cz.chap.fakturka.domain

import java.math.BigDecimal
import java.math.RoundingMode

fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

fun BigDecimal.wholeCrowns(): BigDecimal = setScale(0, RoundingMode.HALF_UP)

fun String.toMoney(): BigDecimal = BigDecimal(trim()).money()

fun formatAmount(v: BigDecimal): String {
    val plain = v.money().abs().toPlainString()
    val (whole, fraction) = plain.split('.')
    val grouped = whole.reversed().chunked(3).joinToString(" ").reversed()
    val sign = if (v.money().signum() < 0) "-" else ""
    return "$sign$grouped.$fraction"
}
