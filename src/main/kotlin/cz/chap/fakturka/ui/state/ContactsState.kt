package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.Address
import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.normalizeVatId
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository

private fun Address.trimmed() = Address(street.trim(), houseNumber.trim(), city.trim(), postcode.trim(), country.trim())

private fun String?.trimmedOrNull(): String? = this?.trim()?.ifBlank { null }

private val DOMESTIC_DIC = Regex("""CZ\d{8,10}""")

private fun Contact.trimmed() = copy(
    name = name.trim(), address = address.trimmed(), ico = ico.trimmedOrNull(), dic = dic.trimmedOrNull()?.let(::normalizeVatId),
    email = email.trimmedOrNull(), deliveryAddress = deliveryAddress?.trimmed(),
)

class ContactsState(private val repo: DataRepository) {
    var contacts: List<Contact> = repo.contacts()
        private set

    /** Localized validation messages; empty when [c] may be saved. */
    fun validate(c: Contact): List<String> = buildList {
        if (c.name.isBlank()) add(Messages.get("ui.contacts.error.name"))
        val dic = normalizeVatId(c.dic.orEmpty())
        if (c.type == ContactType.EU) {
            val prefix = dic.take(2)
            val valid = dic.length > 2 && prefix.all { it in 'A'..'Z' } && prefix != "CZ"
            if (!valid) add(Messages.get("ui.contacts.error.euDic"))
        } else if (dic.isNotEmpty() && !DOMESTIC_DIC.matches(dic)) {
            add(Messages.get("ui.contacts.error.domesticDic"))
        }
    }

    /** Writes [c]; call only after [validate] is empty. */
    fun save(c: Contact) {
        repo.saveContact(c.trimmed())
        contacts = repo.contacts()
    }

    /** Returns a localized refusal when a template still uses the contact, otherwise deletes and returns null. */
    fun delete(id: String): String? {
        if (repo.templates().any { it.customerId == id }) return Messages.get("ui.contacts.error.usedByTemplate")
        repo.deleteContact(id)
        contacts = repo.contacts()
        return null
    }
}
