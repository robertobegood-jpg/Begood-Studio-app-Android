package br.com.begood.studio.player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.begood.studio.ui.Ambar
import br.com.begood.studio.ui.AmbarClaro
import br.com.begood.studio.ui.AppViewModel
import br.com.begood.studio.ui.Cinza
import br.com.begood.studio.ui.Contador
import br.com.begood.studio.ui.Painel
import br.com.begood.studio.ui.Secao
import br.com.begood.studio.ui.formatarTempo
import kotlin.math.roundToInt

@Composable
fun TelaPlayer(vm: AppViewModel) {
    val p = vm.player
    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) { p.abrir(u); vm.mostrarBiblioteca = false }
    }
    val abrirArquivo = { escolher.launch(arrayOf("audio/*")) }
    val escolherPasta = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) { p.abrirMultipista(u); vm.mostrarBiblioteca = false }
    }
    val abrirPasta = { escolherPasta.launch(null) }

    p.avisoMultipista?.let { msg ->
        AlertDialog(
            onDismissRequest = { p.avisoMultipista = null },
            title = { Text("Faixas separadas") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { p.avisoMultipista = null }) { Text("OK") } },
        )
    }

    p.montando?.let { f ->
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Preparando as faixas…", style = MaterialTheme.typography.titleMedium, color = Ambar)
            LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
            Text("${(f * 100).roundToInt()}%", color = AmbarClaro)
            Text(
                "Só na primeira vez: as faixas são juntadas para tocarem exatamente juntas. Depois abre na hora.",
                style = MaterialTheme.typography.bodySmall, color = Cinza,
            )
        }
        return
    }

    if (p.uri == null || vm.mostrarBiblioteca) {
        TelaBiblioteca(vm, abrirArquivo, abrirPasta, fechar = if (p.uri == null) null else ({ vm.mostrarBiblioteca = false }))
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (p.faixas != null) Icons.Filled.Tune else Icons.Filled.MusicNote, null, tint = Ambar)
            Text(
                p.nome, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconButton(onClick = { vm.mostrarBiblioteca = true }) { Icon(Icons.Filled.LibraryMusic, "Biblioteca: abrir outra música", tint = Ambar) }
        }
        p.erro?.let { Text(it, color = Color(0xFFE5643A), style = MaterialTheme.typography.bodySmall) }

        Onda(p)
        Row {
            Text(formatarTempo(p.posicao, decimos = true), color = AmbarClaro, modifier = Modifier.weight(1f))
            Text(
                if (p.calculandoOnda) "desenhando a onda…" else "toque: ir • arraste: marcar A–B",
                style = MaterialTheme.typography.labelSmall, color = Cinza,
            )
            Text(formatarTempo(p.duracao), color = Cinza, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }

        // Controles
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { p.voltar() }) { Icon(Icons.Filled.SkipPrevious, "Voltar ao A / início", Modifier.size(32.dp)) }
            IconButton(onClick = { p.pular(-5000) }) { Icon(Icons.Filled.Replay5, "Voltar 5 segundos", Modifier.size(32.dp)) }
            FloatingActionButton(onClick = { p.tocarPausar() }, containerColor = Ambar, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Icon(if (p.tocando || p.esperando) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Tocar/pausar", Modifier.size(36.dp))
            }
            IconButton(onClick = { p.pular(5000) }) { Icon(Icons.Filled.Forward5, "Avançar 5 segundos", Modifier.size(32.dp)) }
            IconButton(onClick = { p.alternarRepetir() }, enabled = p.pontoB != null) {
                Icon(Icons.Filled.Repeat, "Repetir A–B", Modifier.size(32.dp), tint = if (p.repetir) Ambar else Cinza)
            }
        }
        // Espaço sempre reservado: a tela não "pula" quando o aviso aparece
        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
            if (p.esperando) Text("Recomeça em ${p.faltaEspera}…", color = AmbarClaro, style = MaterialTheme.typography.titleMedium)
            else if (p.repetir && p.pontoB != null) Text("Repetindo o trecho A–B", color = Cinza, style = MaterialTheme.typography.bodyMedium)
        }

        p.faixas?.let { SecaoInstrumentos(p, it) }
        SecaoAB(vm)
        SecaoVelocidade(vm)
        SecaoTom(vm)
    }
}

