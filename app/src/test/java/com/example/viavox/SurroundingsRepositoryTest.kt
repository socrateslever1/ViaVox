package com.example.viavox

import com.example.viavox.data.SurroundingsRepository
import com.example.viavox.model.ScanDirection
import com.example.viavox.model.SurroundingsPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real HTTP serialization/parsing flow, with an in-memory test transport.
 * No public map provider is contacted by unit tests.
 */
class SurroundingsRepositoryTest {
    private val latitude = -3.1200
    private val longitude = -60.0100

    @Test fun namedCommercialPlacesAppearInRequestedRadius() = runBlocking {
        val requests = AtomicInteger()
        val source = """
          {"elements":[
            {"type":"node","id":10,"lat":-3.11955,"lon":-60.0100,
             "tags":{"name":"Lojas Americanas","shop":"variety_store"}},
            {"type":"node","id":11,"lat":-3.11930,"lon":-60.0100,
             "tags":{"name":"Farmácia da Rua","amenity":"pharmacy"}}
          ]}
        """.trimIndent()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(source.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val repository = SurroundingsRepository(client)
        val first = repository.lookup(latitude, longitude, 200)
        assertNull(first.error)
        assertEquals(2, first.landmarks.size)
        assertEquals(1, requests.get())
        val chosen = SurroundingsPolicy.select(
            first.landmarks, latitude, longitude, 200,
            ScanDirection.AROUND, null, 5f
        )
        assertTrue(chosen.any { it.landmark.name == "Lojas Americanas" })
        assertTrue(chosen.any { it.landmark.name == "Farmácia da Rua" })
        repository.lookup(latitude + 0.0001, longitude, 200)
        assertEquals("Cache should tolerate ordinary GPS drift", 1, requests.get())
    }

    @Test fun failureIsNotInterpretedAsNoMappedReferences() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor {
            throw IOException("test network unavailable")
        }.build()
        val repository = SurroundingsRepository(client)
        val failure = repository.lookup(latitude, longitude, 200)
        assertTrue(failure.landmarks.isEmpty())
        assertNotNull(failure.error)
        assertTrue(failure.error!!.contains("indisponível"))
    }

    @Test fun manualRequestNotBlockedByEarlierAutomaticFailure() = runBlocking {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val attempt = attempts.incrementAndGet()
            if (attempt <= 2) throw IOException("offline")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"elements":[]}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val repository = SurroundingsRepository(client)
        val tour = repository.lookup(latitude, longitude, 200, automatic = true)
        assertNotNull(tour.error)
        val manual = repository.lookup(latitude, longitude, 200, automatic = false)
        assertNull(manual.error)
        assertTrue(attempts.get() > 2)
    }
}
