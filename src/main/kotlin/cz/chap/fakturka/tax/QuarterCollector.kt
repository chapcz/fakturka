package cz.chap.fakturka.tax

import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.Quarter
import cz.chap.fakturka.domain.ReceivedInvoice
import cz.chap.fakturka.storage.DataRepository

data class QuarterData(val quarter: Quarter, val issued: List<IssuedInvoice>, val received: List<ReceivedInvoice>)

class QuarterCollector(private val repo: DataRepository) {
    fun collect(q: Quarter): QuarterData {
        // The tax date may fall in another year than the issue date (December issue with January DUZP and vice versa).
        val issued = (repo.issued(q.year - 1) + repo.issued(q.year) + repo.issued(q.year + 1))
            .filter { it.taxDate in q }
            .sortedWith(compareBy({ it.taxDate }, { it.number }))
        val received = repo.received(q.year)
            .filter { it.claimPeriod == q }
            .sortedWith(compareBy({ it.taxDate }, { it.documentNumber }))
        return QuarterData(q, issued, received)
    }
}
