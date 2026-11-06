package cz.chap.fakturka.tax

import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.domain.normalizeVatId
import java.time.LocalDate

/** Builds the Souhrnné hlášení (DPHSHV) element tree; null when the quarter has no EU invoice. */
object ShBuilder {
    fun build(seller: SellerConfig, data: QuarterData, filingDate: LocalDate): EpoElement? {
        val eu = data.issued.filter { it.customer.type == ContactType.EU }
        if (eu.isEmpty()) return null

        val children = mutableListOf<EpoElement>()
        children += veta("VetaD", "dokument" to "SHV", "k_uladis" to "DPH", "rok" to data.quarter.year.toString(),
            "ctvrt" to data.quarter.quarter.toString(), "shvies_forma" to "R", "d_poddp" to EpoFormat.dpDate(filingDate))
        children += vetaPDp(seller, withContact = false)

        eu.forEach {
            val dic = normalizeVatId(it.customer.dic.orEmpty())
            require(dic.length > 2 && dic.take(2).all(Char::isLetter)) {
                "EU customer lacks a VAT ID (invoice ${it.number})"
            }
        }
        euSupplies(eu).forEachIndexed { i, supply ->
            val dic = supply.vatId
            children += veta("VetaR", "c_rad" to (i + 1).toString(), "k_stat" to dic.take(2), "c_vat" to dic.drop(2),
                "k_pln_eu" to "3", "pln_pocet" to supply.invoices.size.toString(), "pln_hodnota" to EpoFormat.whole(supply.wholeBase))
        }
        return EpoElement("DPHSHV", listOf("verzePis" to "02.01"), children)
    }
}
