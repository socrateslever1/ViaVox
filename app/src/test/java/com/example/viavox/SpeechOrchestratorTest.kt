package com.example.viavox

import com.example.viavox.service.GeographicTransition
import com.example.viavox.service.SpeechEvent
import com.example.viavox.service.SpeechKind
import com.example.viavox.service.SpeechOrchestrator
import com.example.viavox.service.SpeechTextNormalizer
import org.junit.Assert.*
import org.junit.Test

class SpeechOrchestratorTest {
    @Test fun neverStartsSecondUtteranceUntilPreviousActuallyFinishes() {
        var done: (() -> Unit)? = null
        var now = 1_000L
        val output = mutableListOf<String>()
        val queue = SpeechOrchestrator(
            play = { text, finished -> output.add(text); done = finished },
            clock = { now }
        )
        assertTrue(queue.offer(SpeechKind.POI,"Posto à direita.","poi:gas"))
        assertTrue(queue.offer(SpeechKind.GEOGRAPHY,"Rua Benjamin Constant.","geo:benjamin"))
        assertTrue(queue.offer(SpeechKind.NAVIGATION,"Vire à direita.","nav:right"))
        assertEquals(listOf("Posto à direita."),output)
        assertEquals(2,queue.pendingCount)
        done?.invoke()
        assertEquals(listOf("Posto à direita.","Vire à direita."),output)
        done?.invoke()
        assertEquals("Rua Benjamin Constant.",output.last())
        done?.invoke()
        assertFalse(queue.isBusy)
    }

    @Test fun dropsObsoletePoiAndKeepsCurrentRoad() {
        var now = 10_000L
        var done: (() -> Unit)? = null
        val played = mutableListOf<String>()
        val q = SpeechOrchestrator(
            play = { text, cb -> played.add(text);done = cb },clock = { now }
        )
        q.offer(SpeechKind.USER_REQUEST,"Teste.","voice-test")
        q.offer(SpeechKind.POI,"Hospital antigo","poi:old")
        q.offer(SpeechKind.POI,"Hospital novo","poi:new")
        q.offer(SpeechKind.GEOGRAPHY,"Rua Pará","geo:para")
        now += 12_000L
        done?.invoke()
        assertEquals(listOf("Teste.","Rua Pará"),played)
        done?.invoke()
        assertEquals(2,played.size)
    }

    @Test fun cancelInvalidatesOutstandingAudioCallback() {
        var done: (() -> Unit)? = null
        val played = mutableListOf<String>()
        val q = SpeechOrchestrator(
            play = { text, callback -> played.add(text);done=callback }
        )
        q.offer(SpeechKind.POI,"Ponto antigo","poi:old")
        q.offer(SpeechKind.GEOGRAPHY,"Rua Nova","geo:novo")
        q.cancelAll()
        done?.invoke()
        assertEquals(1,played.size)
        assertEquals(0,q.pendingCount)
        assertFalse(q.isBusy)
        q.offer(SpeechKind.GEOGRAPHY,"Rua atual","geo:atual")
        assertEquals("Rua atual",played.last())
    }

    @Test fun oneEventAtATimeAndDuplicateQueueEntriesAreRefused() {
        var done: (() -> Unit)? = null
        val emitted = mutableListOf<String>()
        val q = SpeechOrchestrator(play={text,callback->emitted.add(text);done=callback})
        q.offer(SpeechKind.GEOGRAPHY,"Zona Centro-Sul.","geo:zona")
        assertFalse(q.offer(SpeechKind.GEOGRAPHY,"Zona Centro-Sul.","geo:zona"))
        done?.invoke()
        assertEquals(1,emitted.size)
        q.setEnabled(false)
        assertFalse(q.offer(SpeechKind.POI,"Estabelecimento","poi:store"))
        assertEquals(0,q.pendingCount)
    }

    @Test fun simultaneousGeographicEventsHaveUserRequestedOrder() {
        assertEquals(
            "Entrando na Zona Centro-Sul. Bairro Petrópolis. Rua Benjamin Constant.",
            GeographicTransition("Zona Centro-Sul","Petrópolis","Rua Benjamin Constant").sentence()
        )
        assertEquals("Rua Aristides Rocha.",
            GeographicTransition(road="Rua Aristides Rocha").sentence())
        assertEquals("Bairro Centro.",
            GeographicTransition(neighbourhood="Centro").sentence())
    }

