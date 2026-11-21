package br.com.begood.studio.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import br.com.begood.studio.afinador.AFINACOES
import br.com.begood.studio.afinador.MotorAfinador
import br.com.begood.studio.metronomo.MotorMetronomo
import br.com.begood.studio.player.ControlePlayer

/** Guarda as três ferramentas: continuam vivas ao trocar de aba (o metrônomo e a música seguem tocando). */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = app.getSharedPreferences("studio", Context.MODE_PRIVATE)

    var aba by mutableIntStateOf(prefs.getInt("aba", 0))
        private set

    fun mudarAba(n: Int) { aba = n; prefs.edit().putInt("aba", n).apply() }

    // ---------- Afinador ----------
    val afinador = MotorAfinador(app)
    var la4 by mutableDoubleStateOf(prefs.getFloat("la4", 440f).toDouble()); private set
    var afinacao by mutableIntStateOf(prefs.getInt("afinacao", 1).coerceIn(0, AFINACOES.lastIndex)); private set
    var solfejo by mutableStateOf(prefs.getBoolean("solfejo", false)); private set
    var estrobo by mutableStateOf(prefs.getBoolean("estrobo", false)); private set
    /** 0 = sensível, 1 = normal, 2 = ignora barulho */
    var filtroRuido by mutableIntStateOf(prefs.getInt("filtroRuido", 1)); private set
    /** Corda escolhida na mão (null = descobre sozinho). Não é salva. */
    var cordaFixa by mutableStateOf<Int?>(null)

    fun mudarLa4(v: Double) { la4 = v.coerceIn(415.0, 466.0); prefs.edit().putFloat("la4", la4.toFloat()).apply() }
    fun mudarAfinacao(i: Int) { afinacao = i; cordaFixa = null; prefs.edit().putInt("afinacao", i).apply() }
    fun mudarSolfejo(v: Boolean) { solfejo = v; prefs.edit().putBoolean("solfejo", v).apply() }
    fun mudarEstrobo(v: Boolean) { estrobo = v; prefs.edit().putBoolean("estrobo", v).apply() }
    fun mudarFiltro(v: Int) {
        filtroRuido = v
        afinador.nivelMinimoDb = when (v) { 0 -> -68.0; 2 -> -42.0; else -> -55.0 }
        prefs.edit().putInt("filtroRuido", v).apply()
    }

    // ---------- Metrônomo ----------
    val metronomo = MotorMetronomo().apply {
        mudarBpm(prefs.getInt("bpm", 100).toDouble())
        tempos = prefs.getInt("tempos", 4)
        subdivisao = prefs.getInt("subdivisao", 1)
        acentuar = prefs.getBoolean("acentuar", true)
        volume = prefs.getFloat("volumeMetro", 0.9f)
        treinoPasso = prefs.getInt("treinoPasso", 5)
        treinoCada = prefs.getInt("treinoCada", 4)
        treinoMax = prefs.getInt("treinoMax", 160)
        mudoToca = prefs.getInt("mudoToca", 2)
        mudoSilencia = prefs.getInt("mudoSilencia", 2)
    }
    var denominador by mutableIntStateOf(prefs.getInt("denominador", 4))
    var piscar by mutableStateOf(prefs.getBoolean("piscar", true))
    var vibrar by mutableStateOf(prefs.getBoolean("vibrar", false))
    /** Força a tela a recompor quando um ajuste do motor (que não é estado do Compose) muda. */
    var versaoMetro by mutableIntStateOf(0); private set

    fun ajustarMetro(bloco: MotorMetronomo.() -> Unit) {
        metronomo.bloco()
        versaoMetro++
        salvarMetro()
    }

    fun salvarMetro() {
        val m = metronomo
        prefs.edit()
            .putInt("bpm", m.bpm.toInt()).putInt("tempos", m.tempos).putInt("subdivisao", m.subdivisao)
            .putBoolean("acentuar", m.acentuar).putFloat("volumeMetro", m.volume)
            .putInt("treinoPasso", m.treinoPasso).putInt("treinoCada", m.treinoCada).putInt("treinoMax", m.treinoMax)
            .putInt("mudoToca", m.mudoToca).putInt("mudoSilencia", m.mudoSilencia)
            .putInt("denominador", denominador).putBoolean("piscar", piscar).putBoolean("vibrar", vibrar)
            .apply()
    }

    private val toques = ArrayDeque<Long>()

    /** Toque no ritmo: o BPM é a média dos últimos intervalos. */
    fun tapTempo() {
        val agora = System.nanoTime()
        if (toques.isNotEmpty() && agora - toques.last() > 2_000_000_000L) toques.clear()
        toques.addLast(agora)
        while (toques.size > 6) toques.removeFirst()
        if (toques.size >= 2) {
            val media = (toques.last() - toques.first()) / (toques.size - 1).toDouble()
            ajustarMetro { mudarBpm(Math.round(60e9 / media).toDouble()) }
        }
    }

    // ---------- Player ----------
    val player = ControlePlayer(app, prefs, viewModelScope)

    // ---------- Biblioteca (músicas do celular) ----------
    var musicas by mutableStateOf<List<br.com.begood.studio.player.MusicaLocal>?>(null); private set
    var carregandoMusicas by mutableStateOf(false); private set
    /** Biblioteca aberta por cima do player (com uma música já carregada). */
    var mostrarBiblioteca by mutableStateOf(false)
    /** Aba da biblioteca: 0 recentes, 1 músicas, 2 artistas, 3 pastas. */
    var abaBiblioteca by mutableIntStateOf(prefs.getInt("abaBiblioteca", 1))
        private set

    fun mudarAbaBiblioteca(n: Int) { abaBiblioteca = n; prefs.edit().putInt("abaBiblioteca", n).apply() }

    fun carregarMusicas() {
        if (carregandoMusicas) return
        carregandoMusicas = true
        viewModelScope.launch {
            musicas = br.com.begood.studio.player.LeitorBiblioteca.ler(getApplication())
            carregandoMusicas = false
        }
    }

    init { mudarFiltro(filtroRuido) }

    override fun onCleared() {
        afinador.parar()
        metronomo.parar()
        salvarMetro()
        player.liberar()
    }
}
