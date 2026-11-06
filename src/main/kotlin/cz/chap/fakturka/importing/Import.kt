package cz.chap.fakturka.importing

import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.io.path.inputStream

/** One document of an import file: either ready to import or skipped with a reason. */
sealed interface ImportDocument {
    val number: String

    data class Ready(val invoice: IssuedInvoice) : ImportDocument {
        override val number get() = invoice.number
    }

    data class Skipped(override val number: String, val reason: String) : ImportDocument
}

/** What an import would do; built by [InvoiceImporter.plan], executed by [InvoiceImporter.apply]. */
data class ImportPlan(val toImport: List<IssuedInvoice>, val skipped: List<ImportDocument.Skipped>, val newContacts: List<Contact>)

/** Detects the format of an import file and parses it; only the ProFact export of issued documents is known so far. */
object ImportFormats {
    fun parse(path: Path): List<ImportDocument> = when (rootElement(path)) {
        ProfactParser.ROOT -> ProfactParser.parse(path)
        else -> throw IllegalArgumentException(Messages.get("ui.import.error.unsupported"))
    }

    /** Name of the root XML element, or null when the file is not XML. */
    private fun rootElement(path: Path): String? = runCatching {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        path.inputStream().use { input ->
            val reader = factory.createXMLStreamReader(input)
            try {
                while (reader.hasNext()) if (reader.next() == XMLStreamConstants.START_ELEMENT) return@use reader.localName
                null
            } finally {
                reader.close()
            }
        }
    }.getOrNull()
}

class InvoiceImporter(private val repo: DataRepository) {
    /** Sorts the parsed documents into what will be imported and what will be skipped; changes nothing. */
    fun plan(documents: List<ImportDocument>): ImportPlan {
        val ready = documents.filterIsInstance<ImportDocument.Ready>().map { it.invoice }
        val skipped = documents.filterIsInstance<ImportDocument.Skipped>().toMutableList()
        val existing = ready.map { it.issueDate.year }.distinct().flatMap { y -> repo.issued(y).map { it.number } }.toSet()
        val seen = mutableSetOf<String>()
        val toImport = ready.filter { inv ->
            when {
                inv.number in existing -> { skipped += ImportDocument.Skipped(inv.number, Messages.get("ui.import.skip.exists")); false }
                !seen.add(inv.number) -> { skipped += ImportDocument.Skipped(inv.number, Messages.get("ui.import.skip.duplicate")); false }
                else -> true
            }
        }
        val knownIcos = repo.contacts().mapNotNull { it.ico }.toMutableSet()
        val newContacts = toImport.map { it.customer }.filter { c -> c.ico != null && knownIcos.add(c.ico) }
        return ImportPlan(toImport, skipped, newContacts)
    }

    /** Saves the planned invoices (as taken-over, without PDF) and the missing contacts. */
    fun apply(plan: ImportPlan) {
        plan.newContacts.forEach(repo::saveContact)
        plan.toImport.forEach { repo.saveIssued(it) }
    }
}
