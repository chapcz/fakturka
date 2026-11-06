package cz.chap.fakturka.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesTest {
    @Test fun `czech app title`() = assertEquals("Fakturka", Messages.get("app.title"))
    @Test fun `czech label with diacritics`() = assertEquals("Odběratel", Messages.get("pdf.customer"))
    @Test fun `english label`() = assertEquals("Customer", Messages.get("pdf.customer", Messages.EN))
}
