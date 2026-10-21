package com.example.viavox

import com.example.viavox.data.RecentDestinationsStore
import com.example.viavox.model.MapPlace
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentDestinationsStoreTest {

    @Test
    fun `pushRecent keeps at most 5 destinations with most recent first`() {
        var list = emptyList<MapPlace>()
        val places = listOf(
            MapPlace("Teatro Amazonas", -3.1303, -60.0234, "Centro · Manaus"),
            MapPlace("Arena da Amazônia", -3.0831, -60.0281, "Flores · Manaus"),
            MapPlace("Ponta Negra", -3.0680, -60.1000, "Ponta Negra · Manaus"),
            MapPlace("Manauara Shopping", -3.1038, -60.0124, "Adrianópolis · Manaus"),
            MapPlace("Aeroporto Eduardo Gomes", -3.0386, -60.0497, "Tarumã · Manaus"),
            MapPlace("Porto de Manaus", -3.1380, -60.0265, "Centro · Manaus")
        )

        places.forEach { place ->
            list = RecentDestinationsStore.pushRecent(list, place)
        }

        assertEquals(5, list.size)
        assertEquals("Porto de Manaus", list[0].name)
        assertEquals("Aeroporto Eduardo Gomes", list[1].name)
        assertEquals("Manauara Shopping", list[2].name)
        assertEquals("Ponta Negra", list[3].name)
        assertEquals("Arena da Amazônia", list[4].name)
    }

    @Test
    fun `pushRecent deduplicates repeated destination and moves it to top`() {
        val p1 = MapPlace("Teatro Amazonas", -3.1303, -60.0234, "Centro")
        val p2 = MapPlace("Arena da Amazônia", -3.0831, -60.0281, "Flores")
        var list = RecentDestinationsStore.pushRecent(emptyList(), p1)
        list = RecentDestinationsStore.pushRecent(list, p2)
        list = RecentDestinationsStore.pushRecent(list, p1)

        assertEquals(2, list.size)
        assertEquals("Teatro Amazonas", list[0].name)
        assertEquals("Arena da Amazônia", list[1].name)
    }

    @Test
    fun `encodeJson and decodeJson roundtrip preserves up to 5 items`() {
        val original = listOf(
            MapPlace("Teatro Amazonas", -3.1303, -60.0234, "Centro · Manaus"),
            MapPlace("Ponta Negra", -3.0680, -60.1000, "Ponta Negra · Manaus")
        )
        val encoded = RecentDestinationsStore.encodeJson(original)
        val decoded = RecentDestinationsStore.decodeJson(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `toggleFavoriteInList pins new address and unpins when toggled again`() {
        val teatro = MapPlace("Teatro Amazonas", -3.1303, -60.0234, "Centro · Manaus")
        val arena = MapPlace("Arena da Amazônia", -3.0831, -60.0281, "Flores · Manaus")

        var favorites = emptyList<MapPlace>()
        favorites = RecentDestinationsStore.toggleFavoriteInList(favorites, teatro)
        assertEquals(1, favorites.size)
        assertEquals(true, RecentDestinationsStore.isFavoriteInList(favorites, teatro))
        assertEquals(false, RecentDestinationsStore.isFavoriteInList(favorites, arena))

        favorites = RecentDestinationsStore.toggleFavoriteInList(favorites, arena)
        assertEquals(2, favorites.size)
        assertEquals("Arena da Amazônia", favorites[0].name)
        assertEquals("Teatro Amazonas", favorites[1].name)

        // Toggling Teatro Amazonas again unpins it from Favorites
        favorites = RecentDestinationsStore.toggleFavoriteInList(favorites, teatro)
        assertEquals(1, favorites.size)
        assertEquals("Arena da Amazônia", favorites[0].name)
        assertEquals(false, RecentDestinationsStore.isFavoriteInList(favorites, teatro))
    }
}

