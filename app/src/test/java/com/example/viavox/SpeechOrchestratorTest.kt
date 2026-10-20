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

    @Test fun acronymAndRoadNamesAreReadNaturallyWithoutCorruptingGISNames() {
        assertEquals("vigésima quarta cícom",
            SpeechTextNormalizer.normalize("24ª CICOM"))
        assertEquals("Comando Geral",
            SpeechTextNormalizer.normalize("COMANDO GERALCMDOGERAL"))
        assertEquals("BR 1 7 4",
            SpeechTextNormalizer.normalize("BR-174"))
        assertEquals("AM 0 1 0",
            SpeechTextNormalizer.normalize("AM-010"))
        assertEquals("Rua Pará",SpeechTextNormalizer.normalize("Rua Pará"))
        assertEquals("úpa São Jorge",SpeechTextNormalizer.normalize("UPA São Jorge"))
        assertEquals("Polícia Militar do Amazonas",SpeechTextNormalizer.normalize("PMAM"))
        assertEquals("Hospital Geral",SpeechTextNormalizer.normalize("HOSPITAL GERAL"))
    }
}
