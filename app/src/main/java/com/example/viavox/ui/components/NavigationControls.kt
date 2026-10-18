package com.example.viavox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    onOpenSaved: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenHistory: () -> Unit,
    onSaveCurrentPosition: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 14.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = navy,
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp,Color.White.copy(alpha = 0.16f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp,end = 6.dp)) {
                Icon(Icons.Default.Search,null,tint = aqua,modifier = Modifier.size(24.dp))
                TextField(
                    value = ui.query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.weight(1f).testTag("navigation_search_field"),
                    placeholder = { Text("Pesquisar destino ou endereço",color = hint,fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = pale,fontSize = 14.sp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = aqua
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() })
                )
                IconButton(onClick = onSearch,modifier = Modifier.testTag("run_search_button")) {
                    Icon(Icons.Default.ArrowForward,"Buscar local",tint = aqua)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            NavChip("Buscar",Icons.Default.Search,onOpenSearch)
            NavChip("Salvos",Icons.Default.Bookmark,onOpenSaved)
            NavChip("Rotas",Icons.Default.Route,onOpenRoutes)
            NavChip("Percursos",Icons.Default.History,onOpenHistory)
            NavChip("Salvar aqui",Icons.Default.AddLocation,onSaveCurrentPosition)
            NavChip(
                if (ui.recording) "Parar gravação" else "Gravar",
                if (ui.recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                if (ui.recording) onStopRecording else onStartRecording,
                urgent = ui.recording
            )
        }
        if (ui.recording) {
            Surface(color = Color(0xF0802631),shape = RoundedCornerShape(10.dp)) {
                Text(
                    "● Gravando: ${NavigationMath.formatDistance(ui.recordedMeters)} · ${ui.recordedCount} pontos",
                    modifier = Modifier.padding(horizontal = 11.dp,vertical = 7.dp),
                    color = pale,fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun NavChip(label: String,icon: androidx.compose.ui.graphics.vector.ImageVector,
                    action: () -> Unit,urgent: Boolean = false) {
    Surface(
        color = if (urgent) Color(0xE58E2637) else navy,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp,Color.White.copy(alpha = 0.15f)),
        modifier = Modifier.clickable(onClick = action)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp,vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(icon,null,tint = if (urgent) Color(0xFFFFB5B5) else aqua,modifier = Modifier.size(16.dp))
            Text(label,color = pale,fontWeight = FontWeight.Medium,fontSize = 12.sp,maxLines = 1)
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

@Composable
fun NavigationLibraryDialog(
    ui: NavigationUiState,
    onDismiss: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectDestination: (MapPlace) -> Unit,
    onSelectSavedRoute: (StoredRoute) -> Unit,
    onSelectTrip: (SavedTrip) -> Unit,
    onDeleteFavorite: (Long) -> Unit,
    onDeleteRoute: (Long) -> Unit,
    onDeleteTrip: (Long) -> Unit
) {
    if (ui.panel.isBlank()) return
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
                    Text(ui.error,color = Color(0xFFFFBFA9),fontSize = 12.sp)
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
                                onDelete = { onDeleteFavorite(id) })
                        }
                        "routes" -> items(ui.savedRoutes,key = { it.id }) { route ->
                            LibraryRow(
                                route.title, "${NavigationMath.formatDistance(route.route.distanceMeters)} · ${date(route.createdAt)}",
                                Icons.Default.Route,
                                action = { onSelectSavedRoute(route) },
                                onDelete = { onDeleteRoute(route.id) }
                            )
                        }
                        else -> items(ui.trips,key = { it.id }) { trip ->
                            LibraryRow(
                                "Percurso ${date(trip.startedAt)}",
                                "${NavigationMath.formatDistance(trip.distanceMeters)} · ${trip.points.size} registros",
                                Icons.Default.Timeline,
                                action = { onSelectTrip(trip) },
                                onDelete = { onDeleteTrip(trip.id) }
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
                            else -> "Pesquise um destino para visualizar resultados."
                        },color = hint,fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: () -> Unit,
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
