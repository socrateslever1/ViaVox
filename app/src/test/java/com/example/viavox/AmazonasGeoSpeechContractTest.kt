package com.example.viavox

import com.example.viavox.data.AmazonasMunicipalityDirectory
import com.example.viavox.data.GeoSpeechPolicy
import com.example.viavox.model.NearbyLandmark
import com.example.viavox.model.ScanDirection
import com.example.viavox.model.SurroundingsPolicy
import com.example.viavox.service.SpeechTextNormalizer
import org.junit.Assert.*
import org.junit.Test

class AmazonasGeoSpeechContractTest {
    @Test fun all62MunicipalitiesAreAvailableOnFirstOfflineRun() {
        assertEquals(62,AmazonasMunicipalityDirectory.all.size)
        assertEquals(62,AmazonasMunicipalityDirectory.all.map { it.first }.toSet().size)
        assertEquals(1302603,AmazonasMunicipalityDirectory.codeOf("manaus"))
        assertEquals(1301902,AmazonasMunicipalityDirectory.codeOf("Itacoatiara"))
        assertEquals(1304062,AmazonasMunicipalityDirectory.codeOf("tabatinga"))
    }

    @Test fun officialNameIsKeptButSpokenNameIsShortAndSpecific() {
        val official="Terminal Rodoviário Engenheiro Huascar Angelim"
        assertEquals("Terminal Rodoviário de Manaus",
            GeoSpeechPolicy.referenceAlias(official))
        assertEquals("Terminal Rodoviário de Manaus",
            SpeechTextNormalizer.normalizeReferenceName(official))
        assertEquals("Terminal Rodoviário de Manaus",
            SpeechTextNormalizer.normalize("Ao seu lado, "+official)
                .removePrefix("Ao seu lado, "))
        assertEquals(null,GeoSpeechPolicy.referenceAlias("Terminal Rodoviário de Tefé"))
        assertEquals("terminal rodoviario engenheiro huascar angelim",
            GeoSpeechPolicy.officialSearchNameFor("Terminal Rodoviário de Manaus"))
        assertNull(GeoSpeechPolicy.officialSearchNameFor("Terminal Rodoviário de Tefé"))
    }

    @Test fun approvedAliasesCanBeUpdatedWithoutChangingOfficialNames() {
        val official="Centro Cultural do Amazonas"
        assertNull(GeoSpeechPolicy.referenceAlias(official))
        try {
            GeoSpeechPolicy.installCatalogueAliases(
                mapOf("centro cultural do amazonas" to "Centro Cultural")
            )
            assertEquals("Centro Cultural",GeoSpeechPolicy.referenceAlias(official))
            assertEquals("Centro Cultural",
                SpeechTextNormalizer.normalizeReferenceName(official))
        } finally {
            GeoSpeechPolicy.installCatalogueAliases(emptyMap())
        }
        assertNull(GeoSpeechPolicy.referenceAlias(official))
    }

    @Test fun forbidsSpellingRawIdentifiersButKeepsNaturalUppercaseNames() {
        assertNull(GeoSpeechPolicy.speakableReference("DELEGACIA QWRT"))
        assertNull(GeoSpeechPolicy.speakableReference("HOSPITAL XPT"))
        assertEquals("Hospital Geral",
            SpeechTextNormalizer.normalizeReferenceName("HOSPITAL GERAL"))
        assertEquals("Polícia Militar do Amazonas",
            SpeechTextNormalizer.normalizeReferenceName("PMAM"))
        assertFalse(SpeechTextNormalizer.normalize("Rua QWRT").contains("QWRT"))
        assertEquals("Comando de Policiamento de Área Sul",
            SpeechTextNormalizer.normalize("CPA Sul"))
        assertEquals("Rodovia federal 174",
            SpeechTextNormalizer.normalize("BR-174"))
    }

    @Test fun nearbyAudioAppliesAliasWithoutMutatingOfficialName() {
        val official="Terminal Rodoviário Engenheiro Huascar Angelim"
        val poi=NearbyLandmark("terminal",official,-3.1070,-60.0160,"bus_station")
        val chosen=SurroundingsPolicy.select(
            listOf(poi),-3.1075,-60.0160,100,
            ScanDirection.AROUND,null,5f
        )
        assertEquals(official,chosen.single().landmark.name)
        assertTrue(SurroundingsPolicy.scanSpeech(chosen,ScanDirection.AROUND,false)
            .contains("Terminal Rodoviário de Manaus"))
    }
}
