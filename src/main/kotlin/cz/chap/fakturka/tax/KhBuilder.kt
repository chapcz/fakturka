package cz.chap.fakturka.tax

import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.domain.VatAmount
import cz.chap.fakturka.domain.VatRate
import cz.chap.fakturka.domain.totals
import java.math.BigDecimal
import java.math.BigDecimal.ZERO
import java.time.LocalDate

/** Builds the Kontrolní hlášení (DPHKH1) element tree for one quarter. */
object KhBuilder {
    private val THRESHOLD = BigDecimal("10000.00")
    private const val COUNTRY = "Česká republika"

    private class Sums(var base1: BigDecimal = ZERO, var vat1: BigDecimal = ZERO, var base2: BigDecimal = ZERO, var vat2: BigDecimal = ZERO) {
        fun add(rate: VatRate, base: BigDecimal, vat: BigDecimal) {
            when (rate) {
                VatRate.STANDARD -> { base1 += base; vat1 += vat }
                VatRate.REDUCED -> { base2 += base; vat2 += vat }
                VatRate.ZERO -> Unit
            }
        }
    }

    fun build(seller: SellerConfig, data: QuarterData, filingDate: LocalDate): EpoElement {
        val children = mutableListOf<EpoElement>()
        children += veta("VetaD", "k_uladis" to "DPH", "khdph_forma" to "B", "dokument" to "KH1",
            "rok" to data.quarter.year.toString(), "ctvrt" to data.quarter.quarter.toString(), "d_poddp" to EpoFormat.khDate(filingDate))
        children += vetaP(seller)

        val a5 = Sums()
        val domestic = Sums()
        for (inv in data.issued.filter { it.customer.type == ContactType.DOMESTIC }) {
            val s = Sums()
            inv.totals.vatLines.forEach { s.add(it.rate, it.base, it.vat); domestic.add(it.rate, it.base, it.vat) }
            if (inv.customer.hasCzVatId && inv.totals.total > THRESHOLD) {
                children += vetaA4(inv, s)
            } else {
                inv.totals.vatLines.forEach { a5.add(it.rate, it.base, it.vat) }
            }
        }
        children += veta("VetaA5", *rates(a5))

        val b3 = Sums()
        val receivedSums = Sums()
        for (r in data.received) {
            val s = Sums()
            r.amounts.forEach { (rate, a: VatAmount) -> s.add(rate, a.base, a.vat); receivedSums.add(rate, a.base, a.vat) }
            val dic = r.supplierDic?.takeIf { it.isNotBlank() }
            if (dic != null && r.total > THRESHOLD) {
                children += veta("VetaB2", "dic_dod" to withoutPrefix(dic), "c_evid_dd" to r.documentNumber,
                    "dppd" to EpoFormat.khDate(r.taxDate), *rates(s), "pomer" to "N", "zdph_44" to "N", "c_radku" to "1")
            } else {
                r.amounts.forEach { (rate, a) -> b3.add(rate, a.base, a.vat) }
            }
        }
        children += veta("VetaB3", *rates(b3))

        children += veta("VetaC",
            "obrat23" to EpoFormat.amount(domestic.base1), "obrat5" to EpoFormat.amount(domestic.base2),
            "pln23" to EpoFormat.amount(receivedSums.base1), "pln5" to EpoFormat.amount(receivedSums.base2),
            "rez_pren23" to "0", "rez_pren5" to "0", "pln_rez_pren" to "0", "celk_zd_a2" to "0")
        return EpoElement("DPHKH1", listOf("verzePis" to "01.02"), children)
    }

    private fun vetaP(seller: SellerConfig) = veta("VetaP", *listOf(
        "zkrobchjm" to seller.name, "dic" to withoutPrefix(seller.dic), "typ_ds" to "F",
        "opr_jmeno" to "", "opr_prijmeni" to "", "opr_postaveni" to "", "sest_jmeno" to "", "sest_prijmeni" to "", "sest_telef" to "",
        "ulice" to "${seller.address.street} ${seller.address.houseNumber}".trim(),
        "naz_obce" to seller.address.city, "psc" to seller.address.postcode, "stat" to COUNTRY,
        "c_pracufo" to seller.taxOfficeBranch, "c_ufo" to seller.taxOfficeCode, "c_telef" to "",
        "email" to seller.email, "jmeno" to seller.firstName, "prijmeni" to seller.lastName,
    ).filterNot { (name, value) -> name == "c_pracufo" && value.isBlank() }.toTypedArray()) // optional, numeric when present

    private fun vetaA4(inv: IssuedInvoice, s: Sums) = veta("VetaA4",
        "zdph_44" to "N", "dic_odb" to withoutPrefix(inv.customer.dic.orEmpty()), "kod_rezim_pl" to "0",
        "c_evid_dd" to inv.number, "dppd" to EpoFormat.khDate(inv.taxDate), *rates(s), "c_radku" to "1")

    private fun rates(s: Sums): Array<Pair<String, String>> = arrayOf(
        "zakl_dane1" to EpoFormat.amount(s.base1), "dan1" to EpoFormat.amount(s.vat1),
        "zakl_dane2" to EpoFormat.amount(s.base2), "dan2" to EpoFormat.amount(s.vat2),
        "zakl_dane3" to "0", "dan3" to "0",
    )
}
