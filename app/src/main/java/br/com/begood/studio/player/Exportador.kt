package br.com.begood.studio.player

import android.content.ContentValues
import android.content.Context
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import br.com.begood.studio.separador.CodificadorAac
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Exporta o que está tocando como um arquivo .m4a: a mistura dos instrumentos (volumes, tirar, só),
 * a velocidade e o tom escolhidos e, se quiser, só o trecho A–B. Bom para mandar o playback para a banda.
 */
@UnstableApi
object Exportador {
    class Pedido(
        val nome: String,
        /** Música normal (decodifica o arquivo) … */
        val musica: Uri?,
        /** … ou o arquivo montado do multipista, com o volume de cada faixa. */
        val multipista: File?,
        val ganhos: FloatArray?,
        val inicioMs: Long?,
        val fimMs: Long?,
        val velocidade: Float,
        val tom: Float,
        /** Quadros a pular no início (atraso do AAC das faixas separadas pelo app). */
        val deslocQuadros: Long = 0,
    )

    class Resultado(val uris: List<Uri>, val ondeFicou: String, val tipo: String = "audio/mp4")

    suspend fun exportar(ctx: Context, p: Pedido, progresso: (Float) -> Unit): Resultado = withContext(Dispatchers.Default) {
        val trabalho = File(ctx.cacheDir, "exportando").apply { deleteRecursively(); mkdirs() }
        try {
            // 1) Som estéreo do trecho escolhido
            val estereo = File(trabalho, "estereo.raw")
            val taxa = if (p.multipista != null) {
                misturar(p.multipista, p.ganhos ?: FloatArray(0), p.inicioMs, p.fimMs, estereo, p.deslocQuadros) { progresso(it * 0.4f) }
            } else {
                val todo = File(trabalho, "todo.raw")
                val t = MontadorMultipista.decodificarPcm16(ctx, p.musica!!, "música", todo) { progresso(it * 0.4f) }
                recortar(todo, t, p.inicioMs, p.fimMs, estereo)
                todo.delete()
                t
            }
            // 2) Velocidade e tom (o mesmo processamento que o player usa)
            val final = if (p.velocidade != 1f || p.tom != 1f) {
                File(trabalho, "final.raw").also { aplicarSonic(estereo, taxa, p.velocidade, p.tom, it) { f -> progresso(0.4f + f * 0.3f) }; estereo.delete() }
            } else estereo
            if (final.length() < 4 * 1000) throw MontadorMultipista.Problema("O trecho escolhido é curto demais para exportar.")
            // 3) Salva em .m4a na pasta Música
            val nomeArq = p.nome.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(100).ifEmpty { "Begood Studio" } + ".m4a"
            salvar(ctx, nomeArq) { muxer -> CodificadorAac.codificar(final, taxa, muxer, kbps = 256) { f -> progresso(0.7f + f * 0.3f) } }
        } finally {
            trabalho.deleteRecursively()
        }
    }

    private fun limparNome(s: String, max: Int) = s.replace(Regex("[\\/:*?\"<>|]"), "_").trim().take(max)

