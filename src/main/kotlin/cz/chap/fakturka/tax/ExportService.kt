package cz.chap.fakturka.tax

import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.Quarter
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.storage.AtomicFileWriter
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.RepositoryException
import java.math.BigDecimal
import java.math.BigDecimal.ZERO
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate

data class ExportResult(val files: List<Path>, val errors: List<String>)

/** Figures of one quarter for the UI; KH bases at scale 2, DP amounts in whole crowns. */
data class QuarterSummary(
    val a4Count: Int,
    val a5Base: BigDecimal,
    val b2Count: Int,
    val b3Base: BigDecimal,
    val outputVat: BigDecimal,
    val inputVat: BigDecimal,
    val toPay: BigDecimal, // negative = excess deduction
    val euCount: Int,
)

/** Builds one form; null when the form is not filed for the quarter. */
internal fun interface FormBuilder {
    fun build(form: EpoForm, seller: SellerConfig, data: QuarterData, filingDate: LocalDate): EpoElement?
}

internal val defaultFormBuilder = FormBuilder { form, seller, data, filingDate ->
    when (form) {
        EpoForm.KH -> KhBuilder.build(seller, data, filingDate)
        EpoForm.DP -> DpBuilder.build(seller, data, filingDate)
        EpoForm.SH -> ShBuilder.build(seller, data, filingDate)
    }
}

class ExportService internal constructor(
    private val repo: DataRepository,
    private val clock: Clock,
    private val builder: FormBuilder,
) {
    constructor(repo: DataRepository, clock: Clock = Clock.systemDefaultZone()) : this(repo, clock, defaultFormBuilder)

    /** Builds and validates all forms; writes them only when every form is valid. */
    fun export(q: Quarter): ExportResult {
        val seller = seller()
        // c_ufo is required by every EPO form, but optional in the settings: report it here in plain words.
        if (seller.taxOfficeCode.isBlank()) return ExportResult(emptyList(), listOf(Messages.get("ui.vat.error.taxOfficeCode")))
        val data = QuarterCollector(repo).collect(q)
        val filingDate = LocalDate.now(clock)
        val documents = mutableListOf<Pair<Path, ByteArray>>()
        val errors = mutableListOf<String>()
        for (form in EpoForm.entries) {
            try {
                val root = builder.build(form, seller, data, filingDate) ?: continue
                val bytes = EpoXmlWriter.write(root)
                val formErrors = XsdValidator(form).validate(bytes)
                if (formErrors.isEmpty()) documents += fileOf(form, q) to bytes
                else errors += formErrors.map { "${form.name}: $it" }
            } catch (e: IllegalArgumentException) {
                errors += "${form.name}: ${e.message}"
            }
        }
        if (errors.isNotEmpty()) return ExportResult(emptyList(), errors)
        documents.forEach { (path, bytes) -> AtomicFileWriter.write(path, bytes) }
        // An SH left over from an earlier run is stale once the quarter has no EU invoice: keep it as .bak.
        val sh = fileOf(EpoForm.SH, q)
        val staleErrors = if (documents.none { it.first == sh } && Files.exists(sh)) {
            try {
                Files.move(sh, sh.resolveSibling(sh.fileName.toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING)
                emptyList()
            } catch (e: IOException) {
                listOf("SH: ${e.message ?: e.javaClass.simpleName}")
            }
        } else emptyList()
        return ExportResult(documents.map { it.first }, staleErrors)
    }

    fun isExported(q: Quarter): Boolean =
        Files.exists(fileOf(EpoForm.KH, q)) && Files.exists(fileOf(EpoForm.DP, q))

    /** The figures as they appear in the generated KH and DP. */
    fun summary(q: Quarter): QuarterSummary {
        val seller = seller()
        val data = QuarterCollector(repo).collect(q)
        val date = LocalDate.now(clock)
        val kh = KhBuilder.build(seller, data, date)
        val dp = DpBuilder.build(seller, data, date)
        fun base(e: EpoElement) = listOf("zakl_dane1", "zakl_dane2", "zakl_dane3")
            .fold(ZERO) { acc, k -> acc + e.number(k) }.setScale(2)
        val v6 = dp.children.single { it.name == "Veta6" }
        return QuarterSummary(
            a4Count = kh.children.count { it.name == "VetaA4" },
            a5Base = base(kh.children.single { it.name == "VetaA5" }),
            b2Count = kh.children.count { it.name == "VetaB2" },
            b3Base = base(kh.children.single { it.name == "VetaB3" }),
            outputVat = v6.number("dan_zocelk"),
            inputVat = v6.number("odp_zocelk"),
            toPay = v6.number("dano_da") - v6.number("dano_no"),
            euCount = data.issued.count { it.customer.type == ContactType.EU },
        )
    }

    private fun seller(): SellerConfig = repo.loadConfig() ?: throw RepositoryException("Seller configuration is missing")

    private fun fileOf(form: EpoForm, q: Quarter): Path =
        repo.exportDir(q).resolve("${form.name}-${q.year}-Q${q.quarter}.xml")

    private fun EpoElement.number(name: String): BigDecimal =
        attributes.firstOrNull { it.first == name }?.second?.toBigDecimal() ?: ZERO
}
