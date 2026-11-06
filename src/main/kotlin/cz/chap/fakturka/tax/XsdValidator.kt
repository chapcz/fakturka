package cz.chap.fakturka.tax

import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

/** The official EPO schemas, see `src/main/resources/epo/README.md`. */
enum class EpoForm(val xsdResource: String) {
    KH("/epo/dphkh1_epo2.xsd"),
    DP("/epo/dphdp3_epo2.xsd"),
    SH("/epo/dphshv_epo2.xsd"),
}

/** Validates a complete `Pisemnost` document against the official EPO schema of [form]. */
class XsdValidator(form: EpoForm) {
    private val schema: Schema = schemas.computeIfAbsent(form, ::load)

    /** Returns the validation errors; an empty list means the document is valid. */
    fun validate(xml: ByteArray): List<String> {
        val errors = mutableListOf<String>()
        val validator = schema.newValidator()
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        validator.errorHandler = object : ErrorHandler {
            override fun warning(e: SAXParseException) = Unit
            override fun error(e: SAXParseException) { errors += describe(e) }
            override fun fatalError(e: SAXParseException) { errors += describe(e) }
        }
        try {
            validator.validate(StreamSource(ByteArrayInputStream(xml)))
        } catch (e: SAXException) {
            // A fatal error is reported to the handler first and then thrown; record it only once.
            if (errors.isEmpty()) errors += e.message.orEmpty()
        }
        return errors
    }

    private fun describe(e: SAXParseException) = "line ${e.lineNumber}, column ${e.columnNumber}: ${e.message}"

    private companion object {
        val schemas = ConcurrentHashMap<EpoForm, Schema>()

        fun load(form: EpoForm): Schema {
            val url = XsdValidator::class.java.getResource(form.xsdResource)
                ?: error("Missing schema resource ${form.xsdResource}")
            val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            // The system id lets relative xs:include/xs:import resolve next to the schema, also inside the jar.
            return url.openStream().use { factory.newSchema(StreamSource(it, url.toExternalForm())) }
        }
    }
}
