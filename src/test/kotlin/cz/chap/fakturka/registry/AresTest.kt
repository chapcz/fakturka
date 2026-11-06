package cz.chap.fakturka.registry

import com.sun.net.httpserver.HttpServer
import cz.chap.fakturka.domain.Address
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AresTest {
    // Shape of a real ARES response, with fictional values.
    private val vatPayer = """
        {"ico":"12345678","obchodniJmeno":"Testovací firma s.r.o.",
         "sidlo":{"kodStatu":"CZ","nazevStatu":"Česká republika","nazevObce":"Praha","nazevUlice":"Smyšlená",
                  "cisloDomovni":742,"cisloOrientacni":15,"cisloOrientacniPismeno":"b","nazevCastiObce":"Vinohrady",
                  "psc":12000,"textovaAdresa":"Smyšlená 742/15b, Vinohrady, 12000 Praha 2"},
         "dic":"CZ12345678",
         "adresaDorucovaci":{"radekAdresy1":"Smyšlená 742/15b","radekAdresy2":"Vinohrady","radekAdresy3":"12000 Praha 2"},
         "seznamRegistraci":{"stavZdrojeDph":"AKTIVNI"}}
    """.trimIndent()

    private val villageNonPayer = """
        {"ico":"87654321","obchodniJmeno":"Jan Testovací",
         "sidlo":{"nazevStatu":"Česká republika","nazevObce":"Lhota","cisloDomovni":5,"psc":12345},
         "dic":"CZ8001011234",
         "seznamRegistraci":{"stavZdrojeDph":"NEEXISTUJICI"}}
    """.trimIndent()

    private var server: HttpServer? = null

    @AfterTest fun stop() { server?.stop(0) }

    private fun serve(status: Int, body: String): AresClient {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return AresClient("http://127.0.0.1:${s.address.port}/subjekty")
    }

    @Test fun `parses name, VAT ID and address with postal line from delivery address`() {
        val c = AresParser.parse(vatPayer)
        assertEquals("12345678", c.ico)
        assertEquals("Testovací firma s.r.o.", c.name)
        assertEquals("CZ12345678", c.dic)
        assertEquals(Address("Smyšlená", "742/15b", "Praha 2", "12000", "Česká republika"), c.address)
    }

    @Test fun `no VAT ID without active VAT registration, village without street`() {
        val c = AresParser.parse(villageNonPayer)
        assertNull(c.dic)
        assertEquals(Address("Lhota", "5", "Lhota", "12345", "Česká republika"), c.address)
    }

    @Test fun `client returns the subject on 200 and null on 404`() {
        assertEquals("Testovací firma s.r.o.", serve(200, vatPayer).lookup("12345678")?.name)
        stop()
        assertNull(serve(404, """{"kod":"NENALEZENO"}""").lookup("00000019"))
    }

    @Test fun `server error and invalid IČ are reported as AresException`() {
        val client = serve(500, "")
        assertTrue(assertFailsWith<AresException> { client.lookup("12345678") }.message!!.contains("500"))
        assertFailsWith<AresException> { client.lookup("123456789") }
        assertFailsWith<AresException> { client.lookup("12a") }
    }
}
