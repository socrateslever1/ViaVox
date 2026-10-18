package com.example.viavox

import com.example.viavox.data.OverpassPoiParser
import com.example.viavox.model.PoiCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassPoiParserTest {
    @Test
    fun readsActualNodeWayAndRelationWithCentersAndStableIdentifiers() {
        val json = """
        {"elements":[
          {"type":"node","id":11,"lat":-3.1000,"lon":-60.0000,"tags":{"name":"Posto Esperança","amenity":"fuel"}},
          {"type":"way","id":12,"center":{"lat":-3.1003,"lon":-60.0005},"tags":{"name":"Hospital Geral","amenity":"hospital"}},
          {"type":"relation","id":13,"center":{"lat":-3.1005,"lon":-60.0001},"tags":{"name":"Praça Central","leisure":"park"}}
        ]}
        """.trimIndent()
        val found = OverpassPoiParser.parse(json)
        assertEquals(3, found.size)
        assertEquals(listOf("osm_node_11","osm_way_12","osm_relation_13"), found.map { it.id })
        assertEquals(listOf(PoiCategory.FUEL,PoiCategory.HEALTH,PoiCategory.PARK), found.map { it.category })
    }

    @Test
    fun neverInventsUnmappedNamesOrCoordinates() {
        val json = """
        {"elements":[
          {"type":"node","id":1,"lat":-3.0,"lon":-60.0,"tags":{"amenity":"fuel"}},
          {"type":"way","id":2,"tags":{"name":"Sem centro","amenity":"hospital"}},
          {"type":"node","id":3,"lat":100.0,"lon":-60.0,"tags":{"name":"Impossível","amenity":"fuel"}},
          {"type":"node","id":4,"lat":-3.0,"lon":-60.0,"tags":{"name":"Residência","building":"house"}}
        ]}
        """.trimIndent()
        assertTrue(OverpassPoiParser.parse(json).isEmpty())
    }

    @Test
    fun sameNamedLandmarkMappedAsBuildingAndNodeIsOnlyOneCandidate() {
        val json = """
        {"elements":[
          {"type":"node","id":101,"lat":-3.1,"lon":-60.0,"tags":{"name":"UPA Bairro Seguro","amenity":"clinic"}},
          {"type":"way","id":202,"center":{"lat":-3.1001,"lon":-60.0001},"tags":{"name":"UPA Bairro Seguro","amenity":"clinic"}}
        ]}
        """.trimIndent()
        assertEquals(1, OverpassPoiParser.parse(json).size)
    }
}