/** Forma de onda: toque para ir até o ponto; arraste para marcar o trecho A–B. */
@Composable
private fun Onda(p: ControlePlayer) {
    val duracao by rememberUpdatedState(p.duracao)
    var arrasteInicio by remember { mutableStateOf<Float?>(null) }
    var arrasteFim by remember { mutableStateOf<Float?>(null) }
    val picos = p.onda
    Canvas(
        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)).background(Painel)
            .pointerInput(Unit) {
                detectTapGestures { o -> if (duracao > 0) p.irPara((o.x / size.width * duracao).toLong()) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> arrasteInicio = o.x; arrasteFim = o.x },
                    onHorizontalDrag = { ch, _ -> arrasteFim = ch.position.x.coerceIn(0f, size.width.toFloat()) },
                    onDragEnd = {
                        val a = arrasteInicio; val b = arrasteFim
                        if (a != null && b != null && duracao > 0) {
                            val ini = minOf(a, b) / size.width * duracao
                            val fim = maxOf(a, b) / size.width * duracao
                            p.definirTrecho(ini.toLong(), fim.toLong())
                        }
                        arrasteInicio = null; arrasteFim = null
                    },
                    onDragCancel = { arrasteInicio = null; arrasteFim = null },
                )
            }
    ) {
        val w = size.width; val h = size.height
        fun x(ms: Long) = if (duracao > 0) ms.toFloat() / duracao * w else 0f
        // Trecho A–B
        val a = p.pontoA; val b = p.pontoB
        if (a != null && b != null) {
            drawRect(Ambar.copy(alpha = if (p.repetir) 0.22f else 0.10f), topLeft = Offset(x(a), 0f), size = Size(x(b) - x(a), h))
        }
        // Onda
        val tocado = x(p.posicao)
        if (picos != null) {
            val barras = (w / 3.dp.toPx()).toInt().coerceAtLeast(1)
            val larg = w / barras
            for (i in 0 until barras) {
                val ini = i * picos.size / barras
                val fim = ((i + 1) * picos.size / barras).coerceAtLeast(ini + 1)
                var v = 0f
                for (k in ini until fim.coerceAtMost(picos.size)) if (picos[k] > v) v = picos[k]
                val alt = (v * h * 0.9f).coerceAtLeast(2f)
                val xx = i * larg
                drawRect(
                    if (xx < tocado) Ambar else Cinza.copy(alpha = 0.7f),
                    topLeft = Offset(xx + larg * 0.15f, (h - alt) / 2), size = Size(larg * 0.7f, alt),
                )
            }
        } else {
            drawLine(Cinza, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 2f)
        }
        // Marcas A e B
        if (a != null) drawLine(AmbarClaro, Offset(x(a), 0f), Offset(x(a), h), strokeWidth = 2.dp.toPx())
        if (b != null) drawLine(AmbarClaro, Offset(x(b), 0f), Offset(x(b), h), strokeWidth = 2.dp.toPx())
        // Seleção sendo arrastada
        val ai = arrasteInicio; val af = arrasteFim
        if (ai != null && af != null) {
            drawRect(Color.White.copy(alpha = 0.18f), topLeft = Offset(minOf(ai, af), 0f), size = Size(kotlin.math.abs(af - ai), h))
        }
        // Agulha da posição
        drawLine(Color.White, Offset(tocado, 0f), Offset(tocado, h), strokeWidth = 2.dp.toPx())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecaoAB(vm: AppViewModel) {
    val p = vm.player
    var salvando by remember { mutableStateOf(false) }
    Secao("Repetir trecho (A/B)") {
        PontoAB("A", p.pontoA, marcar = { p.marcarA() }, ajustar = { p.ajustarA(it) })
        PontoAB("B", p.pontoB, marcar = { p.marcarB() }, ajustar = { p.ajustarB(it) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Repetir", modifier = Modifier.weight(1f))
            if (p.repetir) Text("voltas: ${p.repeticoes}   ", color = AmbarClaro)
            Switch(checked = p.repetir, onCheckedChange = { p.alternarRepetir() }, enabled = p.pontoB != null)
        }
        Text("Pausa antes de repetir", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in listOf(0, 1, 2, 3, 5)) {
                FilterChip(
                    selected = p.pausaEntre == s, onClick = { p.pausaEntre = s; p.salvarConfigAB() },
                    label = { Text(if (s == 0) "Sem pausa" else "$s s") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Acelerar a cada volta", modifier = Modifier.weight(1f))
            Switch(checked = p.acelerar, onCheckedChange = { p.acelerar = it })
        }
        if (p.acelerar) {
            Contador("+${p.passoAcelerar}%", { p.passoAcelerar = (p.passoAcelerar - 1).coerceAtLeast(1); p.salvarConfigAB() },
                { p.passoAcelerar = (p.passoAcelerar + 1).coerceAtMost(25); p.salvarConfigAB() }, rotulo = "por volta")
            Contador("${p.alvoAcelerar}%", { p.alvoAcelerar = (p.alvoAcelerar - 5).coerceAtLeast(30); p.salvarConfigAB() },
                { p.alvoAcelerar = (p.alvoAcelerar + 5).coerceAtMost(200); p.salvarConfigAB() }, rotulo = "até")
            Text("Dica: comece devagar (ex.: 60%) e deixe o app chegar sozinho na velocidade normal.", style = MaterialTheme.typography.bodySmall, color = Cinza)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { salvando = true }, enabled = p.pontoB != null, modifier = Modifier.weight(1f)) { Text("Salvar trecho") }
            OutlinedButton(onClick = { p.limparAB() }, enabled = p.pontoA != null, modifier = Modifier.weight(1f)) { Text("Limpar A/B") }
        }
        if (p.trechos.isNotEmpty()) {
            Text("Trechos salvos desta música", color = Ambar, style = MaterialTheme.typography.bodyMedium)
            for (t in p.trechos.toList()) {
                val atual = p.pontoA == t.a && p.pontoB == t.b
                Surface(
                    color = if (atual) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable { p.usarTrecho(t) },
                ) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.nome, fontWeight = FontWeight.Medium)
                            Text("${formatarTempo(t.a, true)} – ${formatarTempo(t.b, true)}", style = MaterialTheme.typography.bodySmall, color = Cinza)
                        }
                        IconButton(onClick = { p.apagarTrecho(t) }) { Icon(Icons.Filled.Delete, "Apagar trecho", tint = Cinza) }
                    }
                }
            }
        }
    }
    if (salvando) {
        var nome by remember { mutableStateOf("Solo ${p.trechos.size + 1}") }
        AlertDialog(
            onDismissRequest = { salvando = false },
            title = { Text("Salvar trecho") },
            text = { OutlinedTextField(value = nome, onValueChange = { nome = it }, singleLine = true, label = { Text("Nome") }) },
            confirmButton = { TextButton(onClick = { p.salvarTrecho(nome); salvando = false }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { salvando = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun PontoAB(rotulo: String, ms: Long?, marcar: () -> Unit, ajustar: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = marcar, modifier = Modifier.width(64.dp)) { Text(rotulo, fontWeight = FontWeight.Bold) }
        Text(ms?.let { formatarTempo(it, true) } ?: "—", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { ajustar(-100) }, enabled = ms != null) { Text("−0,1") }
        OutlinedButton(onClick = { ajustar(100) }, enabled = ms != null) { Text("+0,1") }
    }
}

/** Modo multipista: volume de cada instrumento, tirar um (para tocar por cima) ou ouvir só um. */
@Composable
private fun SecaoInstrumentos(p: ControlePlayer, faixas: List<FaixaMix>) {
    Secao("Instrumentos") {
        val temSolo = faixas.any { it.solo }
        for (f in faixas) {
            val ouvindo = if (temSolo) f.solo else !f.mudo
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    f.nome, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(76.dp),
                    color = if (ouvindo) Color.Unspecified else Cinza, fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = f.volume, onValueChange = { p.mudarVolume(f, Math.round(it * 20) / 20f) },
                    valueRange = 0f..1.5f, modifier = Modifier.weight(1f), enabled = ouvindo,
                )
                BotaoFaixa("Tirar", f.mudo, "Tirar ${f.nome}") { p.alternarMudo(f) }
                BotaoFaixa("Só", f.solo, "Ouvir só ${f.nome}") { p.alternarSolo(f) }
            }
        }
        Text(
            "Tirar: some com o instrumento para você tocar a parte dele. Só: ouve apenas ele (dá para marcar vários).",
            style = MaterialTheme.typography.bodySmall, color = Cinza,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextButton(
                onClick = { p.mixNormal() },
                enabled = faixas.any { it.mudo || it.solo || it.volume != 1f },
            ) { Text("Voltar todos ao normal") }
        }
    }
}

@Composable
private fun BotaoFaixa(texto: String, ligado: Boolean, descricao: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (ligado) Ambar else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (ligado) MaterialTheme.colorScheme.onPrimary else Color.Unspecified,
        modifier = Modifier.height(32.dp).width(46.dp).semantics { contentDescription = descricao },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(texto, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecaoVelocidade(vm: AppViewModel) {
    val p = vm.player
    Secao("Velocidade (sem mudar o tom)") {
        Contador("${(p.velocidade * 100).roundToInt()}%", { p.mudarVelocidade(p.velocidade - 0.05f) }, { p.mudarVelocidade(p.velocidade + 0.05f) })
        Slider(value = p.velocidade, onValueChange = { p.mudarVelocidade(Math.round(it * 20) / 20f) }, valueRange = 0.25f..2f)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in listOf(50, 60, 75, 90, 100)) {
                FilterChip(selected = (p.velocidade * 100).roundToInt() == v, onClick = { p.mudarVelocidade(v / 100f) }, label = { Text("$v%") })
            }
        }
    }
}

@Composable
private fun SecaoTom(vm: AppViewModel) {
    val p = vm.player
    Secao("Tom (sem mudar a velocidade)") {
        val nomeTom = when (p.semitons) {
            0 -> "original"
            else -> "%+d %s".format(p.semitons, if (kotlin.math.abs(p.semitons) > 1) "semitons" else "semitom")
        }
        Contador(nomeTom, { p.mudarSemitons(p.semitons - 1) }, { p.mudarSemitons(p.semitons + 1) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ajuste fino", modifier = Modifier.weight(1f))
            Text("%+d cents".format(p.cents), color = AmbarClaro)
        }
        Slider(value = p.cents.toFloat(), onValueChange = { p.mudarCents(it.roundToInt()) }, valueRange = -50f..50f)
        Text(
            "O ajuste fino serve para gravações que não estão em Lá 440 Hz: assim a música fica na mesma afinação do seu instrumento.",
            style = MaterialTheme.typography.bodySmall, color = Cinza,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = { p.zerarTom() }, enabled = p.semitons != 0 || p.cents != 0) { Text("Voltar ao tom original") }
        }
    }
}
