package cz.chap.fakturka.registry

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import cz.chap.fakturka.domain.Address
import cz.chap.fakturka.i18n.Messages
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Company data taken from ARES (Czech public business register). */
data class AresCompany(val ico: String, val name: String, val dic: String?, val address: Address)

/** A lookup that failed; the message is localized and meant for the user. */
class AresException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object AresParser {
    private val mapper = ObjectMapper()
    private val POSTCODE_LINE = Regex("""^(\d{3})\s?(\d{2})\s+(.+)$""")

    /** Parses one `ekonomicke-subjekty/{ico}` response. */
    fun parse(json: String): AresCompany {
        val root = mapper.readTree(json)
        val seat = root.path("sidlo")
        return AresCompany(
            ico = root.text("ico") ?: "",
            name = root.text("obchodniJmeno") ?: "",
            // A DIČ is assigned to many subjects; only an active VAT registration makes it a VAT ID for the KH.
            dic = root.text("dic")?.takeIf { root.path("seznamRegistraci").text("stavZdrojeDph") == "AKTIVNI" },
            address = address(seat, root.path("adresaDorucovaci")),
        )
    }

    private fun address(seat: JsonNode, delivery: JsonNode): Address {
        val street = seat.text("nazevUlice") ?: seat.text("nazevCastiObce") ?: seat.text("nazevObce") ?: ""
        val orientation = seat.text("cisloOrientacni")?.let { it + (seat.text("cisloOrientacniPismeno") ?: "") }
        val house = listOfNotNull(seat.text("cisloDomovni"), orientation).joinToString("/")
        // The postal line of the delivery address ("12000 Praha 2") matches what is printed on invoices;
        // the structured `psc` can differ from it, so it is only the fallback.
        val postal = delivery.fieldNames().asSequence().filter { it.startsWith("radekAdresy") }.sorted()
            .mapNotNull { delivery.text(it)?.let(POSTCODE_LINE::matchEntire) }.lastOrNull()
        val postcode = postal?.let { it.groupValues[1] + it.groupValues[2] } ?: seat.text("psc")?.padStart(5, '0') ?: ""
        val city = postal?.groupValues?.get(3) ?: seat.text("nazevObce") ?: ""
        return Address(street, house, city, postcode, seat.text("nazevStatu") ?: "Česká republika")
    }

    private fun JsonNode.text(field: String): String? =
        get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
}

/** Looks up a subject by IČ in ARES. Blocking; call it off the UI thread. */
class AresClient(
    private val baseUrl: String = "https://ares.gov.cz/ekonomicke-subjekty-v-be/rest/ekonomicke-subjekty",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    /** Returns the subject, or null when ARES does not know the IČ. */
    fun lookup(ico: String): AresCompany? {
        val digits = ico.filterNot { it.isWhitespace() }
        if (digits.isEmpty() || digits.length > 8 || !digits.all { it.isDigit() }) {
            throw AresException(Messages.get("ui.ares.error.ico"))
        }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/${digits.padStart(8, '0')}"))
            .header("Accept", "application/json")
            .header("User-Agent", "Fakturka")
            .timeout(Duration.ofSeconds(15))
            .GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw AresException(Messages.get("ui.ares.error.network").format(e.message ?: e.javaClass.simpleName), e)
        }
        return when (response.statusCode()) {
            200 -> AresParser.parse(response.body())
            404 -> null
            else -> throw AresException(Messages.get("ui.ares.error.network").format("HTTP ${response.statusCode()}"))
        }
    }
}
