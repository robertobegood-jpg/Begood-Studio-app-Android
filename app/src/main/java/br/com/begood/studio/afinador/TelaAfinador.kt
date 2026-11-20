package br.com.begood.studio.afinador

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.begood.studio.ui.Ambar
import br.com.begood.studio.ui.AmbarClaro
import br.com.begood.studio.ui.AppViewModel
import br.com.begood.studio.ui.Cinza
import br.com.begood.studio.ui.Contador
import br.com.begood.studio.ui.Painel
import br.com.begood.studio.ui.Verde
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Laranja = Color(0xFFE5643A)

private fun corDosCents(c: Double?): Color = when {
    c == null -> Cinza
    abs(c) <= 2 -> Verde
    abs(c) <= 10 -> Ambar
    else -> Laranja
}

@Composable
fun TelaAfinador(vm: AppViewModel) {
    val ctx = LocalContext.current
    var temPermissao by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { temPermissao = it }
    LaunchedEffect(Unit) { if (!temPermissao) pedir.launch(Manifest.permission.RECORD_AUDIO) }

    // Microfone ligado só enquanto esta aba está aberta e o app está na frente
    val dono = LocalLifecycleOwner.current
    val permissaoAtual by rememberUpdatedState(temPermissao)
    DisposableEffect(dono, temPermissao) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> if (permissaoAtual) vm.afinador.iniciar()
                Lifecycle.Event.ON_PAUSE -> vm.afinador.parar()
                else -> {}
            }
        }
        dono.lifecycle.addObserver(obs)
        if (temPermissao && dono.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.afinador.iniciar()
        onDispose { dono.lifecycle.removeObserver(obs); vm.afinador.parar() }
    }

    val leitura by vm.afinador.leitura.collectAsState()
    val falhou by vm.afinador.falhou.collectAsState()
    val afinacao = AFINACOES[vm.afinacao]
    val freq = leitura.freq

    // Nota alvo: a nota mais próxima (cromático), a corda escolhida ou a corda mais próxima
    val alvo: Int? = freq?.let { f ->
        when {
            afinacao.cromatica -> Notas.maisProxima(f, vm.la4)
            vm.cordaFixa != null -> vm.cordaFixa
            else -> afinacao.notas.minBy { abs(Notas.midiReal(f, vm.la4) - it) }
        }
    }
    val cents = if (freq != null && alvo != null) Notas.cents(freq, alvo, vm.la4) else null
    var ultimaNota by remember { mutableStateOf<Int?>(null) }
    if (alvo != null) ultimaNota = alvo

    var configurando by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            EscolhaAfinacao(vm, Modifier.weight(1f))
            IconButton(onClick = { configurando = true }) { Icon(Icons.Filled.Settings, "Ajustes do afinador", tint = Ambar) }
        }

        if (!temPermissao || falhou) {
            Surface(color = Painel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.MicOff, null, tint = Laranja, modifier = Modifier.size(36.dp))
                    Text(
                        if (!temPermissao) "O afinador precisa ouvir o instrumento pelo microfone."
                        else "Não deu para abrir o microfone. Feche outros apps que estejam gravando e volte aqui.",
                    )
                    if (!temPermissao) Button(onClick = { pedir.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Permitir microfone") }
                }
            }
        }

        // Nota grande
        val notaMostrada = alvo ?: ultimaNota
        val corNota = if (freq == null) Cinza else corDosCents(cents)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                notaMostrada?.let { Notas.nome(it, vm.solfejo) } ?: "–",
                fontSize = 96.sp, fontWeight = FontWeight.Bold, color = corNota, lineHeight = 96.sp,
            )
            if (notaMostrada != null) {
                Text("${Notas.oitava(notaMostrada)}", fontSize = 32.sp, color = corNota.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 14.dp, start = 4.dp))
            }
        }
        Text(
            when {
                cents == null -> if (temPermissao) "Toque uma nota" else ""
                abs(cents) <= 1.5 -> "Afinado ✓"
                cents > 0 -> "%+.1f cents  (alto: afrouxe)".format(cents)
                else -> "%+.1f cents  (baixo: aperte)".format(cents)
            },
            style = MaterialTheme.typography.titleLarge,
            color = corDosCents(cents),
        )

        if (vm.estrobo) Estrobo(cents) else Mostrador(cents)

        Text(
            if (freq != null && alvo != null) "%.2f Hz   •   alvo %.2f Hz".format(freq, Notas.freq(alvo, vm.la4)) else " ",
            style = MaterialTheme.typography.bodyMedium, color = Cinza,
        )

        // Cordas da afinação escolhida (toque para fixar uma)
        if (!afinacao.cromatica) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (n in afinacao.notas) {
                    val ativa = n == alvo
                    val fixa = vm.cordaFixa == n
                    Surface(
                        color = when { fixa -> Ambar; ativa -> MaterialTheme.colorScheme.secondaryContainer; else -> Painel },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(52.dp).clickable { vm.cordaFixa = if (fixa) null else n },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                Notas.nomeCompleto(n, vm.solfejo),
                                color = if (fixa) MaterialTheme.colorScheme.onPrimary else if (ativa) AmbarClaro else Color.White,
                                fontWeight = if (ativa || fixa) FontWeight.Bold else FontWeight.Normal,
                                fontSize = if (vm.solfejo) 13.sp else 15.sp,
                            )
                        }
                    }
                }
            }
            Text(
                if (vm.cordaFixa != null) "Corda fixa — toque nela de novo para voltar ao automático" else "Toque numa corda para fixá-la",
                style = MaterialTheme.typography.bodySmall, color = Cinza,
            )
        }

        Nivel(leitura.nivelDb, vm.afinador.nivelMinimoDb)

        SingleChoiceSegmentedButtonRow {
            SegmentedButton(selected = !vm.estrobo, onClick = { vm.mudarEstrobo(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Ponteiro") }
            SegmentedButton(selected = vm.estrobo, onClick = { vm.mudarEstrobo(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Estrobo") }
        }
        Text("Lá = %s Hz".format(formatarHz(vm.la4)), style = MaterialTheme.typography.bodyMedium, color = AmbarClaro)
    }

    if (configurando) AjustesAfinador(vm) { configurando = false }
}

private fun formatarHz(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(v) else "%.1f".format(v)

@Composable
private fun EscolhaAfinacao(vm: AppViewModel, modifier: Modifier) {
    var aberto by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { aberto = true }, modifier = Modifier.fillMaxWidth()) {
            Text(AFINACOES[vm.afinacao].nome, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = aberto, onDismissRequest = { aberto = false }) {
            AFINACOES.forEachIndexed { i, a ->
                DropdownMenuItem(
                    text = { Text(a.nome, color = if (i == vm.afinacao) Ambar else Color.Unspecified) },
                    onClick = { vm.mudarAfinacao(i); aberto = false },
                )
            }
        }
    }
}

/** Ponteiro de -50 a +50 cents. */
@Composable
private fun Mostrador(cents: Double?) {
    val alvo = (cents ?: 0.0).coerceIn(-50.0, 50.0).toFloat()
    val animado by animateFloatAsState(alvo, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "ponteiro")
    val cor = corDosCents(cents)
    Canvas(Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(2f)) {
        val centro = Offset(size.width / 2, size.height * 0.95f)
        val raio = minOf(size.width / 2, size.height) * 0.9f
        val abertura = 60.0 // graus para cada lado
        fun ponto(c: Float, r: Float): Offset {
            val ang = Math.toRadians(-90 + c / 50.0 * abertura)
            return Offset(centro.x + (cos(ang) * r).toFloat(), centro.y + (sin(ang) * r).toFloat())
        }
        // Faixa verde de ±2 cents
        drawArc(
            Verde.copy(alpha = 0.35f), startAngle = (-90 - 2 / 50.0 * abertura).toFloat(), sweepAngle = (4 / 50.0 * abertura).toFloat(),
            useCenter = true, topLeft = Offset(centro.x - raio, centro.y - raio), size = Size(raio * 2, raio * 2),
        )
        for (c in -50..50) {
            val longo = c % 10 == 0
            val medio = c % 5 == 0
            if (!medio && abs(c) > 10) continue
            val tam = if (longo) 0.14f else if (medio) 0.09f else 0.05f
            drawLine(
                if (c == 0) Verde else if (longo) AmbarClaro else Cinza,
                ponto(c.toFloat(), raio), ponto(c.toFloat(), raio * (1 - tam)),
                strokeWidth = if (longo) 3.dp.toPx() else 1.5.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        drawLine(cor, centro, ponto(animado, raio * 0.92f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(cor, radius = 8.dp.toPx(), center = centro)
    }
    Row(Modifier.fillMaxWidth().widthIn(max = 420.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("−50", color = Cinza, style = MaterialTheme.typography.bodySmall)
        Text("0", color = Verde, style = MaterialTheme.typography.bodySmall)
        Text("+50", color = Cinza, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Afinador estroboscópico: as faixas andam para a direita quando está alto e para a esquerda
 * quando está baixo, mais rápido quanto mais longe. Paradas = afinado (mostra décimos de cent).
 */
@Composable
private fun Estrobo(cents: Double?) {
    val c by rememberUpdatedState(cents)
    var fase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var antes = 0L
        while (true) {
            withFrameNanos { agora ->
                if (antes != 0L) {
                    val dt = (agora - antes) / 1e9f
                    fase += ((c ?: 0.0).coerceIn(-50.0, 50.0) * 0.6 * dt).toFloat() // 0,6 faixa/s por cent
                }
                antes = agora
            }
        }
    }
    val cor = corDosCents(cents)
    Column(Modifier.fillMaxWidth().widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((linha, mult) in listOf(1f, 2f, 4f).withIndex()) {
            Canvas(Modifier.fillMaxWidth().height(if (linha == 0) 46.dp else 30.dp).clip(RoundedCornerShape(6.dp)).background(Painel)) {
                val largura = size.width / (8 * mult)
                val desloc = ((fase * mult) % 2f + 2f) % 2f * largura
                var x = -2 * largura + desloc
                while (x < size.width) {
                    drawRect(if (cents == null) Cinza.copy(alpha = 0.3f) else cor, topLeft = Offset(x, 0f), size = Size(largura, size.height))
                    x += 2 * largura
                }
            }
        }
    }
}

/** Barrinha do volume que chega no microfone, com a marca do filtro de ruído. */
@Composable
private fun Nivel(db: Double, minimo: Double) {
    val frac = ((db + 80) / 80).coerceIn(0.0, 1.0).toFloat()
    val marca = ((minimo + 80) / 80).coerceIn(0.0, 1.0).toFloat()
    val anim by animateFloatAsState(frac, label = "nivel")
    Column(Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
        Canvas(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Painel)) {
            drawRect(if (frac >= marca) Ambar else Cinza, size = Size(size.width * anim, size.height))
            drawLine(Color.White.copy(alpha = 0.6f), Offset(size.width * marca, 0f), Offset(size.width * marca, size.height), strokeWidth = 2f)
        }
        Spacer(Modifier.height(2.dp))
        Text("Volume no microfone", style = MaterialTheme.typography.labelSmall, color = Cinza)
    }
}

@Composable
private fun AjustesAfinador(vm: AppViewModel, fechar: () -> Unit) {
    AlertDialog(
        onDismissRequest = fechar,
        confirmButton = { TextButton(onClick = fechar) { Text("Fechar") } },
        title = { Text("Ajustes do afinador") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Contador(
                    "${formatarHz(vm.la4)} Hz", { vm.mudarLa4(vm.la4 - 1) }, { vm.mudarLa4(vm.la4 + 1) },
                    rotulo = "Lá de referência",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in listOf(432.0, 440.0, 442.0)) {
                        OutlinedButton(onClick = { vm.mudarLa4(v) }) { Text(formatarHz(v)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Nomes Dó, Ré, Mi (em vez de C, D, E)", modifier = Modifier.weight(1f))
                    Switch(checked = vm.solfejo, onCheckedChange = { vm.mudarSolfejo(it) })
                }
                Text("Filtro de barulho", style = MaterialTheme.typography.titleSmall, color = Ambar)
                listOf("Sensível (lugar silencioso)", "Normal", "Forte (lugar barulhento)").forEachIndexed { i, t ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = vm.filtroRuido == i, onClick = { vm.mudarFiltro(i) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = vm.filtroRuido == i, onClick = { vm.mudarFiltro(i) })
                        Text(t)
                    }
                }
            }
        },
    )
}
