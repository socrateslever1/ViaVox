package com.example.viavox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viavox.model.MapPlace

/**
 * Save/edit/remove is a real SQLite action, never an instantly faked toast.
 * This dialog is intentionally independent of the map's search and route UI.
 */
@Composable
fun SavedPlaceEditorDialog(
    place: MapPlace?,
    existingId: Long?,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    if (place == null) return
    var title by remember(existingId,place.latitude,place.longitude) {
        mutableStateOf(place.name)
    }
    var confirmRemoval by remember(existingId) { mutableStateOf(false) }
    val surface = Color(0xFF10273D)
    val ink = Color(0xFFF3F8FF)
    val teal = Color(0xFF4CDFC7)
    AlertDialog(
        modifier = Modifier.testTag("save_place_dialog"),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = surface,
        title = {
            Row {
                Icon(Icons.Default.Bookmark,null,tint = teal)
                Spacer(Modifier.width(8.dp))
                Text(if (existingId == null) "Salvar este lugar" else "Lugar já salvo",
                    color = ink,fontSize = 20.sp,fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "O ponto ficará guardado neste celular e aparecerá em Salvos.",
                    color = Color(0xFFBED0DF),fontSize = 12.sp
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 90) title = it },
                    label = { Text("Nome do lugar") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("saved_place_title")
                )
                Text(
                    place.subtitle.ifBlank { "Localização GPS" },
                    color = Color(0xFFBED0DF),fontSize = 12.sp
                )
                Text(
                    String.format(java.util.Locale.US,"%.5f, %.5f",
                        place.latitude,place.longitude),
                    color = Color(0xFF829DB1),fontSize = 11.sp
                )
                if (existingId != null) {
                    TextButton(
                        onClick = { confirmRemoval = true },
                        modifier = Modifier.testTag("remove_saved_place")
                    ) {
                        Icon(Icons.Default.Delete,null,tint = Color(0xFFFFB3A1))
                        Spacer(Modifier.width(5.dp))
                        Text("Remover dos salvos",color = Color(0xFFFFB3A1))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.trim()) },
                enabled = title.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = teal, contentColor = Color(0xFF082B38)
                ),
                modifier = Modifier.testTag("confirm_save_place")
            ) { Text(if (existingId == null) "Salvar" else "Atualizar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) {
            Text("Cancelar",color = Color(0xFFBED0DF))
        } }
    )
    if (confirmRemoval) AlertDialog(
        onDismissRequest = { confirmRemoval = false },
        containerColor = surface,
        title = { Text("Remover este lugar?",color = ink) },
        text = { Text("O local será excluído apenas do ViaVox deste celular.",
            color = Color(0xFFBED0DF)) },
        confirmButton = { TextButton(onClick = {
            confirmRemoval = false
            onRemove()
        }) { Text("Remover",color = Color(0xFFFFB3A1)) } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) {
            Text("Cancelar",color = Color(0xFFBED0DF))
        } }
    )
}