    /**
     * Exporta cada faixa separada como WAV (sem perda, 16 bits) para usar em DAW (Reaper, Studio One, Audacity...).
     * Todas começam no mesmo ponto: é só arrastar para o projeto. [deslocQuadros] tira o atraso do AAC das faixas
     * separadas pelo app, para elas ficarem alinhadas também com a música original.
     */
    suspend fun exportarFaixas(
        ctx: Context, wav: File, nomes: List<String>, pasta: String,
        iniMs: Long?, fimMs: Long?, velocidade: Float, tom: Float, deslocQuadros: Long,
        progresso: (Float) -> Unit,
    ): Resultado = withContext(Dispatchers.Default) {
        val trabalho = File(ctx.cacheDir, "exportando").apply { deleteRecursively(); mkdirs() }
        val pastaLimpa = limparNome(pasta, 90).ifEmpty { "Faixas" }
        val uris = ArrayList<Uri>()
        try {
            nomes.forEachIndexed { k, nome ->
                val base = k.toFloat() / nomes.size
                val passo = 1f / nomes.size
                val raw = File(trabalho, "f$k.raw")
                val ganhos = FloatArray(nomes.size) { if (it == k) 1f else 0f }
                val taxa = misturar(wav, ganhos, iniMs, fimMs, raw, deslocQuadros) { progresso(base + it * passo * 0.5f) }
                val final = if (velocidade != 1f || tom != 1f) {
                    File(trabalho, "s$k.raw").also { aplicarSonic(raw, taxa, velocidade, tom, it) { f -> progresso(base + passo * (0.5f + f * 0.3f)) }; raw.delete() }
                } else raw
                val arq = "%02d %s.wav".format(k + 1, limparNome(nome, 40))
                uris += salvarWav(ctx, pastaLimpa, arq, final, taxa)
                final.delete()
                progresso(base + passo)
            }
            Resultado(uris, "Música/Begood Studio/$pastaLimpa", "audio/wav")
        } finally {
            trabalho.deleteRecursively()
        }
    }

