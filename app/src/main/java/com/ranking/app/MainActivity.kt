package com.ranking.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private var players by mutableStateOf<List<Player>>(emptyList())
    private var floating by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme {
                Surface { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        players = Store.load(this)
        floating = FloatingService.running
    }

    private fun update(list: List<Player>) {
        players = list
        Store.save(this, list)
    }

    private fun toggleFloating() {
        if (FloatingService.running) {
            stopService(Intent(this, FloatingService::class.java))
            floating = false
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Dá el permiso de superposición", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        startForegroundService(Intent(this, FloatingService::class.java))
        floating = true
    }

    private fun generateMessage() {
        val text = buildString {
            append("🏆 Ranking")
            sortPlayers(players).forEachIndexed { i, p ->
                append("\n${i + 1}. @${p.number.ifEmpty { p.name }} — ${p.points} pts")
            }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Ranking", text))
        Toast.makeText(this, "Mensaje copiado", Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun Screen() {
        var editing by remember { mutableStateOf<Player?>(null) }
        var adding by remember { mutableStateOf(false) }
        var confirmReset by remember { mutableStateOf(false) }
        var settings by remember { mutableStateOf(false) }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ranking", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { toggleFloating() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (floating) "Detener ventana flotante" else "Iniciar ventana flotante")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { adding = true }) { Text("Agregar") }
                Button(onClick = { update(sortPlayers(players)) }) { Text("Ordenar de mayor a menor") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { generateMessage() }) { Text("Generar mensaje") }
                OutlinedButton(onClick = { confirmReset = true }) { Text("Reiniciar puntos") }
            }
            OutlinedButton(onClick = { settings = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Ajustes")
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(players, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text("${p.number} · ${p.points} pts")
                            }
                            TextButton(onClick = {
                                update(players.map { if (it.id == p.id) it.copy(points = it.points - 1) else it })
                            }) { Text("-1") }
                            TextButton(onClick = { editing = p }) { Text("Editar") }
                        }
                    }
                }
            }
        }

        if (adding) {
            PlayerDialog(null, onDismiss = { adding = false }, onSave = { name, num, _ ->
                update(players + Player(name = name, number = num))
                adding = false
            }, onDelete = null)
        }
        editing?.let { p ->
            PlayerDialog(p, onDismiss = { editing = null }, onSave = { name, num, pts ->
                update(players.map { if (it.id == p.id) it.copy(name = name, number = num, points = pts) else it })
                editing = null
            }, onDelete = {
                update(players.filter { it.id != p.id })
                editing = null
            })
        }
        if (settings) {
            var quick by remember { mutableStateOf(Store.quickRaw(this@MainActivity)) }
            AlertDialog(
                onDismissRequest = { Store.setQuick(this@MainActivity, quick); settings = false },
                title = { Text("Ajustes") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(quick, { quick = it }, singleLine = true,
                            label = { Text("Atajos de la flotante (ej. -1,1,5,10,15,30,100)") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = { Store.setQuick(this@MainActivity, quick); settings = false }) { Text("Listo") }
                },
            )
        }
        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("Reiniciar puntos") },
                text = { Text("Todos los jugadores quedan en 0. ¿Seguro?") },
                confirmButton = {
                    TextButton(onClick = {
                        update(players.map { it.copy(points = 0) })
                        confirmReset = false
                    }) { Text("Reiniciar") }
                },
                dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } },
            )
        }
    }

    @Composable
    private fun PlayerDialog(
        player: Player?,
        onDismiss: () -> Unit,
        onSave: (String, String, Int) -> Unit,
        onDelete: (() -> Unit)?,
    ) {
        var name by remember { mutableStateOf(player?.name ?: "") }
        var number by remember { mutableStateOf(player?.number ?: "") }
        var points by remember { mutableStateOf((player?.points ?: 0).toString()) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (player == null) "Agregar jugador" else "Editar jugador") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                    OutlinedTextField(
                        number, { number = it }, label = { Text("Número (ej. 549..., opcional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    if (player != null) {
                        OutlinedTextField(
                            points, { points = it }, label = { Text("Puntos") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = cleanNumber(number)
                    if (name.isNotBlank()) {
                        onSave(name.trim(), n, points.toIntOrNull() ?: player?.points ?: 0)
                    }
                }) { Text("Guardar") }
            },
            dismissButton = {
                Row {
                    if (onDelete != null) TextButton(onClick = onDelete) { Text("Quitar") }
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                }
            },
        )
    }
}
