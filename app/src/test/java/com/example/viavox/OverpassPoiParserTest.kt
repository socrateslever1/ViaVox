package com.example.viavox

import com.example.viavox.data.OverpassPoiParser
import com.example.viavox.model.PoiCategory
import org.junit.Assert.*
import org.junit.Test

class OverpassPoiParserTest {
    @Test fun parsesOnlyPublicLandmarksWithSourceStreetAddress() {
        val json = """
        {"elements":[
          {"type":"node","id":11,"lat":-3.1000,"lon":-60.0000,
           "tags":{"name":"Posto Esperança","amenity":"fuel","addr:street":"Rua Benjamin Constant"}},
          {"type":"way","id":12,"center":{"lat":-3.1003,"lon":-60.0005},
           "tags":{"name":"Hospital Geral","amenity":"hospital","addr:street":"Rua Benjamin Constant"}},
          {"type":"relation","id":13,"center":{"lat":-3.1005,"lon":-60.0001},
           "tags":{"name":"Praça Central","leisure":"park","addr:street":"Rua Pará"}},
          {"type":"node","id":14,"lat":-3.1002,"lon":-60.0005,
           "tags":{"name":"Farmácia Popular","amenity":"pharmacy","addr:street":"Rua Pará"}},
          {"type":"node","id":15,"lat":-3.1002,"lon":-60.0005,
           "tags":{"name":"Escola sem endereço","amenity":"school"}}
        ]}
        """.trimIndent()
        val found=OverpassPoiParser.parse(json)
        assertEquals(listOf("osm_way_12","osm_relation_13"),found.map { it.id })
        assertEquals(listOf(PoiCategory.HEALTH,PoiCategory.PARK),found.map { it.category })
        assertEquals("Rua Benjamin Constant",found.first().streetAddress)
    }

    @Test fun neverInventsLocationsOrStreets() {
        val json = """
        {"elements":[
          {"type":"node","id":1,"lat":-3.0,"lon":-60.0,"tags":{"amenity":"hospital"}},
          {"type":"way","id":2,"tags":{"name":"Sem centro","amenity":"hospital",
           "addr:street":"Rua Benjamin Constant"}},
          {"type":"node","id":3,"lat":100.0,"lon":-60.0,"tags":{"name":"Impossível",
           "amenity":"school","addr:street":"Rua Benjamin Constant"}},
          {"type":"node","id":4,"lat":-3.0,"lon":-60.0,"tags":{"name":"Residência","building":"house",
           "addr:street":"Rua Benjamin Constant"}}
        ]}
        """.trimIndent()
        assertTrue(OverpassPoiParser.parse(json).isEmpty())
    }

    @Test fun preventsDuplicateNodesAndBuildings() {
        val json = """
        {"elements":[
          {"type":"node","id":101,"lat":-3.1,"lon":-60.0,
           "tags":{"name":"Hospital Geral","amenity":"hospital","addr:street":"Rua Pará"}},
          {"type":"way","id":202,"center":{"lat":-3.1001,"lon":-60.0001},
           "tags":{"name":"Hospital Geral","amenity":"hospital","addr:street":"Rua Pará"}}
        ]}
        """.trimIndent()
        assertEquals(1,OverpassPoiParser.parse(json).size)
    }
}
