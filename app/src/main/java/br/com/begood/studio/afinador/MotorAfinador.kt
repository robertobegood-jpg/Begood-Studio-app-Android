package br.com.begood.studio.afinador

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.log2

/** O que o afinador está ouvindo agora. [freq] null = nenhuma nota clara. */
data class Leitura(val freq: Double?, val nivelDb: Double, val clareza: Double = 0.0)

/**
 * Lê o microfone numa thread de áudio e analisa ~47 vezes por segundo.
 * A leitura é estabilizada com uma mediana das últimas análises da mesma nota
 * (o ponteiro não treme e o erro aleatório cai).
 */
class MotorAfinador(private val ctx: Context) {
    private val _leitura = MutableStateFlow(Leitura(null, -120.0))
    val leitura: StateFlow<Leitura> = _leitura

    /** Nível mínimo do som (dBFS) para considerar: quanto maior, mais ignora barulho de fundo. */
    @Volatile var nivelMinimoDb = -55.0

    /** true quando não deu para abrir o microfone (outro app usando, por exemplo). */
    val falhou = MutableStateFlow(false)

    @Volatile private var rodando = false
    private var thread: Thread? = null

    val ativo get() = rodando

    @SuppressLint("MissingPermission") // quem chama confere a permissão RECORD_AUDIO
    fun iniciar() {
        if (rodando) return
        rodando = true
        thread = Thread({ laco() }, "afinador").apply { start() }
    }

    fun parar() {
        rodando = false
        thread?.join(500)
        thread = null
        _leitura.value = Leitura(null, -120.0)
    }

    @SuppressLint("MissingPermission")
    private fun laco() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // "Sem processamento" evita o controle automático de volume e os filtros de voz do celular.
        val semProcessamento = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val fonte = if (semProcessamento) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION

        var gravador: AudioRecord? = null
        var taxa = 0
        for (t in intArrayOf(48000, 44100)) {
            val minimo = AudioRecord.getMinBufferSize(t, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (minimo <= 0) continue
            val g = runCatching {
                AudioRecord(fonte, t, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minimo, 8192 * 4))
            }.getOrNull()
            if (g != null && g.state == AudioRecord.STATE_INITIALIZED) { gravador = g; taxa = t; break }
            g?.release()
        }
        if (gravador == null) { rodando = false; falhou.value = true; return }
        falhou.value = false

        val analisador = Analisador(taxa)
        val janela = FloatArray(analisador.tamanho)
        val salto = 1024
        val bloco = FloatArray(salto)
        val recentes = ArrayDeque<Double>()
        var ultimaNota = 0L

        try {
            gravador.startRecording()
            while (rodando) {
                var lidos = 0
                while (lidos < salto && rodando) {
                    val n = gravador.read(bloco, lidos, salto - lidos, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    lidos += n
                }
                if (lidos < salto) continue
                System.arraycopy(janela, salto, janela, 0, janela.size - salto)
                System.arraycopy(bloco, 0, janela, janela.size - salto, salto)

                val r = analisador.analisar(janela, nivelMinimoDb)
                val agora = System.currentTimeMillis()
                if (r == null) {
                    // Segura a última nota um instante (a corda some aos poucos)
                    if (agora - ultimaNota > 600) recentes.clear()
                    val anterior = _leitura.value
                    _leitura.value = Leitura(if (recentes.isEmpty()) null else anterior.freq, nivelDb(bloco), 0.0)
                    continue
                }
                ultimaNota = agora
                if (recentes.isNotEmpty()) {
                    val mediana = recentes.sorted()[recentes.size / 2]
                    if (abs(1200 * log2(r.freq / mediana)) > 35) recentes.clear() // mudou de nota
                }
                recentes.addLast(r.freq)
                while (recentes.size > 9) recentes.removeFirst()
                _leitura.value = Leitura(estavel(recentes), r.nivelDb, r.clareza)
            }
        } finally {
            runCatching { gravador.stop() }
            gravador.release()
        }
    }

    /** Média sem os extremos: estável e precisa. */
    private fun estavel(v: ArrayDeque<Double>): Double {
        val s = v.sorted()
        val corte = s.size / 4
        return s.subList(corte, s.size - corte).average()
    }

    private fun nivelDb(b: FloatArray): Double {
        var e = 0.0
        for (x in b) e += x * x
        val rms = kotlin.math.sqrt(e / b.size)
        return if (rms > 0) 20 * kotlin.math.log10(rms) else -120.0
    }
}
