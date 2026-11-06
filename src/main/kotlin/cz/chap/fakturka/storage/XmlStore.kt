package cz.chap.fakturka.storage

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamWriteFeature
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.util.StdConverter
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import cz.chap.fakturka.domain.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass

class MoneySerializer : StdSerializer<BigDecimal>(BigDecimal::class.java) {
    override fun serialize(value: BigDecimal, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeString(value.money().toPlainString())
    }
}

class MoneyDeserializer : StdDeserializer<BigDecimal>(BigDecimal::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): BigDecimal =
        BigDecimal(p.valueAsString.trim()).money()
}

/** Readable XML shape for one entry of `ReceivedInvoice.amounts`. */
data class AmountEntry(
    @field:JacksonXmlProperty(isAttribute = true) val rate: VatRate,
    @field:JsonSerialize(using = MoneySerializer::class) val base: BigDecimal,
    @field:JsonSerialize(using = MoneySerializer::class) val vat: BigDecimal,
)

/** `<amounts><amount rate="STANDARD">...</amount></amounts>` */
data class AmountsXml(
    @param:JacksonXmlElementWrapper(useWrapping = false)
    @param:JacksonXmlProperty(localName = "amount")
    val amount: List<AmountEntry> = emptyList(),
)

class AmountsToXml : StdConverter<Map<VatRate, VatAmount>, AmountsXml>() {
    override fun convert(value: Map<VatRate, VatAmount>): AmountsXml =
        AmountsXml(value.map { (rate, a) -> AmountEntry(rate, a.base, a.vat) })
}

class XmlToAmounts : StdConverter<AmountsXml, Map<VatRate, VatAmount>>() {
    override fun convert(value: AmountsXml): Map<VatRate, VatAmount> =
        value.amount.associate { it.rate to VatAmount(it.base.money(), it.vat.money()) }
}

private abstract class ContactMixin {
    @get:JsonIgnore
    abstract val hasCzVatId: Boolean
}

private abstract class InvoiceItemMixin {
    @get:JsonSerialize(using = MoneySerializer::class)
    @get:JsonDeserialize(using = MoneyDeserializer::class)
    abstract val unitPrice: BigDecimal

    @get:JsonSerialize(using = MoneySerializer::class)
    @get:JsonDeserialize(using = MoneyDeserializer::class)
    abstract val discount: BigDecimal
}

private abstract class TemplateItemMixin {
    @get:JsonSerialize(using = MoneySerializer::class)
    @get:JsonDeserialize(using = MoneyDeserializer::class)
    abstract val unitPrice: BigDecimal
}

private abstract class QuarterMixin {
    @get:JsonIgnore
    abstract val months: IntRange
}

private abstract class SellerConfigMixin {
    @get:JsonIgnore
    abstract val titles: String
}

private abstract class ReceivedInvoiceMixin {
    @get:JsonIgnore
    abstract val total: BigDecimal
}

// Reading ignores the names of elements inside a wrapper, so only the writer needs the singular child names.
// (Jackson cannot rename wrapper children of Kotlin creator properties when reading.)
private abstract class IssuedFileWriteMixin {
    @get:JacksonXmlElementWrapper(localName = "invoices")
    @get:JacksonXmlProperty(localName = "invoice")
    abstract val invoices: List<IssuedInvoice>
}

private abstract class ReceivedFileWriteMixin {
    @get:JacksonXmlElementWrapper(localName = "invoices")
    @get:JacksonXmlProperty(localName = "invoice")
    abstract val invoices: List<ReceivedInvoice>
}

private abstract class IssuedInvoiceWriteMixin {
    @get:JacksonXmlElementWrapper(localName = "items")
    @get:JacksonXmlProperty(localName = "item")
    abstract val items: List<InvoiceItem>
}

private abstract class TemplateWriteMixin {
    @get:JacksonXmlElementWrapper(localName = "items")
    @get:JacksonXmlProperty(localName = "item")
    abstract val items: List<TemplateItem>
}

private abstract class ReceivedInvoiceWriteMixin : ReceivedInvoiceMixin() {
    @get:JsonSerialize(converter = AmountsToXml::class)
    abstract val amounts: Map<VatRate, VatAmount>
}

private abstract class ReceivedInvoiceReadMixin : ReceivedInvoiceMixin() {
    @get:JsonDeserialize(converter = XmlToAmounts::class)
    abstract val amounts: Map<VatRate, VatAmount>
}

class XmlStore {
    private fun builder() = XmlMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .addModule(JavaTimeModule())
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(SerializationFeature.INDENT_OUTPUT, true)
        .enable(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
        .addMixIn(Contact::class.java, ContactMixin::class.java)
        .addMixIn(InvoiceItem::class.java, InvoiceItemMixin::class.java)
        .addMixIn(TemplateItem::class.java, TemplateItemMixin::class.java)
        .addMixIn(Quarter::class.java, QuarterMixin::class.java)
        .addMixIn(SellerConfig::class.java, SellerConfigMixin::class.java)

    private val reader: XmlMapper = builder()
        .addMixIn(ReceivedInvoice::class.java, ReceivedInvoiceReadMixin::class.java)
        .build()

    private val writer: XmlMapper = builder()
        .addMixIn(IssuedFile::class.java, IssuedFileWriteMixin::class.java)
        .addMixIn(ReceivedFile::class.java, ReceivedFileWriteMixin::class.java)
        .addMixIn(IssuedInvoice::class.java, IssuedInvoiceWriteMixin::class.java)
        .addMixIn(Template::class.java, TemplateWriteMixin::class.java)
        .addMixIn(ReceivedInvoice::class.java, ReceivedInvoiceWriteMixin::class.java)
        .build()
        .also { it.factory.enable(ToXmlGenerator.Feature.WRITE_XML_DECLARATION) }

    fun <T : Any> read(path: Path, type: KClass<T>): T? {
        if (!Files.exists(path)) return null
        try {
            val value = reader.readValue(Files.readAllBytes(path), type.java)
            if (value is VersionedFile && value.version != 1) {
                throw StorageException("Unsupported data file version ${value.version} in $path", null)
            }
            return value
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException("Cannot read data file $path: ${e.message}", e)
        }
    }

    fun write(path: Path, value: Any) {
        val bytes = try {
            writer.writeValueAsBytes(value)
        } catch (e: Exception) {
            throw StorageException("Cannot serialize data for $path: ${e.message}", e)
        }
        AtomicFileWriter.write(path, bytes)
    }
}
