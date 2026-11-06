package cz.chap.fakturka.storage

import cz.chap.fakturka.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.div

class RepositoryException(message: String) : RuntimeException(message)

class DataRepository(val dataDir: Path) {
    private companion object {
        const val SIGNATURE_NAME = "signature"
        val SIGNATURE_EXTENSIONS = listOf("png", "jpg", "jpeg")
    }

    private val store = XmlStore()

    private val configPath get() = dataDir / "config.xml"
    private val contactsPath get() = dataDir / "contacts.xml"
    private val templatesPath get() = dataDir / "templates.xml"
    private fun yearDir(year: Int) = dataDir / year.toString()
    private fun issuedPath(year: Int) = yearDir(year) / "issued.xml"
    private fun receivedPath(year: Int) = yearDir(year) / "received.xml"

    fun loadConfig(): SellerConfig? = store.read(configPath, ConfigFile::class)?.seller

    fun saveConfig(config: SellerConfig) = store.write(configPath, ConfigFile(config))

    fun contacts(): List<Contact> = store.read(contactsPath, ContactsFile::class)?.contacts ?: emptyList()

    fun saveContact(contact: Contact) =
        store.write(contactsPath, ContactsFile(upsert(contacts(), contact) { it.id }))

    fun deleteContact(id: String) =
        store.write(contactsPath, ContactsFile(contacts().filter { it.id != id }))

    fun templates(): List<Template> = store.read(templatesPath, TemplatesFile::class)?.templates ?: emptyList()

    fun saveTemplate(template: Template) =
        store.write(templatesPath, TemplatesFile(upsert(templates(), template) { it.id }))

    fun deleteTemplate(id: String) =
        store.write(templatesPath, TemplatesFile(templates().filter { it.id != id }))

    fun issued(year: Int): List<IssuedInvoice> =
        store.read(issuedPath(year), IssuedFile::class)?.invoices ?: emptyList()

    fun saveIssued(invoice: IssuedInvoice, replacing: String? = null) {
        val year = invoice.issueDate.year
        // An in-place edit: the one stored copy of `replacing` lives in this year.
        val originalYears = if (replacing == null) emptyList() else yearsOnDisk().filter { y -> issued(y).any { it.number == replacing } }
        val inPlace = originalYears == listOf(year)
        if (invoice.external) {
            if (invoice.number.isBlank()) throw RepositoryException("Invoice number must not be blank")
        } else if (!(inPlace && replacing == invoice.number)) {
            // A new own number must fit the configured pattern; an unchanged number may predate a pattern change.
            val format = NumberFormat.of(loadConfig()?.numberFormat ?: NumberFormat.DEFAULT)
            if (format.sequenceFor(invoice.number, invoice.issueDate) == null) {
                throw RepositoryException("Invalid invoice number ${invoice.number} for ${invoice.issueDate}")
            }
        }
        val inYear = issued(year)
        val duplicate = inYear.any { it.number == invoice.number } && !(inPlace && replacing == invoice.number)
        if (duplicate) throw RepositoryException("Invoice number ${invoice.number} already exists in $year")
        val key = replacing ?: invoice.number
        val index = inYear.indexOfFirst { it.number == key }
        val result = if (index >= 0) inYear.toMutableList().also { it[index] = invoice } else inYear + invoice
        writeIssued(year, result)
        if (replacing != null) {
            // The issue date may have moved to another year: drop the old record only after the new one is safe.
            for (other in yearsOnDisk().filter { it != year }) {
                val old = issued(other)
                if (old.any { it.number == replacing }) writeIssued(other, old.filter { it.number != replacing })
            }
        }
    }