    private fun cabecalhoWav(bytes: Long, taxa: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + bytes).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(2); b.putInt(taxa); b.putInt(taxa * 4); b.putShort(4); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(bytes.toInt())
        return b.array()
    }

    private fun salvarWav(ctx: Context, pasta: String, nomeArq: String, raw: File, taxa: Int): Uri {
        fun escrever(out: java.io.OutputStream) {
            out.write(cabecalhoWav(raw.length(), taxa))
            raw.inputStream().use { it.copyTo(out, 1 shl 16) }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, nomeArq)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Begood Studio/" + pasta)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
                ?: throw MontadorMultipista.Problema("Não deu para criar o arquivo na pasta Música.")
            try {
                cr.openOutputStream(uri)!!.buffered(1 shl 16).use { escrever(it) }
                cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                runCatching { cr.delete(uri, null, null) }
                throw e
            }
            return uri
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir, "Begood Studio/$pasta").apply { mkdirs() }
        val arq = File(dir, nomeArq)
        FileOutputStream(arq).buffered(1 shl 16).use { escrever(it) }
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".arquivos", arq)
    }

    /** Mistura as faixas do arquivo multipista (2 canais por faixa) em estéreo. */
    private suspend fun misturar(
        wav: File, ganhos: FloatArray, iniMs: Long?, fimMs: Long?, saida: File, deslocQuadros: Long = 0, progresso: (Float) -> Unit,
    ): Int {
        RandomAccessFile(wav, "r").use { ra ->
            val cab = ByteArray(44); ra.readFully(cab)
            val bb = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN)
            val canais = bb.getShort(22).toInt()
            val taxa = bb.getInt(24)
            val n = canais / 2
            val total = (wav.length() - 44) / (canais * 2)
            val de = ((iniMs ?: 0) * taxa / 1000 + deslocQuadros).coerceIn(0, total)
            val ate = ((fimMs ?: (Long.MAX_VALUE / 2 / taxa)) * taxa / 1000 + deslocQuadros).coerceIn(de, total)
            val g = FloatArray(n) { ganhos.getOrElse(it) { 1f } }
            ra.seek(44 + de * canais * 2)
            val bloco = 4096
            val ent = ByteArray(bloco * canais * 2)
            val sai = ByteBuffer.allocate(bloco * 4).order(ByteOrder.LITTLE_ENDIAN)
            BufferedOutputStream(FileOutputStream(saida), 1 shl 16).use { out ->
                var pos = de
                while (pos < ate) {
                    coroutineContext.ensureActive()
                    val q = minOf(bloco.toLong(), ate - pos).toInt()
                    ra.readFully(ent, 0, q * canais * 2)
                    val e = ByteBuffer.wrap(ent).order(ByteOrder.LITTLE_ENDIAN)
                    sai.clear()
                    for (i in 0 until q) {
                        var l = 0f; var r = 0f
                        val base = i * canais * 2
                        for (k in 0 until n) {
                            if (g[k] == 0f) continue
                            l += e.getShort(base + k * 4) * g[k]
                            r += e.getShort(base + k * 4 + 2) * g[k]
                        }
                        sai.putShort(l.coerceIn(-32768f, 32767f).toInt().toShort())
                        sai.putShort(r.coerceIn(-32768f, 32767f).toInt().toShort())
                    }
                    out.write(sai.array(), 0, q * 4)
                    pos += q
                    progresso((pos - de).toFloat() / (ate - de).coerceAtLeast(1))
                }
            }
            return taxa
        }
    }

    private fun recortar(todo: File, taxa: Int, iniMs: Long?, fimMs: Long?, saida: File) {
        val total = todo.length() / 4
        val de = ((iniMs ?: 0) * taxa / 1000).coerceIn(0, total)
        val ate = ((fimMs ?: Long.MAX_VALUE / taxa) * taxa / 1000).coerceIn(de, total)
        if (de == 0L && ate == total) { todo.copyTo(saida, true); return }
        RandomAccessFile(todo, "r").use { ra ->
            ra.seek(de * 4)
            FileOutputStream(saida).use { out ->
                val buf = ByteArray(1 shl 16)
                var falta = (ate - de) * 4
                while (falta > 0) {
                    val n = ra.read(buf, 0, minOf(buf.size.toLong(), falta).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n); falta -= n
                }
            }
        }
    }

    private suspend fun aplicarSonic(ent: File, taxa: Int, velocidade: Float, tom: Float, saida: File, progresso: (Float) -> Unit) {
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(velocidade)
        sonic.setPitch(tom)
        sonic.configure(AudioProcessor.AudioFormat(taxa, 2, C.ENCODING_PCM_16BIT))
        sonic.flush()
        val total = ent.length().coerceAtLeast(1)
        val tmp = ByteArray(1 shl 15)
        val direto = ByteBuffer.allocateDirect(tmp.size).order(ByteOrder.nativeOrder())
        BufferedOutputStream(FileOutputStream(saida), 1 shl 16).use { out ->
            fun escoar() {
                val o = sonic.output
                if (o.hasRemaining()) { val b = ByteArray(o.remaining()); o.get(b); out.write(b) }
            }
            ent.inputStream().buffered(1 shl 16).use { input ->
                var lido = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    var n = input.read(tmp)
                    if (n <= 0) break
                    n -= n % 4
                    direto.clear(); direto.put(tmp, 0, n); direto.flip()
                    sonic.queueInput(direto)
                    escoar()
                    lido += n
                    progresso(lido.toFloat() / total)
                }
            }
            sonic.queueEndOfStream()
            var voltas = 0
            while (!sonic.isEnded && voltas++ < 10_000) escoar()
        }
        sonic.reset()
    }

    /** Pasta Música/Begood Studio (aparece em qualquer player e no gerenciador de arquivos). */
    private suspend fun salvar(ctx: Context, nomeArq: String, gravar: suspend (() -> MediaMuxer) -> Unit): Resultado {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, nomeArq)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Begood Studio")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
                ?: throw MontadorMultipista.Problema("Não deu para criar o arquivo na pasta Música.")
            try {
                cr.openFileDescriptor(uri, "rw")!!.use { pfd ->
                    gravar { MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
                }
                cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                runCatching { cr.delete(uri, null, null) }
                throw e
            }
            return Resultado(listOf(uri), "Música/Begood Studio/$nomeArq")
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir, "Begood Studio").apply { mkdirs() }
        val arq = File(dir, nomeArq)
        gravar { MediaMuxer(arq.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".arquivos", arq)
        return Resultado(listOf(uri), arq.absolutePath)
    }
}