    @Test fun cicomFollowsRoadAndIsExpandedNaturallyForAudio() {
        val transition = GeographicTransition(
            zone = "Zona Leste",
            neighbourhood = "Coroado",
            road = "Avenida Cosme Ferreira",
            policeCircumscription = "11ª CICOM"
        )
        assertEquals(
            "Entrando na Zona Leste. Bairro Coroado. Avenida Cosme Ferreira. Área da 11ª CICOM.",
            transition.sentence()
        )
        assertEquals(
            "Entrando na Zona Leste. Bairro Coroado. Avenida Cosme Ferreira. Área da décima primeira companhia interativa comunitária.",
            SpeechTextNormalizer.normalize(transition.sentence())
        )
    }

    @Test fun queuedPoiIsSilencedIfPositionIsNoLongerValidWhenItWouldSpeak() {
        var finish: (() -> Unit)? = null
        val utterances = mutableListOf<String>()
        var poiNearby = true
        val queue = SpeechOrchestrator(
            play = { text, callback -> utterances.add(text); finish = callback },
            canPlay = { event -> event.kind != SpeechKind.POI || poiNearby }
        )
        queue.offer(SpeechKind.NAVIGATION,"Vire na próxima rua","nav:1")
        queue.offer(SpeechKind.POI,"Delegacia próxima","poi:12")
        poiNearby = false // Rode away from the 30m geofence during navigation
        finish?.invoke()
        assertEquals(listOf("Vire na próxima rua"),utterances)
        assertFalse(queue.isBusy)
        assertEquals(0,queue.pendingCount)
    }

    @Test fun unpronounceableGisAcronymsAreSilencedInsteadOfSpelled() {
        assertEquals("vigésima quarta companhia interativa comunitária",
            SpeechTextNormalizer.normalizeReferenceName("24ª CICOM"))
        assertEquals("Hospital Geral",
            SpeechTextNormalizer.normalizeReferenceName("HOSPITAL GERAL"))
        assertEquals("Comando Geral",
            SpeechTextNormalizer.normalizeReferenceName("COMANDO GERALCMDOGERAL"))
        assertEquals("Polícia Militar do Amazonas",
            SpeechTextNormalizer.normalizeReferenceName("PMAM"))
        assertEquals("Secretaria de Segurança Pública",
            SpeechTextNormalizer.normalizeReferenceName("SSP"))
        assertEquals(null,
            SpeechTextNormalizer.normalizeReferenceName("HOSPITAL QWRT"))
        assertEquals(null,
            SpeechTextNormalizer.normalizeReferenceName("DELEGACIA XPT"))
    }

    @Test fun acronymAndRoadNamesAreReadNaturallyWithoutCorruptingGISNames() {
        assertEquals("vigésima quarta companhia interativa comunitária",
            SpeechTextNormalizer.normalize("24ª CICOM"))
        assertEquals("Comando Geral",
            SpeechTextNormalizer.normalize("COMANDO GERALCMDOGERAL"))
        assertEquals("BR 1 7 4",
            SpeechTextNormalizer.normalize("BR-174"))
        assertEquals("AM 0 1 0",
            SpeechTextNormalizer.normalize("AM-010"))
        assertEquals("Rua Pará",SpeechTextNormalizer.normalize("Rua Pará"))
        assertEquals("unidade de pronto atendimento São Jorge",SpeechTextNormalizer.normalize("UPA São Jorge"))
        assertEquals("Polícia Militar do Amazonas",SpeechTextNormalizer.normalize("PMAM"))
        assertEquals("Centro Municipal de Educação Infantil",
            SpeechTextNormalizer.normalizeReferenceName("CMEI"))
        assertEquals("Instituto Médico Legal",
            SpeechTextNormalizer.normalizeReferenceName("IML"))
        assertNull(SpeechTextNormalizer.normalizeReferenceName("Drogaria Ltda"))
        assertEquals("Hospital Geral",SpeechTextNormalizer.normalize("HOSPITAL GERAL"))
        assertEquals("Distrito Integrado de Polícia",SpeechTextNormalizer.normalize("DIP"))
        assertEquals("Instituto Federal do Amazonas",SpeechTextNormalizer.normalize("IFAM"))
        assertEquals("Avenida Eduardo Ribeiro",SpeechTextNormalizer.normalize("Av. Eduardo Ribeiro"))
        assertEquals("Rua Pará",SpeechTextNormalizer.normalize("R. Pará"))
    }
}
