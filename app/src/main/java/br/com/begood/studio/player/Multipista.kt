package br.com.begood.studio.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Uma faixa separada (voz, guitarra...) com o volume escolhido na tela. */
class FaixaMix(val nome: String) {
    var volume by mutableFloatStateOf(1f)
    var mudo by mutableStateOf(false)
    var solo by mutableStateOf(false)
}

/** Tudo que o player precisa para tocar as faixas juntas. */
class MontagemMultipista(
    val arquivo: File,
    val nomes: List<String>,
    val duracaoMs: Long,
    val onda: FloatArray,
)

/**
 * Mistura as faixas na hora de tocar. O arquivo montado tem todas as faixas lado a lado
 * (2 canais por faixa); aqui cada faixa recebe o seu volume e tudo vira estéreo de novo.
 * Fica antes do ajuste de tempo e tom, então velocidade, tom e A/B continuam valendo.
 */
@UnstableApi
class MixerMultipista : BaseAudioProcessor() {
    /** Quantas faixas o próximo arquivo tem (0 = música normal: o mixer fica desligado). */
    @Volatile var faixas = 0
    @Volatile var ganhos = FloatArray(0)
    private var anteriores = FloatArray(0)
    private var n = 0

    override fun onConfigure(entrada: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val f = faixas
        if (f < 2 || entrada.channelCount != f * 2) return AudioProcessor.AudioFormat.NOT_SET
        if (entrada.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(entrada)
        n = f
        anteriores = FloatArray(f) { ganhos.getOrElse(it) { 1f } }
        return AudioProcessor.AudioFormat(entrada.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(entrada: ByteBuffer) {
        val canais = n * 2
        val quadros = entrada.remaining() / (canais * 2)
        if (quadros == 0) return
        val saida = replaceOutputBuffer(quadros * 4)
        val alvo = ganhos.let { g -> FloatArray(n) { g.getOrElse(it) { 1f } } }
        val ini = anteriores
        // O volume muda aos poucos dentro do bloco (sem estalo ao mexer no controle)
        val passo = FloatArray(n) { (alvo[it] - ini[it]) / quadros }
        val g = ini.copyOf()
        entrada.order(ByteOrder.LITTLE_ENDIAN)
        val base = entrada.position()
        for (q in 0 until quadros) {
            var l = 0f; var r = 0f
            val p = base + q * canais * 2
            for (k in 0 until n) {
                g[k] += passo[k]
                if (g[k] != 0f) {
                    l += entrada.getShort(p + k * 4) * g[k]
                    r += entrada.getShort(p + k * 4 + 2) * g[k]
                }
            }
            saida.putShort(l.coerceIn(-32768f, 32767f).toInt().toShort())
            saida.putShort(r.coerceIn(-32768f, 32767f).toInt().toShort())
        }
        anteriores = alvo
        entrada.position(base + quadros * canais * 2)
        saida.flip()
    }

    override fun onReset() { n = 0 }
}

/**
 * Junta as faixas de uma pasta num arquivo WAV só (guardado no cache do app), com cada faixa
 * em dois canais. Assim todas tocam exatamente juntas, sem uma atrasar em relação à outra.
 */
object MontadorMultipista {
    const val MAX_FAIXAS = 8
    private val extensoes = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "3gp", "amr")

    class Problema(msg: String) : Exception(msg)

    private class Arquivo(val nome: String, val uri: Uri, val tamanho: Long, val ordem: Int)

    /** Nome em português e a ordem na tela (voz primeiro, "outros" por último). */
    fun nomeDaFaixa(arquivo: String): Pair<String, Int> {
        val s = semAcento(arquivo.substringBeforeLast('.'))
        return when {
            listOf("vocal", "voz", "vox", "voice", "canto").any { it in s } -> "Voz" to 0
            listOf("guitar", "guitarra", "violao").any { it in s } -> "Guitarra" to 1
            "bass" in s || "baixo" in s -> "Baixo" to 2
            listOf("drum", "bateria", "percuss").any { it in s } -> "Bateria" to 3
            listOf("piano", "keys", "teclado").any { it in s } -> "Piano" to 4
            listOf("other", "outros", "resto", "instrumental").any { it in s } -> "Outros" to 6
            else -> arquivo.substringBeforeLast('.') to 5
        }
    }

    /** Pasta comum do celular (faixas separadas pelo próprio app) ou pasta escolhida pelo seletor do Android. */
    fun ehPastaDeArquivos(u: Uri) = u.scheme == "file" && u.path?.let { File(it).isDirectory } == true

    private fun listar(ctx: Context, pasta: Uri): List<Arquivo> {
        if (pasta.scheme == "file") {
            val dir = File(pasta.path!!)
            return (dir.listFiles() ?: emptyArray())
                .filter { it.isFile && it.extension.lowercase() in extensoes }
                .map { f -> nomeDaFaixa(f.name).let { (rotulo, ordem) -> Arquivo(rotulo, Uri.fromFile(f), f.length(), ordem) } }
                .sortedWith(compareBy({ it.ordem }, { semAcento(it.nome) }))
        }
        val doc = DocumentsContract.getTreeDocumentId(pasta)
        val filhos = DocumentsContract.buildChildDocumentsUriUsingTree(pasta, doc)
        val lista = ArrayList<Arquivo>()
        ctx.contentResolver.query(
            filhos,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val nome = c.getString(1).orEmpty()
                val mime = c.getString(2).orEmpty()
                val ext = nome.substringAfterLast('.', "").lowercase()
                if (!mime.startsWith("audio/") && ext !in extensoes) continue
                val (rotulo, ordem) = nomeDaFaixa(nome)
                lista += Arquivo(rotulo, DocumentsContract.buildDocumentUriUsingTree(pasta, c.getString(0)), c.getLong(3), ordem)
            }
        }
        return lista.sortedWith(compareBy({ it.ordem }, { semAcento(it.nome) }))
    }

    fun nomeDaPasta(ctx: Context, pasta: Uri): String = runCatching {
        if (pasta.scheme == "file") return@runCatching File(pasta.path!!).name
        val doc = DocumentsContract.buildDocumentUriUsingTree(pasta, DocumentsContract.getTreeDocumentId(pasta))
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "Faixas separadas"

    suspend fun montar(ctx: Context, pasta: Uri, progresso: (Float) -> Unit): MontagemMultipista = withContext(Dispatchers.Default) {
        val arquivos = listar(ctx, pasta)
        if (arquivos.size < 2) throw Problema("Esta pasta precisa ter pelo menos duas faixas de áudio (ex.: Voz, Guitarra, Baixo, Bateria).")
        if (arquivos.size > MAX_FAIXAS) throw Problema("Esta pasta tem ${arquivos.size} arquivos de áudio. O modo multipista aceita até $MAX_FAIXAS faixas.")

        val dir = File(ctx.cacheDir, "multipista").apply { mkdirs() }
        val chave = (pasta.toString() + arquivos.joinToString { it.nome + it.tamanho }).hashCode().toUInt().toString(16)
        val wav = File(dir, "$chave.wav")
        val info = File(dir, "$chave.json")
        // Já montado antes: abre na hora
        if (wav.exists() && info.exists()) {
            runCatching {
                val o = JSONObject(info.readText())
                val nomes = o.getJSONArray("nomes").let { a -> List(a.length()) { a.getString(it) } }
                val onda = o.getJSONArray("onda").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                return@withContext MontagemMultipista(wav, nomes, o.getLong("duracao"), onda)
            }
        }
        // Só guarda uma montagem por vez (os arquivos são grandes)
        dir.listFiles()?.forEach { it.delete() }

        val brutos = ArrayList<File>()
        try {
            var taxa = 0
            arquivos.forEachIndexed { i, a ->
                val bruto = File(dir, "faixa$i.raw")
                brutos += bruto
                val t = decodificar(ctx, a, bruto) { f -> progresso((i + f) / (arquivos.size + 1f)) }
                if (taxa == 0) taxa = t
                else if (t != taxa) throw Problema("As faixas têm qualidades diferentes ($taxa Hz e $t Hz). Use faixas tiradas da mesma separação.")
            }
            val quadros = brutos.maxOf { it.length() / 4 }
            if (quadros <= 0) throw Problema("Não deu para ler o áudio das faixas.")
            val onda = intercalar(brutos, wav, quadros, taxa) { f -> progresso((arquivos.size + f) / (arquivos.size + 1f)) }
            val duracao = quadros * 1000L / taxa
            val nomes = arquivos.map { it.nome }
            info.writeText(
                JSONObject().put("nomes", JSONArray(nomes)).put("duracao", duracao)
                    .put("onda", JSONArray().apply { onda.forEach { put(it.toDouble()) } }).toString()
            )
            MontagemMultipista(wav, nomes, duracao, onda)
        } catch (e: Exception) {
            wav.delete(); info.delete()
            throw e
        } finally {
            brutos.forEach { it.delete() }
        }
    }

    private suspend fun decodificar(ctx: Context, a: Arquivo, destino: File, progresso: (Float) -> Unit): Int =
        decodificarPcm16(ctx, a.uri, a.nome, destino, progresso)

    /** Decodifica um arquivo de áudio para PCM 16 bits estéreo (L, R intercalados). Devolve a taxa de amostragem. */
    suspend fun decodificarPcm16(ctx: Context, uri: Uri, rotulo: String, destino: File, progresso: (Float) -> Unit): Int {
        val a = object { val uri = uri; val nome = rotulo }
        val extrator = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extrator.setDataSource(ctx, a.uri, null)
            val faixa = (0 until extrator.trackCount).firstOrNull {
                extrator.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw Problema("O arquivo da faixa \"${a.nome}\" não tem áudio.")
            extrator.selectTrack(faixa)
            val formato = extrator.getTrackFormat(faixa)
            val duracaoUs = if (formato.containsKey(MediaFormat.KEY_DURATION)) formato.getLong(MediaFormat.KEY_DURATION) else 0L
            var taxa = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var canais = formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val c = MediaCodec.createDecoderByType(formato.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(formato, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var fimEntrada = false
            var fimSaida = false
            BufferedOutputStream(FileOutputStream(destino), 1 shl 16).use { out ->
                var tmp = ByteArray(0)
                while (!fimSaida) {
                    coroutineContext.ensureActive()
                    if (!fimEntrada) {
                        val i = c.dequeueInputBuffer(5000)
                        if (i >= 0) {
                            val n = extrator.readSampleData(c.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); fimEntrada = true
                            } else {
                                c.queueInputBuffer(i, 0, n, extrator.sampleTime, 0); extrator.advance()
                            }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 5000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = c.outputFormat
                        taxa = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        canais = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val amostras = if (pcmFloat) info.size / 4 else info.size / 2
                            val quadros = amostras / canais.coerceAtLeast(1)
                            if (tmp.size < quadros * 4) tmp = ByteArray(quadros * 4)
                            val bb = ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN)
                            fun ler(): Short = if (pcmFloat) (buf.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort() else buf.short
                            for (q in 0 until quadros) {
                                val l = ler()
                                val r = if (canais >= 2) ler() else l
                                for (x in 2 until canais) ler() // canais além do estéreo: ignora
                                bb.putShort(l); bb.putShort(r)
                            }
                            out.write(tmp, 0, quadros * 4)
                            if (duracaoUs > 0) progresso((info.presentationTimeUs.toFloat() / duracaoUs).coerceIn(0f, 1f))
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fimSaida = true
                        c.releaseOutputBuffer(o, false)
                    }
                }
            }
            return taxa
        } catch (e: Problema) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Problema("Não deu para ler a faixa \"${a.nome}\" (${e.javaClass.simpleName}).")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extrator.release()
        }
    }

    /** Escreve o WAV com as faixas lado a lado e calcula a forma de onda da mistura. */
    private suspend fun intercalar(brutos: List<File>, wav: File, quadros: Long, taxa: Int, progresso: (Float) -> Unit): FloatArray {
        val n = brutos.size
        val canais = n * 2
        val colunas = 1200
        val picos = FloatArray(colunas)
        val bloco = 4096
        val entradas = brutos.map { DataInputStream(BufferedInputStream(FileInputStream(it), 1 shl 16)) }
        try {
            BufferedOutputStream(FileOutputStream(wav), 1 shl 17).use { out ->
                out.write(cabecalhoWav(quadros, taxa, canais))
                val lidos = Array(n) { ByteArray(bloco * 4) }
                val saida = ByteArray(bloco * canais * 2)
                var feito = 0L
                while (feito < quadros) {
                    coroutineContext.ensureActive()
                    val q = minOf(bloco.toLong(), quadros - feito).toInt()
                    for (k in 0 until n) {
                        val b = lidos[k]
                        var pos = 0
                        while (pos < q * 4) {
                            val r = runCatching { entradas[k].read(b, pos, q * 4 - pos) }.getOrDefault(-1)
                            if (r <= 0) break
                            pos += r
                        }
                        if (pos < q * 4) b.fill(0, pos, q * 4) // faixa mais curta: completa com silêncio
                    }
                    for (i in 0 until q) {
                        var soma = 0
                        for (k in 0 until n) {
                            val b = lidos[k]
                            val o = (i * canais + k * 2) * 2
                            saida[o] = b[i * 4]; saida[o + 1] = b[i * 4 + 1]
                            saida[o + 2] = b[i * 4 + 2]; saida[o + 3] = b[i * 4 + 3]
                            soma += ((b[i * 4 + 1].toInt() shl 8) or (b[i * 4].toInt() and 0xFF)).toShort().toInt()
                        }
                        val col = ((feito + i) * colunas / quadros).toInt().coerceIn(0, colunas - 1)
                        val v = abs(soma) / 32768f
                        if (v > picos[col]) picos[col] = v
                    }
                    out.write(saida, 0, q * canais * 2)
                    feito += q
                    progresso(feito.toFloat() / quadros)
                }
            }
        } finally {
            entradas.forEach { runCatching { it.close() } }
        }
        val maior = picos.maxOrNull()?.takeIf { it > 0 } ?: 1f
        for (i in picos.indices) picos[i] /= maior
        return picos
    }

    private fun cabecalhoWav(quadros: Long, taxa: Int, canais: Int): ByteArray {
        val dados = quadros * canais * 2
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dados).coerceAtMost(0xFFFFFFFFL).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(canais.toShort()); b.putInt(taxa); b.putInt(taxa * canais * 2)
        b.putShort((canais * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dados.coerceAtMost(0xFFFFFFFFL).toInt())
        return b.array()
    }

}