    fun deleteIssued(year: Int, number: String) {
        val all = issued(year)
        val target = all.firstOrNull { it.number == number } ?: return
        if (!target.external) {
            val format = NumberFormat.of(loadConfig()?.numberFormat ?: NumberFormat.DEFAULT)
            val highest = all.filter { !it.external }.mapNotNull { InvoiceNumbering.sequenceOf(it.number, year, format) }.maxOrNull()
            if (InvoiceNumbering.sequenceOf(number, year, format) != highest) {
                throw RepositoryException("Only the last issued invoice can be deleted (not $number)")
            }
        }
        writeIssued(year, all.filter { it.number != number })
    }

    /** Next own invoice number for an invoice issued on [date]. */
    fun nextNumber(date: LocalDate): String {
        val seller = loadConfig() ?: throw RepositoryException("Seller configuration is missing")
        return InvoiceNumbering.next(date, issued(date.year), seller)
    }

    fun received(year: Int): List<ReceivedInvoice> =
        store.read(receivedPath(year), ReceivedFile::class)?.invoices ?: emptyList()

    fun saveReceived(invoice: ReceivedInvoice) {
        val year = invoice.claimPeriod.year
        store.write(receivedPath(year), ReceivedFile(year, upsert(received(year), invoice) { it.id }))
        // An edit may move the claim period to another year; prune stale copies only after the new one is saved.
        for (other in yearsOnDisk().filter { it != year }) {
            val old = received(other)
            if (old.any { it.id == invoice.id }) {
                store.write(receivedPath(other), ReceivedFile(other, old.filter { it.id != invoice.id }))
            }
        }
    }

    fun deleteReceived(year: Int, id: String) =
        store.write(receivedPath(year), ReceivedFile(year, received(year).filter { it.id != id }))

    fun pdfPath(invoice: IssuedInvoice): Path =
        yearDir(invoice.issueDate.year) / "pdf" / "${safeFileName(invoice.number)}.pdf"

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").replace("..", "_").ifEmpty { "_" }

    /** The stamp and signature image placed above the signature line of new PDFs, or null when none is set. */
    fun signatureImage(): Path? = SIGNATURE_EXTENSIONS.map { dataDir / "$SIGNATURE_NAME.$it" }.firstOrNull { Files.isRegularFile(it) }

    /** Copies [source] (PNG or JPEG) into the data folder as the stamp and signature image, replacing the old one. */
    fun setSignatureImage(source: Path) {
        val extension = source.fileName.toString().substringAfterLast('.', "").lowercase()
        if (extension !in SIGNATURE_EXTENSIONS || javax.imageio.ImageIO.read(source.toFile()) == null) {
            throw RepositoryException(cz.chap.fakturka.i18n.Messages.get("ui.settings.signature.invalid"))
        }
        Files.createDirectories(dataDir)
        val target = dataDir / "$SIGNATURE_NAME.$extension"
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        SIGNATURE_EXTENSIONS.map { dataDir / "$SIGNATURE_NAME.$it" }.filter { it != target }.forEach { Files.deleteIfExists(it) }
    }

    fun removeSignatureImage() = SIGNATURE_EXTENSIONS.forEach { Files.deleteIfExists(dataDir / "$SIGNATURE_NAME.$it") }

    fun receivedDir(year: Int): Path = (yearDir(year) / "received").also { Files.createDirectories(it) }

    fun exportDir(q: Quarter): Path = yearDir(q.year) / "export" / "Q${q.quarter}"

    /** Years that have a folder in the data directory, ascending. */
    fun issuedYears(): List<Int> = yearsOnDisk().sorted()

    private fun yearsOnDisk(): List<Int> =
        if (!Files.isDirectory(dataDir)) emptyList()
        else Files.list(dataDir).use { s -> s.map { it.fileName.toString() }.toList() }.mapNotNull { it.toIntOrNull() }

    private fun writeIssued(year: Int, invoices: List<IssuedInvoice>) =
        store.write(issuedPath(year), IssuedFile(year, invoices))

    private fun <T> upsert(list: List<T>, item: T, id: (T) -> String): List<T> {
        val index = list.indexOfFirst { id(it) == id(item) }
        return if (index >= 0) list.toMutableList().also { it[index] = item } else list + item
    }
}
