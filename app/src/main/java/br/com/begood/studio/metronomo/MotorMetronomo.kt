package br.com.begood.studio.metronomo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Metrônomo que monta o próprio áudio, amostra por amostra.
 *
 * Cada clique é colocado na posição exata (em amostras) dentro do fluxo de áudio, então o tempo
 * não "escorrega" com o passar dos minutos, como acontece com metrônomos baseados em relógio.
 * A tela descobre qual tempo está soando lendo a posição real de reprodução do AudioTrack.
 */
class MotorMetronomo {
    /** Momento (em amostras tocadas) de cada clique — a tela usa para piscar no tempo certo. */
    data class Marca(val quadro: Long, val tempo: Int, val sub: Int, val compasso: Long, val mudo: Boolean)

    val taxa: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48000

    @Volatile var bpm = 120.0
    @Volatile var tempos = 4
    @Volatile var subdivisao = 1
    @Volatile var acentuar = true
    @Volatile var volume = 0.9f

    // Treino de velocidade: sobe [treinoPasso] BPM a cada [treinoCada] compassos, até [treinoMax].
    @Volatile var treino = false
    @Volatile var treinoPasso = 5
    @Volatile var treinoCada = 4
    @Volatile var treinoMax = 160

    // Compassos mudos: toca [mudoToca], silencia [mudoSilencia] (para treinar o tempo interno).
    @Volatile var mudo = false
    @Volatile var mudoToca = 2
    @Volatile var mudoSilencia = 2

    private val _bpmAtual = MutableStateFlow(bpm)
    /** Muda sozinho no modo treino. */
    val bpmAtual: StateFlow<Double> = _bpmAtual

    private val _tocando = MutableStateFlow(false)
    val tocando: StateFlow<Boolean> = _tocando

    @Volatile private var rodando = false
    private var thread: Thread? = null
    @Volatile private var track: AudioTrack? = null
    private val marcas = ArrayDeque<Marca>()

    private val somAcento = clique(1800.0, 1.0)
    private val somTempo = clique(1200.0, 0.75)
    private val somSub = clique(800.0, 0.45)

    fun mudarBpm(novo: Double) {
        bpm = novo.coerceIn(20.0, 300.0)
        _bpmAtual.value = bpm
    }

    fun iniciar() {
        if (rodando) return
        rodando = true
        _tocando.value = true
        thread = Thread({ laco() }, "metronomo").apply { start() }
    }

    fun parar() {
        rodando = false
        thread?.join(500)
        thread = null
        _tocando.value = false
        synchronized(marcas) { marcas.clear() }
    }

    /** Qual clique está soando agora (null = parado). */
    fun marcaAtual(): Marca? {
        val t = track ?: return null
        val tocado = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrNull() ?: return null
        synchronized(marcas) {
            while (marcas.size >= 2 && marcas[1].quadro <= tocado) marcas.removeFirst()
            val m = marcas.firstOrNull() ?: return null
            return if (m.quadro <= tocado) m else null
        }
    }

    private fun laco() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minimo = AudioTrack.getMinBufferSize(taxa, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(taxa)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minimo, 2048 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t

        val bloco = 256
        val buf = FloatArray(bloco)
        // Sons tocando agora: (som, posição dentro do som, início dentro do bloco, ganho)
        class Voz(val som: FloatArray, var pos: Int, var inicio: Int, val ganho: Float)
        val vozes = ArrayList<Voz>()

        var escrito = 0L
        var proximo = taxa * 0.05 // pequena folga antes do primeiro clique
        var tempo = 0
        var sub = 0
        var compasso = 0L

        try {
            t.play()
            while (rodando) {
                java.util.Arrays.fill(buf, 0f)
                // Agenda os cliques que caem dentro deste bloco
                while (proximo < escrito + bloco) {
                    val inicio = (proximo - escrito).toInt().coerceIn(0, bloco - 1)
                    val silencio = mudo && (compasso % (mudoToca + mudoSilencia)) >= mudoToca
                    val som = when {
                        sub != 0 -> somSub
                        tempo == 0 && acentuar -> somAcento
                        else -> somTempo
                    }
                    if (!silencio) vozes += Voz(som, 0, inicio, volume)
                    synchronized(marcas) {
                        marcas.addLast(Marca(escrito + inicio, tempo, sub, compasso, silencio))
                        while (marcas.size > 64) marcas.removeFirst()
                    }
                    // Avança para o próximo clique (subdivisão → tempo → compasso)
                    proximo += taxa * 60.0 / (bpm * subdivisao.coerceAtLeast(1))
                    sub++
                    if (sub >= subdivisao) {
                        sub = 0; tempo++
                        if (tempo >= tempos) {
                            tempo = 0; compasso++
                            if (treino && treinoCada > 0 && compasso % treinoCada == 0L && bpm < treinoMax) {
                                mudarBpm(minOf(treinoMax.toDouble(), bpm + treinoPasso))
                            }
                        }
                    }
                }
                // Mistura
                val it = vozes.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    var i = v.inicio
                    while (i < bloco && v.pos < v.som.size) { buf[i] += v.som[v.pos++] * v.ganho; i++ }
                    v.inicio = 0
                    if (v.pos >= v.som.size) it.remove()
                }
                t.write(buf, 0, bloco, AudioTrack.WRITE_BLOCKING)
                escrito += bloco
            }
        } finally {
            runCatching { t.pause(); t.flush(); t.stop() }
            track = null
            t.release()
        }
    }

    /** Clique curto: senoide com ataque rápido e queda exponencial (sem estalo). */
    private fun clique(freq: Double, ganho: Double): FloatArray {
        val n = (taxa * 0.045).toInt()
        return FloatArray(n) { i ->
            val s = i.toDouble() / taxa
            val env = (1 - exp(-s / 0.0007)) * exp(-s / 0.009)
            (ganho * env * (sin(2 * PI * freq * s) + 0.25 * sin(2 * PI * freq * 2.01 * s))).toFloat() * 0.8f
        }
    }
}

/** Nome italiano do andamento (só para referência). */
fun nomeAndamento(bpm: Int): String = when {
    bpm < 40 -> "Grave"
    bpm < 60 -> "Largo"
    bpm < 66 -> "Larghetto"
    bpm < 76 -> "Adagio"
    bpm < 108 -> "Andante"
    bpm < 120 -> "Moderato"
    bpm < 156 -> "Allegro"
    bpm < 176 -> "Vivace"
    bpm < 200 -> "Presto"
    else -> "Prestissimo"
}
