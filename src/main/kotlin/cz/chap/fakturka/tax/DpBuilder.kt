package cz.chap.fakturka.tax

import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.domain.VatRate
import cz.chap.fakturka.domain.totals
import cz.chap.fakturka.domain.wholeCrowns
import java.math.BigDecimal
import java.math.BigDecimal.ZERO
import java.time.LocalDate

/** Builds the Přiznání k DPH (DPHDP3) element tree for one quarter. */
object DpBuilder {
    fun build(seller: SellerConfig, data: QuarterData, filingDate: LocalDate): EpoElement {
        var base23 = ZERO; var vat23 = ZERO; var base5 = ZERO; var vat5 = ZERO
        // Row 21 must equal the SH total, which rounds per customer.
        val euBase = euSupplies(data.issued).fold(ZERO) { acc, s -> acc + s.wholeBase }
        for (inv in data.issued) {
            if (inv.customer.type == ContactType.EU) continue
            for (l in inv.totals.vatLines) when (l.rate) {
                VatRate.STANDARD -> { base23 += l.base; vat23 += l.vat }
                VatRate.REDUCED -> { base5 += l.base; vat5 += l.vat }
                VatRate.ZERO -> Unit
            }
        }
        var in23 = ZERO; var ded23 = ZERO; var in5 = ZERO; var ded5 = ZERO
        for (r in data.received) for ((rate, a) in r.amounts) when (rate) {
            VatRate.STANDARD -> { in23 += a.base; ded23 += a.vat }
            VatRate.REDUCED -> { in5 += a.base; ded5 += a.vat }
            VatRate.ZERO -> Unit
        }

        val children = mutableListOf<EpoElement>()
        children += veta("VetaD", "c_okec" to seller.czNace, "ctvrt" to data.quarter.quarter.toString(),
            "d_poddp" to EpoFormat.dpDate(filingDate), "dapdph_forma" to "B", "dokument" to "DP3", "k_uladis" to "DPH",
            "kod_zo" to "Q", "rok" to data.quarter.year.toString(), "trans" to "A", "typ_platce" to "P")
        children += vetaPDp(seller)

        val dan23 = vat23.wholeCrowns()
        val dan5 = vat5.wholeCrowns()
        children += veta("Veta1", *buildList {
            add("dan23" to EpoFormat.amount(dan23)); add("obrat23" to EpoFormat.whole(base23))
            if (base5.signum() != 0 || vat5.signum() != 0) {
                add("dan5" to EpoFormat.amount(dan5)); add("obrat5" to EpoFormat.whole(base5))
            }
        }.toTypedArray())
        if (euBase.signum() != 0) children += veta("Veta2", "pln_sluzby" to EpoFormat.whole(euBase))

        val odp = (ded23.wholeCrowns() + ded5.wholeCrowns())
        children += veta("Veta4", *buildList {
            add("odp_sum_kr" to "0"); add("odp_sum_nar" to EpoFormat.whole(odp))
            add("odp_tuz23_nar" to EpoFormat.whole(ded23))
            if (in5.signum() != 0 || ded5.signum() != 0) add("odp_tuz5_nar" to EpoFormat.whole(ded5))
            add("pln23" to EpoFormat.whole(in23))
            if (in5.signum() != 0 || ded5.signum() != 0) add("pln5" to EpoFormat.whole(in5))
        }.toTypedArray())
        children += veta("Veta5", "odp_uprav_kf" to "0")

        val tax = dan23 + dan5
        children += veta("Veta6", "dan_zocelk" to EpoFormat.whole(tax), "dano" to "0",
            "dano_da" to EpoFormat.whole((tax - odp).max(BigDecimal.ZERO)),
            "dano_no" to EpoFormat.whole((odp - tax).max(BigDecimal.ZERO)),
            "odp_zocelk" to EpoFormat.whole(odp))
        return EpoElement("DPHDP3", listOf("verzePis" to "03.01"), children)
    }
}
