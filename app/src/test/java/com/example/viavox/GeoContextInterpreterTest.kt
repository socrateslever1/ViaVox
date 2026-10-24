package com.example.viavox

import com.example.viavox.data.GeoContextInterpreter
import com.example.viavox.model.LandmarkCandidate
import com.example.viavox.model.NearbyLandmark
import org.junit.Assert.*
import org.junit.Test

class GeoContextInterpreterTest {
    private fun item(id:String,name:String,kind:String,meters:Double,angle:Double?=null) =
        LandmarkCandidate(NearbyLandmark(id,name,-3.1,-60.0,kind),meters,angle)

    @Test fun importantPublicTerminalCanOutrankGenericNearbyShop() {
        val generic=item("shop","Loja comercial","shop",55.0)
        val terminal=item("terminal","Terminal Rodoviário de Manaus","bus_station",190.0)
        val ordered=GeoContextInterpreter.rank(listOf(generic,terminal),false)
        assertEquals("terminal",ordered.first().landmark.id)
        assertEquals("shop",ordered.last().landmark.id)
    }

    @Test fun equallyImportantPlacesFavorCloseAndAhead() {
        val ahead=item("ahead","Museu A","tourism",90.0,0.0)
        val side=item("side","Museu B","tourism",90.0,60.0)
        assertEquals("ahead",
            GeoContextInterpreter.rank(listOf(side,ahead),true).first().landmark.id)
    }

    @Test fun scoresOnlyTheRealCandidatesAndIsDeterministic() {
        val a=item("a","Escola","amenity",70.0)
        val b=item("b","Hospital Geral","amenity",130.0)
        val result=GeoContextInterpreter.rank(listOf(a,b),false)
        assertEquals(setOf("a","b"),result.map { it.landmark.id }.toSet())
        assertEquals(result,GeoContextInterpreter.rank(listOf(b,a),false))
    }
}
