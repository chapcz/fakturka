package cz.chap.fakturka.storage

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.ReceivedInvoice
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.domain.Template

sealed interface VersionedFile {
    val version: Int
}

@JacksonXmlRootElement(localName = "config")
data class ConfigFile(
    val seller: SellerConfig,
    @param:JacksonXmlProperty(isAttribute = true) override val version: Int = 1,
) : VersionedFile

@JacksonXmlRootElement(localName = "contacts")
data class ContactsFile(
    @param:JacksonXmlElementWrapper(useWrapping = false)
    @param:JacksonXmlProperty(localName = "contact")
    val contacts: List<Contact> = emptyList(),
    @param:JacksonXmlProperty(isAttribute = true) override val version: Int = 1,
) : VersionedFile

@JacksonXmlRootElement(localName = "templates")
data class TemplatesFile(
    @param:JacksonXmlElementWrapper(useWrapping = false)
    @param:JacksonXmlProperty(localName = "template")
    val templates: List<Template> = emptyList(),
    @param:JacksonXmlProperty(isAttribute = true) override val version: Int = 1,
) : VersionedFile

@JacksonXmlRootElement(localName = "issuedInvoices")
data class IssuedFile(
    @param:JacksonXmlProperty(isAttribute = true) val year: Int,
    val invoices: List<IssuedInvoice>,
    @param:JacksonXmlProperty(isAttribute = true) override val version: Int = 1,
) : VersionedFile

@JacksonXmlRootElement(localName = "receivedInvoices")
data class ReceivedFile(
    @param:JacksonXmlProperty(isAttribute = true) val year: Int,
    val invoices: List<ReceivedInvoice>,
    @param:JacksonXmlProperty(isAttribute = true) override val version: Int = 1,
) : VersionedFile
