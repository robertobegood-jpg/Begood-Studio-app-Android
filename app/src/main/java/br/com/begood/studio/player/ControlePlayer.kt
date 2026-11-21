package br.com.begood.studio.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

data class Trecho(val nome: String, val a: Long, val b: Long)
data class Recente(val uri: String, val nome: String)

/**
 * Player para estudar: muda o tempo sem mudar o tom (e vice-versa), repete o trecho A–B
 * e pode ir acelerando a cada repetição.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class ControlePlayer(private val ctx: Context, private val prefs: SharedPreferences, private val escopo: CoroutineScope) {

    /** Mistura as faixas separadas (modo multipista). Desligado nas músicas normais. */
    private val mixer = MixerMultipista()

    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setRenderersFactory(object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(mixer))
                    .build()
        })
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                ctx,
                // Busca exata em MP3 de taxa variável: o A/B volta no ponto certo.
                DefaultExtractorsFactory()
                    .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
            )
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true) // tirou o fone: pausa
        .build().apply { setSeekParameters(SeekParameters.EXACT) }

    var uri by mutableStateOf<Uri?>(null); private set
    var nome by mutableStateOf(""); private set
    var tocando by mutableStateOf(false); private set
    var posicao by mutableLongStateOf(0L); private set
    var duracao by mutableLongStateOf(0L); private set
    var erro by mutableStateOf<String?>(null)

    /** Picos da forma de onda (0..1) ou null enquanto calcula. */
    var onda by mutableStateOf<FloatArray?>(null); private set
    var calculandoOnda by mutableStateOf(false); private set

    // Tempo e tom
    var velocidade by mutableFloatStateOf(1f); private set
    var semitons by mutableIntStateOf(0); private set
    var cents by mutableIntStateOf(0); private set

    // A/B
    var pontoA by mutableStateOf<Long?>(null); private set
    var pontoB by mutableStateOf<Long?>(null); private set
    var repetir by mutableStateOf(false); private set
    var repeticoes by mutableIntStateOf(0); private set
    var pausaEntre by mutableIntStateOf(prefs.getInt("pausaEntre", 0)) // segundos
    var acelerar by mutableStateOf(false)
    var passoAcelerar by mutableIntStateOf(prefs.getInt("passoAcelerar", 5)) // % por repetição
    var alvoAcelerar by mutableIntStateOf(prefs.getInt("alvoAcelerar", 100)) // %
    var esperando by mutableStateOf(false); private set
    /** Segundos que faltam para recomeçar a volta (durante a pausa entre voltas). */
    var faltaEspera by mutableIntStateOf(0); private set
    private var fimEspera = 0L

    // Multipista (faixas separadas: voz, guitarra, baixo...)
    /** Faixas da música aberta, ou null quando é uma música normal. */
    var faixas by mutableStateOf<List<FaixaMix>?>(null); private set
    /** Progresso (0..1) enquanto prepara as faixas, ou null. */
    var montando by mutableStateOf<Float?>(null); private set
    /** Aviso de problema ao abrir as faixas (mostrado numa janela). */
    var avisoMultipista by mutableStateOf<String?>(null)
    private var jobMontar: Job? = null

    val trechos = mutableStateListOf<Trecho>()
    val recentes = mutableStateListOf<Recente>()

    private var mensagemB: PlayerMessage? = null
    private val handler = Handler(Looper.getMainLooper())
    private var jobOnda: Job? = null
    private val voltarAoA = Runnable { if (esperando) { esperando = false; player.play() } }

    init {
        carregarRecentes()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { tocando = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) atualizarDuracao()
                if (state == Player.STATE_ENDED) tocando = false
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = recuperar(error)
        })
        escopo.launch {
            while (isActive) {
                posicao = player.currentPosition
                if (duracao <= 0 || player.duration > 0) atualizarDuracao()
                // Rede de segurança: em alguns MP3 (taxa variável) o aviso do B pode não chegar.
                val b = pontoB
                if (repetir && b != null && !esperando && player.isPlaying && posicao in (b + 30)..(b + 2000)) chegouNoB()
                faltaEspera = if (esperando) ((fimEspera - android.os.SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(1) else 0
                delay(40)
            }
        }
        // Abre a última música estudada (sem tocar)
        prefs.getString("ultima", null)?.let { u ->
            val r = recentes.firstOrNull { it.uri == u }
            if (r != null) runCatching { abrirRecente(r, tocar = false) }
        }
    }

    /** Abre uma música da lista de recentes (arquivo normal ou pasta de faixas separadas). */
    fun abrirRecente(r: Recente, tocar: Boolean = true) {
        val u = Uri.parse(r.uri)
        if (android.provider.DocumentsContract.isTreeUri(u)) abrirMultipista(u, tocar) else abrir(u, r.nome, tocar)
    }

    fun abrir(novo: Uri, nomeConhecido: String? = null, tocar: Boolean = true) {
        // Mantém a permissão de leitura do arquivo depois de reiniciar o celular
        runCatching { ctx.contentResolver.takePersistableUriPermission(novo, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        jobMontar?.cancel(); montando = null
        faixas = null
        mixer.faixas = 0
        carregar(novo, novo, nomeConhecido ?: nomeDoArquivo(novo), tocar, null)
    }

    /**
     * Abre uma pasta com as faixas separadas (Voz, Guitarra, Baixo, Bateria...). Na primeira vez
     * junta as faixas num arquivo só (leva alguns segundos); depois abre na hora.
     */
    fun abrirMultipista(pasta: Uri, tocar: Boolean = true) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(pasta, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        jobMontar?.cancel()
        player.pause()
        montando = 0f
        jobMontar = escopo.launch {
            try {
                var ultimo = -1
                val m = MontadorMultipista.montar(ctx, pasta) { f ->
                    val pct = (f * 100).toInt()
                    if (pct != ultimo) { ultimo = pct; escopo.launch { if (montando != null) montando = f } }
                }
                val lista = m.nomes.map { FaixaMix(it) }
                faixas = lista
                mixer.faixas = lista.size
                aplicarMix()
                montando = null
                carregar(pasta, Uri.fromFile(m.arquivo), MontadorMultipista.nomeDaPasta(ctx, pasta), tocar, m)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                montando = null
                avisoMultipista = (e as? MontadorMultipista.Problema)?.message
                    ?: "Não deu para abrir as faixas (${e.javaClass.simpleName}: ${e.message?.take(120)})."
            }
        }
    }

    // ---------- Volume de cada faixa ----------

    fun mudarVolume(f: FaixaMix, v: Float) { f.volume = v.coerceIn(0f, 1.5f); aplicarMix() }
    fun alternarMudo(f: FaixaMix) { f.mudo = !f.mudo; if (f.mudo) f.solo = false; aplicarMix() }
    fun alternarSolo(f: FaixaMix) { f.solo = !f.solo; if (f.solo) f.mudo = false; aplicarMix() }
    /** Tira só esta faixa (para tocar a parte dela por cima). */
    fun tirarSo(f: FaixaMix) { faixas?.forEach { it.solo = false; it.mudo = it === f }; aplicarMix() }
    fun mixNormal() { faixas?.forEach { it.volume = 1f; it.mudo = false; it.solo = false }; aplicarMix() }

    private fun aplicarMix() {
        val lista = faixas ?: return
        val temSolo = lista.any { it.solo }
        mixer.ganhos = FloatArray(lista.size) { i ->
            val f = lista[i]
            when {
                temSolo -> if (f.solo) f.volume else 0f
                f.mudo -> 0f
                else -> f.volume
            }
        }
    }

    /** [chave] identifica a música (trechos salvos, recentes); [midia] é o que o player toca. */
    private fun carregar(chave: Uri, midia: Uri, nomeMusica: String, tocar: Boolean, montagem: MontagemMultipista?) {
        val novo = chave
        erro = null
        cancelarEspera()
        uri = novo
        nome = nomeMusica
        limparAB()
        repeticoes = 0
        duracao = 0
        duracaoArquivo = montagem?.duracaoMs ?: 0
        if (montagem == null) escopo.launch {
            val d = lerDuracao(novo)
            if (uri == novo) { duracaoArquivo = d; atualizarDuracao() }
        }
        atualizarDuracao()
        player.setMediaItem(MediaItem.fromUri(midia))
        player.prepare()
        player.playWhenReady = tocar
        aplicarParametros()
        carregarTrechos()
        lembrarRecente(novo.toString(), nome)
        prefs.edit().putString("ultima", novo.toString()).apply()

        onda = null
        jobOnda?.cancel()
        if (montagem != null) { onda = montagem.onda; calculandoOnda = false; return }
        calculandoOnda = true
        jobOnda = escopo.launch {
            val picos = FormaDeOnda.calcular(ctx, novo)
            if (uri == novo) { onda = picos; calculandoOnda = false }
        }
    }

    /**
     * MP3 de taxa variável: o player só descobre a duração exata no fim da música. Enquanto isso,
     * usa a duração que o Android lê do arquivo (senão o A/B e a barra ficam em 0:00).
     */
    private var duracaoArquivo = 0L

    private fun atualizarDuracao() {
        val d = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: duracaoArquivo
        if (d > 0) duracao = d
    }

    private suspend fun lerDuracao(u: Uri): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, u)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { r.release() }
        }.getOrDefault(0L)
    }

    private var ultimaRecuperacao = 0L
    private val apagarAviso = Runnable { if (erro?.startsWith("Deu um erro") == true) erro = null }

    /**
     * Se o player der erro no meio da música, recomeça sozinho do mesmo ponto (uma vez a cada 5 s,
     * para não ficar em laço com um arquivo realmente estragado) e mostra o motivo exato.
     */
    private fun recuperar(e: androidx.media3.common.PlaybackException) {
        val causa = generateSequence(e.cause) { it.cause }.lastOrNull() ?: e.cause
        val detalhe = listOfNotNull(e.errorCodeName, causa?.javaClass?.simpleName, causa?.message?.take(140)).joinToString(" • ")
        android.util.Log.e("BegoodStudio", "Erro no player: $detalhe", e)
        val agora = android.os.SystemClock.elapsedRealtime()
        if (uri != null && agora - ultimaRecuperacao > 5000) {
            ultimaRecuperacao = agora
            val pos = player.currentPosition
            val tocar = player.playWhenReady || esperando
            cancelarEspera()
            player.prepare()
            player.seekTo(pos)
            player.playWhenReady = tocar
            aplicarParametros()
            reprogramar()
            erro = "Deu um erro ao tocar e o player recomeçou do mesmo ponto. Se repetir, mande este detalhe: $detalhe"
            handler.removeCallbacks(apagarAviso)
            handler.postDelayed(apagarAviso, 12000)
        } else {
            erro = "Não deu para tocar este arquivo. Detalhe: $detalhe"
        }
    }

    fun tocarPausar() {
        if (uri == null) return
        if (esperando) { cancelarEspera(); return }
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(pontoA ?: 0)
            player.play()
        }
    }

    fun irPara(ms: Long) {
        cancelarEspera()
        player.seekTo(ms.coerceIn(0, duracao.coerceAtLeast(0)))
        posicao = player.currentPosition
    }

    fun pular(deltaMs: Long) = irPara(player.currentPosition + deltaMs)

    /** Volta ao ponto A (ou ao início). */
    fun voltar() = irPara(pontoA ?: 0)

    // ---------- Tempo e tom ----------

    fun mudarVelocidade(v: Float) { velocidade = (Math.round(v * 100) / 100f).coerceIn(0.25f, 2f); aplicarParametros() }
    fun mudarSemitons(s: Int) { semitons = s.coerceIn(-12, 12); aplicarParametros() }
    fun mudarCents(c: Int) { cents = c.coerceIn(-50, 50); aplicarParametros() }
    fun zerarTom() { semitons = 0; cents = 0; aplicarParametros() }

    private fun aplicarParametros() {
        val tom = 2.0.pow(semitons / 12.0 + cents / 1200.0).toFloat()
        player.playbackParameters = PlaybackParameters(velocidade, tom)
    }

    // ---------- A/B ----------

    fun marcarA() = definirA(player.currentPosition)
    fun marcarB() = definirB(player.currentPosition)

    fun definirA(ms: Long) {
        val b = pontoB
        pontoA = ms.coerceIn(0, if (b != null) b - 100 else duracao)
        reprogramar()
    }

    fun definirB(ms: Long) {
        val a = pontoA ?: 0L
        if (pontoA == null) pontoA = 0L
        pontoB = ms.coerceIn(a + 100, duracao.coerceAtLeast(a + 100))
        if (!repetir) repetir = true
        reprogramar()
    }

    /** Seleciona A e B de uma vez (arrastando na forma de onda). */
    fun definirTrecho(a: Long, b: Long) {
        if (b - a < 200) return
        pontoA = a.coerceAtLeast(0); pontoB = b.coerceAtMost(duracao)
        repetir = true
        repeticoes = 0
        reprogramar()
        irPara(pontoA!!)
    }

    fun ajustarA(deltaMs: Long) { pontoA?.let { definirA(it + deltaMs) } }
    fun ajustarB(deltaMs: Long) { pontoB?.let { definirB(it + deltaMs) } }

    fun alternarRepetir() {
        repetir = !repetir
        if (repetir) repeticoes = 0
        reprogramar()
    }

    fun limparAB() {
        pontoA = null; pontoB = null; repetir = false; repeticoes = 0
        reprogramar()
    }

    /** O ExoPlayer avisa exatamente quando chega no B (não depende de ficar conferindo a posição). */
    private fun reprogramar() {
        mensagemB?.cancel()
        mensagemB = null
        val a = pontoA ?: return
        val b = pontoB ?: return
        if (!repetir || uri == null) return
        if (player.currentPosition > b + 50) player.seekTo(a)
        mensagemB = player.createMessage { _, _ -> chegouNoB() }
            .setLooper(Looper.getMainLooper())
            .setPosition(b)
            .setDeleteAfterDelivery(false)
            .send()
    }

    private fun chegouNoB() {
        val a = pontoA ?: return
        val b = pontoB ?: return
        if (!repetir || esperando) return
        // O aviso pode chegar duas vezes na mesma passagem; se já voltamos ao A, ignora.
        if (player.currentPosition < b - 150) return
        repeticoes++
        if (acelerar && velocidade * 100 < alvoAcelerar) {
            mudarVelocidade(minOf(alvoAcelerar / 100f, velocidade + passoAcelerar / 100f))
        }
        if (pausaEntre > 0) {
            player.pause()
            player.seekTo(a)
            esperando = true
            fimEspera = android.os.SystemClock.elapsedRealtime() + pausaEntre * 1000L
            faltaEspera = pausaEntre
            handler.postDelayed(voltarAoA, pausaEntre * 1000L)
        } else {
            player.seekTo(a)
        }
    }

    private fun cancelarEspera() {
        if (esperando) { esperando = false; handler.removeCallbacks(voltarAoA) }
    }

    fun salvarConfigAB() {
        prefs.edit().putInt("pausaEntre", pausaEntre).putInt("passoAcelerar", passoAcelerar)
            .putInt("alvoAcelerar", alvoAcelerar).apply()
    }

    // ---------- Trechos salvos (por música) ----------

    fun salvarTrecho(nomeTrecho: String) {
        val a = pontoA ?: return
        val b = pontoB ?: return
        trechos += Trecho(nomeTrecho.ifBlank { "Trecho ${trechos.size + 1}" }, a, b)
        gravarTrechos()
    }

    fun usarTrecho(t: Trecho) = definirTrecho(t.a, t.b)

    fun apagarTrecho(t: Trecho) { trechos.remove(t); gravarTrechos() }

    private fun chaveTrechos() = "trechos:" + uri.toString()

    private fun carregarTrechos() {
        trechos.clear()
        val json = prefs.getString(chaveTrechos(), null) ?: return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                trechos += Trecho(o.getString("nome"), o.getLong("a"), o.getLong("b"))
            }
        }
    }

    private fun gravarTrechos() {
        val arr = JSONArray()
        trechos.forEach { arr.put(JSONObject().put("nome", it.nome).put("a", it.a).put("b", it.b)) }
        prefs.edit().putString(chaveTrechos(), arr.toString()).apply()
    }

    // ---------- Recentes ----------

    private fun carregarRecentes() {
        val json = prefs.getString("recentes", null) ?: return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                recentes += Recente(o.getString("uri"), o.getString("nome"))
            }
        }
    }

    private fun lembrarRecente(u: String, n: String) {
        recentes.removeAll { it.uri == u }
        recentes.add(0, Recente(u, n))
        while (recentes.size > 15) recentes.removeAt(recentes.lastIndex)
        val arr = JSONArray()
        recentes.forEach { arr.put(JSONObject().put("uri", it.uri).put("nome", it.nome)) }
        prefs.edit().putString("recentes", arr.toString()).apply()
    }

    fun esquecerRecente(r: Recente) {
        recentes.remove(r)
        val arr = JSONArray()
        recentes.forEach { arr.put(JSONObject().put("uri", it.uri).put("nome", it.nome)) }
        prefs.edit().putString("recentes", arr.toString()).apply()
    }

    private fun nomeDoArquivo(u: Uri): String =
        runCatching {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: u.lastPathSegment ?: "Música"

    fun liberar() {
        handler.removeCallbacksAndMessages(null)
        player.release()
    }
}
