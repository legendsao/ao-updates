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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Paleta fija para elegir color con un toque; además se puede escribir cualquier hex a mano. */
private val PALETTE = listOf(
    "#FFFFFF", "#000000", "#6A1B9A", "#1976D2", "#2E7D32",
    "#F9A825", "#D32F2F", "#CE93D8", "#90CAF9", "#A5D6A7", "#FFCC80", "#EF9A9A",
)

class MainActivity : ComponentActivity() {

    private var players by mutableStateOf<List<Player>>(emptyList())
    private var floating by mutableStateOf(false)

    /** Respaldo en un archivo fuera de la app: sobrevive a una desinstalación o a una reinstalación con otra firma. */
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(Store.exportJson(this).toByteArray()) }
        }.onSuccess {
            Toast.makeText(this, "Respaldo guardado", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "No se pudo guardar el respaldo", Toast.LENGTH_SHORT).show()
        }
    }

    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val restored = text?.let { Store.importJson(this, it) }
        if (restored != null) {
            players = restored
            Toast.makeText(this, "Se restauraron ${restored.size} jugadores", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "El archivo no es un respaldo válido", Toast.LENGTH_SHORT).show()
        }
    }

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
            sortPlayers(players.filter { it.points != 0 }).forEachIndexed { i, p ->
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
            val atajos = remember { Store.quick(this@MainActivity).map { it.toString() }.toMutableStateList() }
            var panelOp by remember { mutableStateOf(Store.panelOpacity(this@MainActivity).toFloat()) }
            var btnOp by remember { mutableStateOf(Store.buttonOpacity(this@MainActivity).toFloat()) }
            var scale by remember { mutableStateOf(Store.scalePercent(this@MainActivity).toFloat()) }
            var bubbleColor by remember { mutableStateOf(Store.bubbleColor(this@MainActivity)) }
            var panelColor by remember { mutableStateOf(Store.panelColor(this@MainActivity)) }
            var buttonColor by remember { mutableStateOf(Store.buttonColor(this@MainActivity)) }
            var selectedColor by remember { mutableStateOf(Store.selectedColor(this@MainActivity)) }
            var side by remember { mutableStateOf(Store.bubbleSide(this@MainActivity)) }
            var confirmImport by remember { mutableStateOf(false) }

            fun persist() {
                Store.setQuick(this@MainActivity, atajos.mapNotNull { it.trim().toIntOrNull() }.filter { it != 0 }.joinToString(","))
                Store.setPanelOpacity(this@MainActivity, panelOp.toInt())
                Store.setButtonOpacity(this@MainActivity, btnOp.toInt())
                Store.setScalePercent(this@MainActivity, scale.toInt())
                Store.setColor(this@MainActivity, "colorBubble", bubbleColor)
                Store.setColor(this@MainActivity, "colorPanel", panelColor)
                Store.setColor(this@MainActivity, "colorButton", buttonColor)
                Store.setColor(this@MainActivity, "colorSelected", selectedColor)
                Store.setBubbleSide(this@MainActivity, side)
            }

            AlertDialog(
                onDismissRequest = { persist(); settings = false },
                title = { Text("Ajustes") },
                text = {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Atajos de puntos de la flotante", style = MaterialTheme.typography.titleSmall)
                        atajos.forEachIndexed { i, v ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    v, { atajos[i] = it }, singleLine = true, modifier = Modifier.weight(1f),
                                    label = { Text("Botón ${i + 1}") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                TextButton(onClick = { atajos.removeAt(i) }) { Text("✕") }
                            }
                        }
                        OutlinedButton(onClick = { atajos.add("0") }) { Text("+ Agregar botón") }

                        Text("Apariencia de la flotante", style = MaterialTheme.typography.titleSmall)
                        Text("Opacidad del panel: ${panelOp.toInt()}%")
                        Slider(panelOp, { panelOp = it }, valueRange = 10f..100f)
                        Text("Opacidad de los botones: ${btnOp.toInt()}%")
                        Slider(btnOp, { btnOp = it }, valueRange = 10f..100f)
                        Text("Tamaño: ${scale.toInt()}%")
                        Slider(scale, { scale = it }, valueRange = 60f..200f)

                        ColorPicker("Burbuja", bubbleColor) { bubbleColor = it }
                        ColorPicker("Panel", panelColor) { panelColor = it }
                        ColorPicker("Botones", buttonColor) { buttonColor = it }
                        ColorPicker("Jugador seleccionado", selectedColor) { selectedColor = it }

                        Text("Lado por defecto de la burbuja", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("left" to "Izquierda", "right" to "Derecha").forEach { (value, label) ->
                                if (side == value) Button(onClick = {}) { Text(label) }
                                else OutlinedButton(onClick = { side = value }) { Text(label) }
                            }
                        }
                        OutlinedButton(onClick = {
                            Store.resetBubblePosition(this@MainActivity)
                            Toast.makeText(this@MainActivity, "Se reinicia la próxima vez que abras la flotante", Toast.LENGTH_SHORT).show()
                        }) { Text("Reiniciar posición de la burbuja") }

                        Text("Respaldo", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Un archivo fuera de la app que sobrevive a una desinstalación.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { exportBackup.launch("ranking-backup.json") }) {
                                Text("Exportar")
                            }
                            OutlinedButton(onClick = { confirmImport = true }) { Text("Importar") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { persist(); settings = false }) { Text("Listo") }
                },
            )
            if (confirmImport) {
                AlertDialog(
                    onDismissRequest = { confirmImport = false },
                    title = { Text("Importar respaldo") },
                    text = { Text("Reemplaza todos los jugadores actuales por los del archivo. ¿Seguro?") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmImport = false
                            importBackup.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }) { Text("Elegir archivo") }
                    },
                    dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancelar") } },
                )
            }
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

    /** Fila de color: paleta de toques rápidos + campo para escribir cualquier hex. */
    @Composable
    private fun ColorPicker(label: String, hex: String, onPick: (String) -> Unit) {
        var text by remember(hex) { mutableStateOf(hex) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PALETTE.forEach { swatch ->
                    val selected = swatch.equals(hex, ignoreCase = true)
                    Column(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(runCatching { Color(android.graphics.Color.parseColor(swatch)) }.getOrDefault(Color.Gray))
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                CircleShape,
                            )
                            .clickable { onPick(swatch); text = swatch }
                    ) {}
                }
            }
            OutlinedTextField(
                text,
                {
                    text = it
                    if (Regex("^#[0-9A-Fa-f]{6}$").matches(it)) onPick(it)
                },
                singleLine = true,
                label = { Text("o un hex (#RRGGBB)") },
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
