package com.example.viavox.ui.components

/** Human-readable, provider-neutral location diagnostics; HTTP codes stay in logs. */
object PoiStatusPresenter {
    fun display(raw: String): String {
        val message = raw.trim()
        return when {
            message.isBlank() -> "Referências: aguardando localização"
            message.contains("indisponível",ignoreCase = true) ||
                message.contains("Sem conexão",ignoreCase = true) ||
                Regex("""\b[45]\d\d\b""").containsMatchIn(message) ->
                "Referências indisponíveis no momento. O GPS e a narração de ruas continuam ativos."
            message.startsWith("Nenhum ponto",ignoreCase = true) ->
                "Nenhum ponto de referência cadastrado perto daqui."
            message.startsWith("Aguardando GPS",ignoreCase = true) ->
                "Aguardando melhor precisão do GPS."
            message.startsWith("Buscando",ignoreCase = true) ||
                message.startsWith("Procurando",ignoreCase = true) ->
                "Procurando pontos de referência próximos..."
            else -> message.replace(Regex("""\s*\([45]\d\d\)"""),"")
        }
    }
}
