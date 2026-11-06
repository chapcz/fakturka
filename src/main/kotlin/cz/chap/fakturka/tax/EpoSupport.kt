package cz.chap.fakturka.tax

import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.domain.normalizeVatId
import cz.chap.fakturka.domain.totals
import java.math.BigDecimal
import java.math.RoundingMode

// Helpers shared by the EPO form builders (KH, DP, SH).

internal fun veta(name: String, vararg attrs: Pair<String, String>) = EpoElement(name, attrs.toList())

/** Stored VAT IDs may carry spaces or lower case ("cz 11223344"); the forms want the bare digits ("11223344"). */
internal fun withoutPrefix(dic: String): String {
    val normalized = normalizeVatId(dic)
    return if (normalized.length >= 2 && normalized.take(2).all(Char::isLetter)) normalized.substring(2) else normalized
}

/** EU invoices of one customer (one SH VetaR); [wholeBase] is the rounded value SH reports for it. */
internal class EuSupply(val vatId: String, val invoices: List<IssuedInvoice>) {
    val wholeBase: BigDecimal = invoices.fold(BigDecimal.ZERO) { acc, inv -> acc + inv.totals.base }.setScale(0, RoundingMode.HALF_UP)
}

/** EU invoices grouped by normalised customer VAT ID. DP row 21 is the sum of [EuSupply.wholeBase], so it equals the SH total. */
internal fun euSupplies(issued: List<IssuedInvoice>): List<EuSupply> =
    issued.filter { it.customer.type == ContactType.EU }
        .groupBy { normalizeVatId(it.customer.dic.orEmpty()) }
        .map { (vatId, invoices) -> EuSupply(vatId, invoices) }

// EPO data value (upper case, as in the official export), not UI text.
private const val EPO_COUNTRY = "ČESKÁ REPUBLIKA"

// c_pop is a decimal in the schemas; "742/15B" is split into c_pop and c_orient (text, at most 4 characters).
private val HOUSE_NUMBER = Regex("""(\d{1,6})(?:/(\S{1,4}))?""")

/** VetaP as used by DPHDP3 and DPHSHV (differs from the KH variant); DPHSHV has no phone and e-mail. */
internal fun vetaPDp(seller: SellerConfig, withContact: Boolean = true): EpoElement {
    val a = seller.address
    val house = HOUSE_NUMBER.matchEntire(a.houseNumber.trim())
    val attrs = buildList {
        if (house != null) {
            add("c_pop" to house.groupValues[1])
            house.groups[2]?.let { add("c_orient" to it.value) }
        }
        if (withContact) add("c_telef" to seller.phone.orEmpty())
        add("c_ufo" to seller.taxOfficeCode)
        if (seller.taxOfficeBranch.isNotBlank()) add("c_pracufo" to seller.taxOfficeBranch)
        add("dic" to withoutPrefix(seller.dic))
        if (withContact) add("email" to seller.email)
        add("jmeno" to seller.firstName); add("naz_obce" to a.city); add("prijmeni" to seller.lastName)
        add("psc" to a.postcode); add("stat" to EPO_COUNTRY); add("titul" to seller.titles); add("typ_ds" to "F")
        add("ulice" to if (house != null) a.street else "${a.street} ${a.houseNumber}".trim())
    }
    return EpoElement("VetaP", attrs)
}
