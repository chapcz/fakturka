package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.Address
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SettingsStateTest {
    @TempDir
    lateinit var dir: Path

    private val full = SellerConfig(
        "Test Dodavatel", "Test", "Dodavatel", "", Address("Ulice", "1", "Praha", "11000", "CZ"),
        "12345678", "CZ12345678", "a@b.cz", null, null,
        "Banka", "1/0100", "CZ6501000000000000000001", "KOMBCZPP", "451", "2001", "62020",
        numberingStartYear = 2026, numberingStartSequence = 23,
    )

    @Test fun `validate requires seller identity, address, ico, dic with CZ prefix, iban — tax office is optional`() {
        val state = SettingsState(DataRepository(dir))
        val expected = listOf(
            "name", "firstName", "lastName", "street", "city", "postcode", "ico", "dic", "iban",
        )
            .map { Messages.get("ui.settings.error.$it") }
        assertEquals(expected, state.validate())
        state.draft = full
        assertTrue(state.validate().isEmpty())
        state.draft = full.copy(taxOfficeCode = "", taxOfficeBranch = "")
        assertTrue(state.validate().isEmpty())
        state.draft = full.copy(dic = "12345678")
        assertEquals(listOf(Messages.get("ui.settings.error.dic")), state.validate())
    }

    @Test fun `due days and numbering are validated as text`() {
        val state = SettingsState(DataRepository(dir))
        state.draft = full
        for (bad in listOf("", "0", "-3", "x")) {
            state.dueDaysText = bad
            assertEquals(listOf(Messages.get("ui.settings.error.dueDays")), state.validate(), bad)
        }
        state.dueDaysText = "30"
        state.numberingStartYearText = "abc"
        state.numberingStartSequenceText = "1.5"
        assertEquals(
            listOf(Messages.get("ui.settings.error.numberingStartYear"), Messages.get("ui.settings.error.numberingStartSequence")),
            state.validate(),
        )
        state.numberingStartYearText = "2027"
        state.numberingStartSequenceText = "7"
        assertTrue(state.validate().isEmpty())
        state.save()
        val saved = DataRepository(dir).loadConfig()!!
        assertEquals(30, saved.dueDays)
        assertEquals(2027, saved.numberingStartYear)
        assertEquals(7, saved.numberingStartSequence)
    }

    @Test fun `number pattern is validated and previewed before saving`() {
        val repo = DataRepository(dir)
        val state = SettingsState(repo, Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC))
        state.draft = full.copy(numberFormat = "FV-{NNN}")
        assertTrue(state.validate().contains(Messages.get("ui.settings.error.numberFormat.year")))
        assertNull(state.previewNumber())
        state.draft = full.copy(numberFormat = "FV-{RRRR}-{NNN}", numberingStartYear = null, numberingStartSequence = null)
        state.numberingStartYearText = ""
        state.numberingStartSequenceText = ""
        assertTrue(state.validate().isEmpty())
        assertEquals("FV-2026-001", state.previewNumber())
    }

    @Test fun `titles longer than the EPO limit are refused`() {
        val state = SettingsState(DataRepository(dir))
        state.draft = full.copy(title = "Ing.", titleAfter = "Ph.D.")
        assertEquals(emptyList(), state.validate())
        state.draft = full.copy(title = "Ing. Mgr.", titleAfter = "Ph.D.")
        assertEquals(listOf(Messages.get("ui.settings.error.titles")), state.validate())
    }

    @Test fun `composed name puts the title after the name behind a comma`() {
        val state = SettingsState(DataRepository(dir))
        state.draft = full.copy(title = "Ing.", firstName = "Jan", lastName = "Novák", titleAfter = "Ph.D.")
        assertEquals("Ing. Jan Novák, Ph.D.", state.composedName())
        state.draft = full.copy(title = "", firstName = "Jan", lastName = "Novák", titleAfter = "")
        assertEquals("Jan Novák", state.composedName())
    }

    @Test fun `save persists and reloads`() {
        val state = SettingsState(DataRepository(dir))
        state.draft = full
        state.save()
        assertEquals(full, DataRepository(dir).loadConfig())
        assertEquals(full, SettingsState(DataRepository(dir)).draft)
    }

    @Test fun `failed save leaves state unchanged`() {
        val state = SettingsState(DataRepository(dir))
        state.draft = full
        state.dueDaysText = "30"
        state.numberingStartYearText = "2027"
        java.nio.file.Files.createDirectories(dir.resolve("config.xml"))
        assertFailsWith<Exception> { state.save() }
        assertEquals(full, state.draft)
        assertEquals("30", state.dueDaysText)
        assertEquals("2027", state.numberingStartYearText)
        assertEquals(null, state.numberingStartSequenceText)
    }

    @Test fun `each blank seller identity or address field is reported`() {
        val state = SettingsState(DataRepository(dir))
        val blanks = mapOf(
            "name" to full.copy(name = " "), "firstName" to full.copy(firstName = ""), "lastName" to full.copy(lastName = ""),
            "street" to full.copy(address = full.address.copy(street = "")), "city" to full.copy(address = full.address.copy(city = "")),
            "postcode" to full.copy(address = full.address.copy(postcode = " ")),
        )
        for ((key, config) in blanks) {
            state.draft = config
            assertEquals(listOf(Messages.get("ui.settings.error.$key")), state.validate(), key)
        }
    }

    @Test fun `numbering start year and sequence must be set together`() {
        val state = SettingsState(DataRepository(dir))
        state.draft = full
        val error = listOf(Messages.get("ui.settings.error.numberingStartPair"))
        state.numberingStartYearText = ""
        state.numberingStartSequenceText = "7"
        assertEquals(error, state.validate())
        state.numberingStartYearText = "2026"
        state.numberingStartSequenceText = " "
        assertEquals(error, state.validate())
        state.numberingStartSequenceText = "7"
        assertTrue(state.validate().isEmpty())
        state.numberingStartYearText = ""
        state.numberingStartSequenceText = ""
        assertTrue(state.validate().isEmpty())
    }

    @Test fun `next number follows the saved numbering start for the current year`() {
        val clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-06T10:00:00Z"), java.time.ZoneOffset.UTC)
        val state = SettingsState(DataRepository(dir), clock)
        assertEquals(null, state.nextNumber())
        state.draft = full
        state.save()
        assertEquals("2600023", state.nextNumber())
        state.numberingStartSequenceText = "40"
        state.save()
        assertEquals("2600040", state.nextNumber())
    }

    @Test fun `unreadable config fails on creation so the screen can show an error`() {
        java.nio.file.Files.writeString(dir.resolve("config.xml"), "<config><broken")
        assertFailsWith<Exception> { SettingsState(DataRepository(dir)) }
    }
}
