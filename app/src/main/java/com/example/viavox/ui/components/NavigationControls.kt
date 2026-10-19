package com.example.viavox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationMath
import com.example.viavox.model.SavedTrip
import com.example.viavox.model.StoredRoute
import com.example.viavox.viewmodel.NavigationUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val navy = Color(0xED0C1D32)
private val aqua = Color(0xFF40E1C9)
private val pale = Color(0xFFF4F8FF)
private val hint = Color(0xFFC2D3E3)

@Composable
fun NavigationSearchAndActions(
    ui: NavigationUiState,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenSearch: () -> Unit,
    onShowSuggestions: () -> Unit,
    onDismissSuggestions: () -> Unit,
    onSelectSuggestion: (MapPlace) -> Unit,
    onOpenSaved: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenHistory: () -> Unit,
    onSaveCurrentPosition: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Surface(
            color = Color(0xF5091D31),
            shape = RoundedCornerShape(19.dp),
            shadowElevation = 9.dp,
            border = BorderStroke(1.dp,Color.White.copy(alpha = 0.15f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 55.dp).padding(start = 12.dp,end = 4.dp)
            ) {
                Icon(Icons.Default.Search,null,tint = aqua,modifier = Modifier.size(22.dp))
                TextField(
                    value = ui.query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.weight(1f)
                        .onFocusChanged { if (it.isFocused) onShowSuggestions() }
                        .testTag("navigation_search_field"),
                    placeholder = { Text("Para onde vamos?",color = hint,fontSize = 14.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = pale,fontSize = 14.sp,fontWeight = FontWeight.Medium
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = aqua
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide();onSearch() })
                )
                if (ui.query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChanged("");onDismissSuggestions() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close,"Limpar pesquisa",tint = hint,
                            modifier = Modifier.size(17.dp))
                    }
                } else {
                    IconButton(onClick = onSaveCurrentPosition,modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.BookmarkAdd,"Salvar localização",tint = hint,
                            modifier = Modifier.size(19.dp))
                    }
                }
                IconButton(onClick = { keyboard?.hide();onSearch() },
                    modifier = Modifier.size(40.dp).testTag("run_search_button")) {
                    Icon(Icons.Default.ArrowForward,"Pesquisar destino",tint = aqua)
                }
            }
        }
        if (ui.suggestionsVisible && ui.query.trim().length >= 3) {
            Surface(
                color = Color(0xFA10283F),
                shape = RoundedCornerShape(17.dp),shadowElevation = 12.dp,
                border = BorderStroke(1.dp,Color.White.copy(alpha = 0.14f)),
                modifier = Modifier.fillMaxWidth().testTag("autocomplete_dropdown")
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp,vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("DESTINOS",fontSize = 10.sp,fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,color = aqua,modifier = Modifier.weight(1f))
                        if (ui.suggesting || ui.searching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),strokeWidth = 1.5.dp,color = aqua
                            )
                        }
                        TextButton(
                            onClick = { keyboard?.hide();onDismissSuggestions() },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 7.dp,vertical = 0.dp)
                        ) { Text("Fechar",fontSize = 11.sp,color = hint) }
                    }
                    ui.results.take(5).forEach { place ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { keyboard?.hide();onSelectSuggestion(place) }
                                .padding(vertical = 7.dp).testTag("suggestion_result"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(34.dp)
                                    .background(Color(0xFF23465D),CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Place,null,tint = aqua,
                                    modifier = Modifier.size(19.dp))
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(place.name,color = pale,fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,maxLines = 1,overflow = TextOverflow.Ellipsis)
                                if (place.subtitle.isNotBlank()) Text(
                                    place.subtitle,color = hint,fontSize = 11.sp,
                                    maxLines = 1,overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(Icons.Default.ChevronRight,null,tint = hint,
                                modifier = Modifier.size(17.dp))
                        }
                    }
                    if (ui.results.isEmpty()) {
                        Text(
                            when {
                                ui.error != null -> ui.error
                                ui.searching || ui.suggesting -> "Procurando locais próximos..."
                                else -> "Sem resultados próximos. Escolha um ponto no mapa."
                            },
                            color = if (ui.error == null) hint else Color(0xFFFFC5B2),
                            fontSize = 12.sp,modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickAction("Salvos",Icons.Default.Bookmark,onOpenSaved,Modifier.weight(1f))
                QuickAction("Rotas",Icons.Default.Route,onOpenRoutes,Modifier.weight(1f))
                QuickAction("Percursos",Icons.Default.History,onOpenHistory,Modifier.weight(1f))
                QuickAction(
                    if (ui.recording) "Parar" else "Gravar",
                    if (ui.recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    if (ui.recording) onStopRecording else onStartRecording,
                    Modifier.weight(1f),ui.recording
                )
            }
            if (ui.recording) Surface(
                color = Color(0xDD6E2B38),shape = RoundedCornerShape(9.dp)
            ) {
                Text(
                    "● Gravando · ${NavigationMath.formatDistance(ui.recordedMeters)} · ${ui.recordedCount} pontos",
                    color = pale,fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp,vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (active) Color(0xEF702D40) else Color(0xEE0A2035),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp,Color.White.copy(alpha = 0.11f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon,null,
                tint = if (active) Color(0xFFFFB4BA) else aqua,
                modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(3.dp))
            Text(label,color = pale,fontSize = 10.sp,maxLines = 1)
        }
    }
}

@Composable
fun NavigationRouteCard(
    ui: NavigationUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSaveRoute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val route = ui.route
    val destination = ui.selected
    if (route == null && destination == null) return

    Surface(
        color = navy,shape = RoundedCornerShape(23.dp),
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp,Color.White.copy(alpha = 0.14f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Text(
                if (ui.navigating) "NAVEGAÇÃO ATIVA" else "DESTINO SELECIONADO",
                color = aqua,fontSize = 10.sp,fontWeight = FontWeight.Bold
            )
            Text(
                destination?.name ?: route?.destination?.name.orEmpty(),
                color = pale,fontSize = 17.sp,fontWeight = FontWeight.Bold,maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (ui.navigating) {
                Text(
                    "${NavigationMath.formatDistance(ui.metersUntilTurn)} · ${ui.nextInstruction}",
                    color = hint,fontSize = 13.sp,maxLines = 2
                )
            } else if (route != null) {
                val etaMinutes = (route.durationSeconds / 60.0).toInt().coerceAtLeast(1)
                Text(
                    "${NavigationMath.formatDistance(route.distanceMeters)} · aproximadamente $etaMinutes min · carro",
                    color = hint,fontSize = 12.sp
                )
            } else {
                Text(
                    if (ui.calculating) "Calculando trajeto..." else "Aguardando rota",
                    color = hint,fontSize = 12.sp
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ui.navigating) {
                    OutlinedButton(onClick = onStop,modifier = Modifier.testTag("stop_navigation_button")) {
                        Icon(Icons.Default.Close,null,modifier = Modifier.size(16.dp))
                        Text("Encerrar",fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onStart,enabled = route != null,
                        colors = ButtonDefaults.buttonColors(containerColor = aqua,contentColor = Color(0xFF06273A)),
                        modifier = Modifier.testTag("start_navigation_button")
                    ) {
                        Icon(Icons.Default.Navigation,null,modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Navegar",fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = onSaveRoute,enabled = route != null,
                        modifier = Modifier.testTag("save_route_button")
                    ) {
                        Icon(Icons.Default.BookmarkAdd,null,modifier = Modifier.size(16.dp))
                        Text("Salvar rota",fontSize = 12.sp)
                    }
                    TextButton(onClick = onStop) { Text("Fechar",color = hint,fontSize = 12.sp) }
                }
            }
        }
    }
}


/**
 * Driving HUD only: one compact maneuver strip, no stacked route panels.
 * All values come from live navigation state; do not show static route ETA.
 */
@Composable
fun ActiveNavigationBanner(
    ui: NavigationUiState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!ui.navigating || ui.route == null) return
    Surface(
        modifier = modifier.testTag("active_navigation_banner"),
        shape = RoundedCornerShape(19.dp),
        color = Color(0xF40B243A),
        shadowElevation = 9.dp,
        border = BorderStroke(1.dp,Color.White.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp,vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = aqua.copy(alpha = 0.13f),
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.size(43.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Navigation,"Próxima manobra",tint = aqua,
                        modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (ui.metersUntilTurn > 0)
                        "Em ${NavigationMath.formatDistance(ui.metersUntilTurn)}"
                    else "Siga a rota",
                    color = aqua,fontSize = 12.sp,fontWeight = FontWeight.Bold
                )
                Text(
                    ui.nextInstruction.ifBlank { "Siga pela via indicada" },
                    color = pale,fontSize = 15.sp,fontWeight = FontWeight.Bold,
                    maxLines = 2,overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onStop,
                modifier = Modifier.size(40.dp).testTag("stop_navigation_button")
            ) {
                Icon(Icons.Default.Close, "Encerrar navegação",tint = hint,
                    modifier = Modifier.size(23.dp))
            }
        }
    }
}

@Composable
fun NavigationStreetStrip(
    street: String,
    neighborhood: String,
    accuracyMeters: Int?,
    voiceEnabled: Boolean,
    onToggleVoice: (Boolean) -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.testTag("navigation_street_strip"),
        shape = RoundedCornerShape(19.dp),
        color = Color(0xEE0B243A),
        border = BorderStroke(1.dp,Color.White.copy(alpha = 0.12f)),
        shadowElevation = 7.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp,vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onToggleVoice(!voiceEnabled) },
                modifier = Modifier.size(39.dp)) {
                Icon(
                    if (voiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    if (voiceEnabled) "Silenciar voz" else "Ativar voz",
                    tint = if (voiceEnabled) aqua else hint
                )
            }
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(street,color = pale,fontSize = 15.sp,fontWeight = FontWeight.Bold,
                    maxLines = 1,overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(neighborhood.takeIf { it.isNotBlank() },
                        accuracyMeters?.let { "GPS ±${it} m" }).joinToString(" · "),
                    color = hint,fontSize = 11.sp,maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRepeat,modifier = Modifier.size(39.dp)) {
                Icon(Icons.Default.Replay,"Repetir nome da rua",tint = aqua)
            }
        }
    }
}

@Composable
fun NavigationLibraryDialog(
    ui: NavigationUiState,
    onDismiss: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectDestination: (MapPlace) -> Unit,
    onSelectSavedRoute: (StoredRoute) -> Unit,
    onSelectTrip: (SavedTrip) -> Unit,
    onRenameFavorite: (Long, String) -> Unit,
    onDeleteFavorite: (Long) -> Unit,
    onDeleteRoute: (Long) -> Unit,
    onDeleteTrip: (Long) -> Unit
) {
    if (ui.panel.isBlank() || ui.panel == "search") return
    var editFavoriteId by remember { mutableStateOf<Long?>(null) }
    var editedName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Pair<String,Long>?>(null) }
    val title = when(ui.panel) {
        "search" -> "Pesquisar destinos"
        "saved" -> "Lugares salvos"
        "routes" -> "Rotas salvas"
        else -> "Percursos gravados"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),color = Color(0xFF11263D),
            modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp),
            border = BorderStroke(1.dp,Color.White.copy(alpha = 0.1f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title,color = pale,fontSize = 19.sp,fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close,"Fechar",tint = pale)
                    }
                }
                if (ui.panel == "search") {
                    OutlinedTextField(
                        value = ui.query,onValueChange = onQueryChanged,
                        label = { Text("Rua, bairro, comércio ou coordenadas") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("dialog_search_field"),
                        textStyle = androidx.compose.ui.text.TextStyle(color = pale),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() })
                    )
                    Button(onClick = onSearch,modifier = Modifier.align(Alignment.End)) {
                        Text("Pesquisar")
                    }
                    if (ui.searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (ui.error != null) {
                    Text(
                        ui.error, color = Color(0xFFFFBFA9), fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                } else if (ui.panel == "search" && ui.searchNotice != null) {
                    Text(
                        ui.searchNotice, color = aqua, fontSize = 12.sp,
                        modifier = Modifier.testTag("search_feedback")
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    when(ui.panel) {
                        "search" -> items(ui.results) { point ->
                            LibraryRow(point.name,point.subtitle,Icons.Default.Place,
                                action = { onSelectDestination(point) })
                        }
                        "saved" -> items(ui.savedPlaces,key = { it.first }) { (id, point) ->
                            LibraryRow(point.name,point.subtitle,Icons.Default.Bookmark,
                                action = { onSelectDestination(point) },
                                onEdit = {
                                    editFavoriteId = id
                                    editedName = point.name
                                },
                                onDelete = { pendingDelete = "saved" to id })
                        }
                        "routes" -> items(ui.savedRoutes,key = { it.id }) { route ->
                            LibraryRow(
                                route.title, "${NavigationMath.formatDistance(route.route.distanceMeters)} · ${date(route.createdAt)}",
                                Icons.Default.Route,
                                action = { onSelectSavedRoute(route) },
                                onDelete = { pendingDelete = "routes" to route.id }
                            )
                        }
                        else -> items(ui.trips,key = { it.id }) { trip ->
                            LibraryRow(
                                "Percurso ${date(trip.startedAt)}",
                                "${NavigationMath.formatDistance(trip.distanceMeters)} · ${trip.points.size} registros",
                                Icons.Default.Timeline,
                                action = { onSelectTrip(trip) },
                                onDelete = { pendingDelete = "history" to trip.id }
                            )
                        }
                    }
                }
                val empty = when(ui.panel) {
                    "saved" -> ui.savedPlaces.isEmpty()
                    "routes" -> ui.savedRoutes.isEmpty()
                    "history" -> ui.trips.isEmpty()
                    else -> !ui.searching && ui.results.isEmpty()
                }
                if (empty) {
                    Text(
                        when(ui.panel) {
                            "saved" -> "Nenhum lugar salvo. Use Salvar aqui quando estiver no local."
                            "routes" -> "Nenhuma rota salva. Selecione um destino e toque em Salvar rota."
                            "history" -> "Nenhum percurso gravado. Use Gravar no mapa para iniciar."
                            else -> if (ui.error != null)
                                "Você também pode segurar um ponto no mapa para escolher o destino."
                                else "Pesquise uma rua, comércio, local salvo ou coordenadas."
                        },color = hint,fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
    }
    if (editFavoriteId != null) {
        val id = editFavoriteId!!
        AlertDialog(
            onDismissRequest = { editFavoriteId = null },
            containerColor = Color(0xFF11263D),
            title = { Text("Renomear lugar",color = pale) },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { if (it.length <= 90) editedName = it },
                    label = { Text("Nome do local") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_saved_place_field")
                )
            },
            confirmButton = { TextButton(
                enabled = editedName.trim().isNotBlank(),
                onClick = {
                    onRenameFavorite(id,editedName.trim())
                    editFavoriteId = null
                }
            ) { Text("Salvar",color = aqua) } },
            dismissButton = { TextButton(onClick = { editFavoriteId = null }) {
                Text("Cancelar",color = hint)
            } }
        )
    }
    if (pendingDelete != null) {
        val (type,id) = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Color(0xFF11263D),
            title = { Text("Confirmar exclusão",color = pale) },
            text = { Text(
                "Remover este item dos dados locais do ViaVox? Esta ação não pode ser desfeita.",
                color = hint
            ) },
            confirmButton = { TextButton(onClick = {
                when (type) {
                    "saved" -> onDeleteFavorite(id)
                    "routes" -> onDeleteRoute(id)
                    "history" -> onDeleteTrip(id)
                }
                pendingDelete = null
            }) { Text("Remover",color = Color(0xFFFFB4A3)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) {
                Text("Cancelar",color = hint)
            } }
        )
    }
}

@Composable
private fun LibraryRow(
    title: String,subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Surface(color = Color(0xFF1C354E),shape = RoundedCornerShape(13.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = action).padding(10.dp)) {
            Icon(icon,null,tint = aqua,modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title,color = pale,fontSize = 13.sp,fontWeight = FontWeight.SemiBold,
                    maxLines = 2,overflow = TextOverflow.Ellipsis)
                if(subtitle.isNotBlank()) Text(subtitle,color = hint,fontSize = 11.sp,
                    maxLines = 2,overflow = TextOverflow.Ellipsis)
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit,modifier = Modifier.size(37.dp)) {
                    Icon(Icons.Default.Edit,"Renomear",tint = aqua)
                }
            }
            if(onDelete != null) {
                IconButton(onClick = onDelete,modifier = Modifier.size(37.dp)) {
                    Icon(Icons.Default.Delete,"Excluir",tint = Color(0xFFFFB4A3))
                }
            }
            Icon(Icons.Default.ChevronRight,null,tint = hint,modifier = Modifier.size(18.dp))
        }
    }
}

private fun date(time: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("pt","BR")).format(Date(time))
