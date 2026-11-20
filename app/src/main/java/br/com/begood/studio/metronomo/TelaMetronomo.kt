package br.com.begood.studio.metronomo

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.begood.studio.ui.Ambar
import br.com.begood.studio.ui.AmbarClaro
import br.com.begood.studio.ui.AppViewModel
import br.com.begood.studio.ui.Cinza
import br.com.begood.studio.ui.Contador
import br.com.begood.studio.ui.Painel
import br.com.begood.studio.ui.Secao

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelaMetronomo(vm: AppViewModel) {
    val m = vm.metronomo
    @Suppress("UNUSED_VARIABLE") val v = vm.versaoMetro // recompõe quando um ajuste muda
    val bpm by m.bpmAtual.collectAsState()
    val tocando by m.tocando.collectAsState()
    val ctx = LocalContext.current
    val vibrador = remember { vibrador(ctx) }

    // Qual tempo está soando (lido da posição real do áudio, a cada quadro da tela)
    var marca by remember { mutableStateOf<MotorMetronomo.Marca?>(null) }
    val brilho = remember { Animatable(0f) }
    LaunchedEffect(tocando) {
        if (!tocando) { marca = null; return@LaunchedEffect }
        var anterior: MotorMetronomo.Marca? = null
        while (true) {
            withFrameNanos { }
            val atual = m.marcaAtual()
            if (atual != null && atual != anterior) {
                anterior = atual
                marca = atual
                if (atual.sub == 0 && !atual.mudo) {
                    if (vm.piscar) { brilho.snapTo(if (atual.tempo == 0 && m.acentuar) 1f else 0.55f) }
                    if (vm.vibrar) vibrador?.let { vibrar(it, atual.tempo == 0) }
                }
            }
            if (brilho.value > 0f) brilho.snapTo((brilho.value - 0.06f).coerceAtLeast(0f))
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Tempos do compasso
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in 0 until m.tempos) {
                val atual = marca?.tempo == t
                val mudo = marca?.mudo == true
                val tamanho = if (t == 0 && m.acentuar) 30.dp else 22.dp
                Box(
                    Modifier.size(tamanho).clip(CircleShape)
                        .background(
                            when {
                                atual && mudo -> Cinza
                                atual -> Ambar
                                else -> Painel
                            }
                        )
                        .border(1.dp, if (t == 0) Ambar else Cinza, CircleShape)
                )
            }
        }

        // BPM
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Ambar.copy(alpha = 0.10f + 0.35f * brilho.value)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 12.dp)) {
                Text("${bpm.toInt()}", fontSize = 88.sp, fontWeight = FontWeight.Bold, color = Ambar, lineHeight = 88.sp)
                Text("BPM  •  ${nomeAndamento(bpm.toInt())}", color = AmbarClaro)
                if (marca?.mudo == true) Text("(compasso mudo — segure o tempo!)", color = Cinza, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { vm.ajustarMetro { mudarBpm(bpm - 1) } }, modifier = Modifier.fillMaxWidth()) { Text("−1") }
                OutlinedButton(onClick = { vm.ajustarMetro { mudarBpm(bpm - 5) } }, modifier = Modifier.fillMaxWidth()) { Text("−5") }
            }
            LargeFloatingActionButton(
                onClick = { if (tocando) m.parar() else m.iniciar() },
                containerColor = Ambar, contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(if (tocando) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (tocando) "Parar" else "Começar", Modifier.size(40.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { vm.ajustarMetro { mudarBpm(bpm + 1) } }, modifier = Modifier.fillMaxWidth()) { Text("+1") }
                OutlinedButton(onClick = { vm.ajustarMetro { mudarBpm(bpm + 5) } }, modifier = Modifier.fillMaxWidth()) { Text("+5") }
            }
        }
        Slider(
            value = bpm.toFloat(), valueRange = 20f..300f,
            onValueChange = { m.mudarBpm(Math.round(it).toDouble()) },
            onValueChangeFinished = { vm.salvarMetro() },
        )
        FilledTonalButton(onClick = { vm.tapTempo() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("TOQUE NO RITMO", fontWeight = FontWeight.Bold)
        }

        Secao("Compasso") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((num, den) in listOf(2 to 4, 3 to 4, 4 to 4, 5 to 4, 6 to 8, 7 to 8, 9 to 8, 12 to 8)) {
                    FilterChip(
                        selected = m.tempos == num && vm.denominador == den,
                        onClick = { vm.denominador = den; vm.ajustarMetro { tempos = num } },
                        label = { Text("$num/$den") },
                    )
                }
            }
            Contador(
                "${m.tempos}/${vm.denominador}",
                { vm.ajustarMetro { tempos = (tempos - 1).coerceAtLeast(1) } },
                { vm.ajustarMetro { tempos = (tempos + 1).coerceAtMost(16) } },
                rotulo = "Tempos por compasso",
            )
            Text("Subdivisão", style = MaterialTheme.typography.bodyMedium)
            val subs = listOf(1 to "♩", 2 to "♪♪", 3 to "3", 4 to "♬♬")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                subs.forEachIndexed { i, (n, t) ->
                    SegmentedButton(
                        selected = m.subdivisao == n, onClick = { vm.ajustarMetro { subdivisao = n } },
                        shape = SegmentedButtonDefaults.itemShape(i, subs.size),
                    ) { Text(t) }
                }
            }
            Text(
                when (m.subdivisao) { 2 -> "Colcheias"; 3 -> "Tercinas"; 4 -> "Semicolcheias"; else -> "Só os tempos" },
                style = MaterialTheme.typography.bodySmall, color = Cinza,
            )
        }

        Secao("Som e aviso") {
            Opcao("Acentuar o 1º tempo", m.acentuar) { vm.ajustarMetro { acentuar = it } }
            Opcao("Piscar no tempo", vm.piscar) { vm.piscar = it; vm.salvarMetro() }
            Opcao("Vibrar no tempo", vm.vibrar) { vm.vibrar = it; vm.salvarMetro() }
            Text("Volume", style = MaterialTheme.typography.bodyMedium)
            Slider(value = m.volume, onValueChange = { vol -> vm.ajustarMetro { volume = vol } }, valueRange = 0.05f..1f)
        }

        Secao("Treino de velocidade") {
            Opcao("Aumentar o andamento sozinho", m.treino) { vm.ajustarMetro { treino = it } }
            Contador("+${m.treinoPasso}", { vm.ajustarMetro { treinoPasso = (treinoPasso - 1).coerceAtLeast(1) } },
                { vm.ajustarMetro { treinoPasso = (treinoPasso + 1).coerceAtMost(30) } }, rotulo = "BPM a mais")
            Contador("${m.treinoCada}", { vm.ajustarMetro { treinoCada = (treinoCada - 1).coerceAtLeast(1) } },
                { vm.ajustarMetro { treinoCada = (treinoCada + 1).coerceAtMost(64) } }, rotulo = "a cada (compassos)")
            Contador("${m.treinoMax}", { vm.ajustarMetro { treinoMax = (treinoMax - 5).coerceAtLeast(25) } },
                { vm.ajustarMetro { treinoMax = (treinoMax + 5).coerceAtMost(300) } }, rotulo = "até (BPM)")
        }

        Secao("Compassos mudos") {
            Text("Treina o tempo interno: o metrônomo some por alguns compassos e volta.", style = MaterialTheme.typography.bodySmall, color = Cinza)
            Opcao("Ligar", m.mudo) { vm.ajustarMetro { mudo = it } }
            Contador("${m.mudoToca}", { vm.ajustarMetro { mudoToca = (mudoToca - 1).coerceAtLeast(1) } },
                { vm.ajustarMetro { mudoToca = (mudoToca + 1).coerceAtMost(16) } }, rotulo = "Toca (compassos)")
            Contador("${m.mudoSilencia}", { vm.ajustarMetro { mudoSilencia = (mudoSilencia - 1).coerceAtLeast(1) } },
                { vm.ajustarMetro { mudoSilencia = (mudoSilencia + 1).coerceAtMost(16) } }, rotulo = "Silencia (compassos)")
        }
    }
}

@Composable
private fun Opcao(texto: String, ligado: Boolean, mudar: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { mudar(!ligado) }, verticalAlignment = Alignment.CenterVertically) {
        Text(texto, modifier = Modifier.weight(1f))
        Switch(checked = ligado, onCheckedChange = mudar)
    }
}

private fun vibrador(ctx: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

private fun vibrar(v: Vibrator, forte: Boolean) {
    runCatching { v.vibrate(VibrationEffect.createOneShot(if (forte) 45 else 25, if (forte) 255 else 140)) }
}
