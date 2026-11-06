package cz.chap.fakturka.pdf

import cz.chap.fakturka.domain.Address
import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.InvoiceItem
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.domain.VatRate
import java.time.LocalDate

object PdfFixtures {
    val seller = SellerConfig(
        name = "Test Dodavatel",
        firstName = "Test",
        lastName = "Dodavatel",
        title = "",
        address = Address("Testovací", "1", "Praha", "11000", "Česká republika"),
        ico = "12345678",
        dic = "CZ12345678",
        email = "test@example.com",
        phone = "123456789",
        web = "www.example.com",
        bankName = "Testovací banka, a.s.",
        accountNumber = "19-2000145399/0800",
        iban = "CZ6508000000192000145399",
        swift = "TESTCZPPXXX",
        taxOfficeCode = "461",
        taxOfficeBranch = "2509",
        czNace = "620200",
    )

    val domesticCustomer = Contact(
        id = "c1",
        name = "Odběratel Test s.r.o.",
        address = Address("Smyšlená", "123/4A", "Testov", "12345", "Česká republika"),
        ico = "87654321",
        dic = "CZ87654321",
        email = null,
        deliveryAddress = null,
        type = ContactType.DOMESTIC,
    )

    val euCustomer = Contact(
        id = "c2",
        name = "Test Kunde GmbH",
        address = Address("Teststraße", "5", "Berlin", "10115", "Deutschland"),
        ico = null,
        dic = "DE123456789",
        email = null,
        deliveryAddress = null,
        type = ContactType.EU,
    )

    fun invoice2600022(customer: Contact = domesticCustomer) = IssuedInvoice(
        number = "2600022",
        issueDate = LocalDate.of(2026, 11, 4),
        taxDate = LocalDate.of(2026, 11, 4),
        dueDate = LocalDate.of(2026, 11, 18),
        customer = customer,
        orderNumber = null,
        constantSymbol = "0308",
        variableSymbol = "2600022",
        paymentMethod = "převodním příkazem",
        items = listOf(
            InvoiceItem(
                description = "Programátorské práce",
                quantity = "18.5".toBigDecimal(),
                unit = "h",
                unitPrice = "1000.00".toBigDecimal(),
                vatRate = if (customer.type == ContactType.EU) VatRate.ZERO else VatRate.STANDARD,
            ),
        ),
    )
}
