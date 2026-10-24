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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Paleta fija para elegir color con un toque; además se puede escribir cualquier hex a mano. */
private val PALETTE = listOf(
    "#FFFFFF", "#000000", "#6A1B9A", "#1976D2", "#2E7D32",
    "#F9A825", "#D32F2F", "#CE93D8", "#90CAF9", "#A5D6A7", "#FFCC80", "#EF9A9A",
)

private val BrandPurple = Color(0xFF6A1B9A)
private val BrandGold = Color(0xFFF9A825)

private val AppColors = lightColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFD9FF),
    onPrimaryContainer = Color(0xFF3A0A5C),
    secondary = BrandGold,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFFFFE9B0),
    tertiary = Color(0xFF2E7D32),
    background = Color(0xFFFAF7FC),
    surface = Color(0xFFFAF7FC),
    surfaceVariant = Color(0xFFEFE6F5),
    error = Color(0xFFD32F2F),
)

/** Un color de avatar estable por nombre, para distinguir jugadores de un vistazo. */
private val AVATAR_COLORS = listOf(
    Color(0xFF6A1B9A), Color(0xFF1976D2), Color(0xFF2E7D32), Color(0xFFD32F2F),
    Color(0xFFF9A825), Color(0xFF00838F), Color(0xFFAD1457), Color(0xFF4E342E),
)
private fun avatarColor(name: String) = AVATAR_COLORS[(name.hashCode().and(0x7fffffff)) % AVATAR_COLORS.size]

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
            MaterialTheme(colorScheme = AppColors) {
                Surface(color = MaterialTheme.colorScheme.background) { Screen() }
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

    /** Arma el mensaje con el formato y emojis pedidos (negrita/cursiva estilo WhatsApp con * y _). */
    private fun generateMessage() {
        val title = Store.rankingTitle(this)
        val deadline = Store.rankingDeadline(this)
        val prizeT = Store.rankingPrizeTournament(this)
        val prize1 = Store.rankingPrize1st(this)
        val prize2 = Store.rankingPrize2nd(this)
        val qualifiedCount = Store.rankingQualified(this)
        val ranked = sortPlayers(players.filter { it.points != 0 })
        val qualified = ranked.take(qualifiedCount)
        val rest = ranked.drop(qualifiedCount)

        fun line(p: Player) = "@${p.number.ifEmpty { p.name }} (${p.points} pts)"
        fun medal(i: Int) = when (i) { 0 -> "🥇"; 1 -> "🥈"; else -> "🔺" }

        val text = buildString {
            append("🃏⚜️ *RANKING")
            if (title.isNotBlank()) append(" $title")
            append("* ⚜️🃏")
            if (deadline.isNotBlank()) {
                append("\n\n📣 *ACTUALIZACIÓN DE PUNTOS HASTA EL $deadline* 📣")
            }
            append("\n\n_RESPONDER LA MESA CON CAPTURA GANADORA PARA SUMAR PUNTOS_ ✅")
            if (prizeT.isNotBlank() || prize1.isNotBlank() || prize2.isNotBlank()) {
                append("\n")
                if (prizeT.isNotBlank()) append("\n🏆 *GANADOR DEL TORNEO: $prizeT*")
                if (prize1.isNotBlank()) append("\n🥇 *PRIMER PUESTO DEL RANKING: $prize1*")
                if (prize2.isNotBlank()) append("\n🥈 *SEGUNDO LUGAR $prize2*")
            }
            if (qualified.isNotEmpty()) {
                append("\n\n*CLASIFICADOS AL TORNEO* 🏆\n")
                qualified.forEachIndexed { i, p -> append("\n${medal(i)} ${line(p)}") }
            }
            if (rest.isNotEmpty()) {
                append("\n\n🔻 *PARTICIPANTES DEL RANKING* 🔻\n")
                rest.forEach { p -> append("\n♠️ ${line(p)}") }
            }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Ranking", text))
        Toast.makeText(this, "Mensaje copiado", Toast.LENGTH_SHORT).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        var editing by remember { mutableStateOf<Player?>(null) }
        var adding by remember { mutableStateOf(false) }
        var confirmReset by remember { mutableStateOf(false) }
        var settings by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.EmojiEvents, null, tint = MaterialTheme.colorScheme.secondary)
                            Text("Ranking", fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        IconButton(onClick = { settings = true }) {
                            Icon(Icons.Filled.Settings, "Ajustes")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { toggleFloating() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = if (floating) {
                        androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        androidx.compose.material3.ButtonDefaults.buttonColors()
                    },
                ) {
                    Icon(if (floating) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (floating) "Detener ventana flotante" else "Iniciar ventana flotante")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { adding = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PersonAdd, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Agregar")
                    }
                    FilledTonalButton(onClick = { update(sortPlayers(players)) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Sort, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Ordenar")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { generateMessage() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Mensaje")
                    }
                    OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.RestartAlt, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Reiniciar")
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                if (players.isEmpty()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                Icons.Filled.PersonAdd, null, modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            )
                            Text(
                                "Sin jugadores todavía.\nTocá \"Agregar\" para sumar al primero.",
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(players, key = { it.id }) { p -> PlayerRow(p, editing = { editing = p }, update = ::update, players = players) }
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
            var goldColor by remember { mutableStateOf(Store.goldColor(this@MainActivity)) }
            var dangerColor by remember { mutableStateOf(Store.dangerColor(this@MainActivity)) }
            var side by remember { mutableStateOf(Store.bubbleSide(this@MainActivity)) }
            var confirmImport by remember { mutableStateOf(false) }
            var rkTitle by remember { mutableStateOf(Store.rankingTitle(this@MainActivity)) }
            var rkDeadline by remember { mutableStateOf(Store.rankingDeadline(this@MainActivity)) }
            var rkPrizeT by remember { mutableStateOf(Store.rankingPrizeTournament(this@MainActivity)) }
            var rkPrize1 by remember { mutableStateOf(Store.rankingPrize1st(this@MainActivity)) }
            var rkPrize2 by remember { mutableStateOf(Store.rankingPrize2nd(this@MainActivity)) }
            var rkQualified by remember { mutableStateOf(Store.rankingQualified(this@MainActivity).toString()) }

            fun persist() {
                Store.setQuick(this@MainActivity, atajos.mapNotNull { it.trim().toIntOrNull() }.filter { it != 0 }.joinToString(","))
                Store.setPanelOpacity(this@MainActivity, panelOp.toInt())
                Store.setButtonOpacity(this@MainActivity, btnOp.toInt())
                Store.setScalePercent(this@MainActivity, scale.toInt())
                Store.setColor(this@MainActivity, "colorBubble", bubbleColor)
                Store.setColor(this@MainActivity, "colorPanel", panelColor)
                Store.setColor(this@MainActivity, "colorButton", buttonColor)
                Store.setColor(this@MainActivity, "colorSelected", selectedColor)
                Store.setColor(this@MainActivity, "colorGold", goldColor)
                Store.setColor(this@MainActivity, "colorDanger", dangerColor)
                Store.setBubbleSide(this@MainActivity, side)
                Store.setRankingHeader(
                    this@MainActivity, rkTitle, rkDeadline, rkPrizeT, rkPrize1, rkPrize2,
                    rkQualified.toIntOrNull() ?: 16,
                )
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

                        Text("Modo: usa un juego de colores de una vez", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                bubbleColor = "#9C4DCC"; panelColor = "#1A1025"
                                buttonColor = "#2D1B3D"; selectedColor = "#9C4DCC"
                                goldColor = "#F9A825"; dangerColor = "#D32F2F"
                            }) { Text("🌙 Oscuro violeta") }
                            OutlinedButton(onClick = {
                                bubbleColor = "#6A1B9A"; panelColor = "#FFFFFF"
                                buttonColor = "#FFFFFF"; selectedColor = "#CE93D8"
                                goldColor = "#F9A825"; dangerColor = "#D32F2F"
                            }) { Text("☀ Claro") }
                        }

                        ColorPicker(
                            "Burbuja", "El círculo que flota sobre WhatsApp", "🏆",
                            bubbleColor,
                        ) { bubbleColor = it }
                        ColorPicker(
                            "Panel", "El fondo de toda la ventana cuando la abrís", "Panel",
                            panelColor,
                        ) { panelColor = it }
                        ColorPicker(
                            "Botones", "Deshacer, Editar, Restar y los chips de puntos", "Botón",
                            buttonColor,
                        ) { buttonColor = it }
                        ColorPicker(
                            "Jugador seleccionado", "El borde que marca a quién le sumaste", "✓ Nombre",
                            selectedColor,
                        ) { selectedColor = it }
                        ColorPicker(
                            "Dorado", "El botón Sumar y los puntos de cada jugador", "Sumar",
                            goldColor,
                        ) { goldColor = it }
                        ColorPicker(
                            "Rojo", "Cerrar y los montos negativos (ej. -1)", "-1",
                            dangerColor,
                        ) { dangerColor = it }

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

                        Text("Mensaje de ranking", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(rkTitle, { rkTitle = it }, singleLine = true,
                            label = { Text("Nombre del torneo (ej. TRUCO TOTTENHAM)") })
                        OutlinedTextField(rkDeadline, { rkDeadline = it }, singleLine = true,
                            label = { Text("Fecha y hora límite (ej. 16/9 20.20 HS)") })
                        OutlinedTextField(rkPrizeT, { rkPrizeT = it }, singleLine = true,
                            label = { Text("Premio ganador del torneo (ej. \$50.000)") })
                        OutlinedTextField(rkPrize1, { rkPrize1 = it }, singleLine = true,
                            label = { Text("Premio 1° puesto del ranking") })
                        OutlinedTextField(rkPrize2, { rkPrize2 = it }, singleLine = true,
                            label = { Text("Premio 2° puesto") })
                        OutlinedTextField(
                            rkQualified, { rkQualified = it }, singleLine = true,
                            label = { Text("Cantidad de clasificados (top N)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Text(
                            "Dejá un campo vacío para que esa línea no aparezca en el mensaje.",
                            style = MaterialTheme.typography.bodySmall,
                        )

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

    /** Tarjeta de un jugador: avatar con inicial, nombre, puntos como chip y acciones rápidas. */
    @Composable
    private fun PlayerRow(p: Player, editing: () -> Unit, update: (List<Player>) -> Unit, players: List<Player>) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                Modifier.padding(10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(avatarColor(p.name)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        p.name.trim().take(1).uppercase().ifEmpty { "?" },
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (p.number.isNotBlank()) {
                        Text(
                            p.number, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("${p.points} pts", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                IconButton(onClick = {
                    update(players.map { if (it.id == p.id) it.copy(points = it.points - 1) else it })
                }) { Icon(Icons.Filled.RemoveCircleOutline, "-1", tint = MaterialTheme.colorScheme.error) }
                IconButton(onClick = editing) { Icon(Icons.Filled.Edit, "Editar") }
            }
        }
    }

    /** Fila de color: paleta de toques rápidos, muestra en vivo de dónde se usa, y campo para
     * escribir cualquier hex. La muestra existe para que se vea QUÉ cambia antes de tocar nada. */
    @Composable
    private fun ColorPicker(label: String, description: String, sampleText: String, hex: String, onPick: (String) -> Unit) {
        var text by remember(hex) { mutableStateOf(hex) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                val sampleColor = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
                val luminance = 0.299f * sampleColor.red + 0.587f * sampleColor.green + 0.114f * sampleColor.blue
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(sampleColor).padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(sampleText, color = if (luminance > 0.6f) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
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
