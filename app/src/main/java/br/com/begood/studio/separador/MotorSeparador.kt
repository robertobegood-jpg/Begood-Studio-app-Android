package br.com.begood.studio.separador

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaMuxer
import android.net.Uri
import br.com.begood.studio.player.MontadorMultipista
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Separa uma música em 6 faixas com o HTDemucs (Meta), tudo no celular:
 * decodifica → normaliza → corta em trechos de 7,8 s que se sobrepõem → modelo (ONNX Runtime)
 * → junta os trechos com transição suave → salva cada instrumento em .m4a.
 * É o mesmo cálculo do Demucs do PC (conferido número a número).
 */
class MotorSeparador(private val ctx: Context) {
    companion object {
        const val TAXA = 44100
        /** Tamanho do trecho que o modelo recebe: 7,8 s. */
        const val L = 343980
        /** Ordem das fontes que o modelo devolve. */
        val FONTES = listOf("Bateria", "Baixo", "Outros", "Voz", "Guitarra", "Piano")
        private const val MODELO = "htdemucs_6s"

        /** Pasta onde ficam as faixas separadas pelo app. */
        fun pastaFaixas(ctx: Context): File =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Faixas separadas").apply { mkdirs() }

        fun pastaDaMusica(ctx: Context, nome: String): File =
            File(pastaFaixas(ctx), nome.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "Música" })

        /** Já separada antes (todas as faixas prontas)? */
        fun jaSeparada(ctx: Context, nome: String): File? =
            pastaDaMusica(ctx, nome).takeIf { d -> FONTES.all { File(d, "$it.m4a").length() > 0 } }
    }

    /**
     * Na primeira vez, monta o modelo na memória do celular: os pesos vêm no app pela metade do tamanho
     * (16 bits) e aqui viram 32 bits de novo (o ONNX Runtime calcula em 32 bits).
     */
    suspend fun prepararModelo(progresso: (Float) -> Unit): File {
        val dir = File(ctx.filesDir, "separador").apply { mkdirs() }
        val grafo = File(dir, "$MODELO.onnx")
        val pesos = File(dir, "$MODELO.pesos")
        val marca = File(dir, "versao")
        val versao = "2:" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        if (grafo.exists() && pesos.exists() && marca.exists() && marca.readText() == versao) return grafo
        dir.listFiles()?.forEach { it.delete() }
        ctx.assets.open("separador/$MODELO.onnx").use { e -> FileOutputStream(grafo).use { e.copyTo(it) } }
        val total = runCatching { ctx.assets.openFd("separador/$MODELO.pesos16").use { it.length } }.getOrNull()?.takeIf { it > 0 } ?: 54_824_448L
        DataInputStream(BufferedInputStream(ctx.assets.open("separador/$MODELO.pesos16"), 1 shl 16)).use { e ->
            BufferedOutputStream(FileOutputStream(pesos), 1 shl 17).use { s ->
                val ent = ByteArray(1 shl 16)
                val sai = ByteBuffer.allocate(ent.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                var lido = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    var n = e.read(ent)
                    if (n <= 0) break
                    if (n % 2 == 1) { e.readFully(ent, n, 1); n++ }
                    sai.clear()
                    var i = 0
                    while (i < n) {
                        val h = (ent[i].toInt() and 0xFF) or ((ent[i + 1].toInt() and 0xFF) shl 8)
                        sai.putFloat(meioParaFloat(h))
                        i += 2
                    }
                    s.write(sai.array(), 0, n * 2)
                    lido += n
                    progresso((lido.toFloat() / total).coerceAtMost(1f))
                }
            }
        }
        marca.writeText(versao)
        return grafo
    }

    private fun meioParaFloat(h: Int): Float {
        val sinal = (h ushr 15) and 1
        val exp = (h ushr 10) and 0x1F
        val mant = h and 0x3FF
        val bits = when {
            exp == 0 && mant == 0 -> sinal shl 31
            exp == 0 -> { // subnormal
                var e = -1; var m = mant
                while (m and 0x400 == 0) { m = m shl 1; e++ }
                (sinal shl 31) or ((127 - 15 - e) shl 23) or ((m and 0x3FF) shl 13)
            }
            exp == 31 -> (sinal shl 31) or (0xFF shl 23) or (mant shl 13)
            else -> (sinal shl 31) or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }

    class Etapa(val texto: String, val fracao: Float)

    /**
     * Separa [musica] e grava as faixas em [destino]. [progresso] recebe o que está fazendo e quanto falta
     * (0..1 do total). Pode ser cancelado (corrotina).
     */
    suspend fun separar(musica: Uri, destino: File, progresso: (Etapa) -> Unit) {
        val trabalho = File(ctx.cacheDir, "separando").apply { deleteRecursively(); mkdirs() }
        try {
            // 1) Abre a música (e passa para 44,1 kHz se precisar)
            progresso(Etapa("Lendo a música…", 0f))
            val bruto = File(trabalho, "entrada.raw")
            val taxa = MontadorMultipista.decodificarPcm16(ctx, musica, "música", bruto) { f -> progresso(Etapa("Lendo a música…", f * 0.03f)) }
            val entrada = if (taxa == TAXA) bruto else File(trabalho, "entrada44.raw").also {
                reamostrar(bruto, taxa, it) { f -> progresso(Etapa("Ajustando a qualidade do som…", 0.03f + f * 0.02f)) }
                bruto.delete()
            }
            val quadros = entrada.length() / 4
            if (quadros < TAXA) throw MontadorMultipista.Problema("A música é curta demais para separar.")

            // 2) Prepara o modelo
            progresso(Etapa("Preparando o separador…", 0.05f))
            val grafo = prepararModelo { f -> progresso(Etapa("Preparando o separador (só na primeira vez)…", 0.05f + f * 0.03f)) }

            // 3) Separa
            val saidas = FONTES.map { File(trabalho, "$it.raw") }
            OrtEnvironment.getEnvironment().use { env ->
                OrtSession.SessionOptions().use { op ->
                    // Sem otimizações de grafo e sem reservar memória à toa: cabe em celulares comuns
                    // (no PC: ~1,4 GB no pico em vez de ~4,5 GB)
                    op.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    op.setMemoryPatternOptimization(false)
                    op.setCPUArenaAllocator(false)
                    env.createSession(grafo.absolutePath, op).use { sessao ->
                        separarTrechos(env, sessao, entrada, quadros, saidas) { f, falta ->
                            progresso(Etapa("Separando os instrumentos…" + falta, 0.08f + f * 0.87f))
                        }
                    }
                }
            }

            // 4) Salva cada instrumento em .m4a
            val temp = File(destino.parentFile, destino.name + ".tmp").apply { deleteRecursively(); mkdirs() }
            saidas.forEachIndexed { i, raw ->
                progresso(Etapa("Salvando ${FONTES[i]}…", 0.95f + 0.05f * i / saidas.size))
                codificarM4a(raw, File(temp, "${FONTES[i]}.m4a"))
                raw.delete()
            }
            destino.deleteRecursively()
            if (!temp.renameTo(destino)) { temp.copyRecursively(destino, true); temp.deleteRecursively() }
            progresso(Etapa("Pronto", 1f))
        } finally {
            trabalho.deleteRecursively()
        }
    }

    private suspend fun separarTrechos(
        env: OrtEnvironment, sessao: OrtSession, entrada: File, quadros: Long, saidas: List<File>,
        progresso: (Float, String) -> Unit,
    ) {
        val total = quadros
        // Normalização igual ao Demucs: média e desvio do mono da música inteira
        var soma = 0.0; var soma2 = 0.0
        lerQuadros(entrada, 0, total) { l, r -> val m = (l + r) * 0.5; soma += m; soma2 += m * m }
        val media = soma / total
        val desvio = sqrt(((soma2 - soma * soma / total) / (total - 1)).coerceAtLeast(1e-12))

        val esp = Espectro()
        val le = Espectro.quadros(L)
        val nFontes = FONTES.size
        val passo = (0.75 * L).toInt()
        val peso = FloatArray(L) { i -> (if (i < L / 2) i + 1 else L - i).toFloat() / (L / 2) }
        // Buffers fora da memória do Java (o modelo escreve direto neles)
        fun direto(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val bufMix = direto(2 * L)
        val bufMag = direto(4 * Espectro.BINS * le)
        val bufSpec = direto(nFontes * 4 * Espectro.BINS * le)
        val bufWave = direto(nFontes * 2 * L)
        val tMix = OnnxTensor.createTensor(env, bufMix, longArrayOf(1, 2, L.toLong()))
        val tMag = OnnxTensor.createTensor(env, bufMag, longArrayOf(1, 4, Espectro.BINS.toLong(), le.toLong()))
        val tSpec = OnnxTensor.createTensor(env, bufSpec, longArrayOf(1, nFontes.toLong(), 4, Espectro.BINS.toLong(), le.toLong()))
        val tWave = OnnxTensor.createTensor(env, bufWave, longArrayOf(1, nFontes.toLong(), 2, L.toLong()))

        val tl = FloatArray(L); val tr = FloatArray(L)
        val mag = FloatArray(4 * Espectro.BINS * le)
        val yl = DoubleArray(L); val yr = DoubleArray(L)
        // Acumuladores do trecho atual [ini, ini + L)
        val acc = Array(nFontes) { Array(2) { FloatArray(L) } }
        val somaPeso = FloatArray(L)
        var ini = 0L
        val outs = saidas.map { BufferedOutputStream(FileOutputStream(it), 1 shl 16) }
        val blocoSaida = ByteArray(8192 * 4)
        val inicio = System.currentTimeMillis()
        val nTrechos = ((total + passo - 1) / passo).toInt()

        /** Grava as amostras prontas [ini, ate) e anda os acumuladores. */
        fun gravar(ate: Long) {
            val n = (ate - ini).toInt()
            for (s in 0 until nFontes) {
                val a0 = acc[s][0]; val a1 = acc[s][1]
                var k = 0
                while (k < n) {
                    val q = minOf(8192, n - k)
                    val bb = ByteBuffer.wrap(blocoSaida).order(ByteOrder.LITTLE_ENDIAN)
                    for (j in k until k + q) {
                        val w = somaPeso[j].takeIf { it > 0f } ?: 1f
                        val vl = (a0[j] / w * desvio + media) * 32767.0
                        val vr = (a1[j] / w * desvio + media) * 32767.0
                        bb.putShort(vl.coerceIn(-32768.0, 32767.0).toInt().toShort())
                        bb.putShort(vr.coerceIn(-32768.0, 32767.0).toInt().toShort())
                    }
                    outs[s].write(blocoSaida, 0, q * 4)
                    k += q
                }
                // Anda a janela
                System.arraycopy(a0, n, a0, 0, L - n); java.util.Arrays.fill(a0, L - n, L, 0f)
                System.arraycopy(a1, n, a1, 0, L - n); java.util.Arrays.fill(a1, L - n, L, 0f)
            }
            System.arraycopy(somaPeso, n, somaPeso, 0, L - n); java.util.Arrays.fill(somaPeso, L - n, L, 0f)
            ini = ate
        }

        try {
            var off = 0L
            var feitos = 0
            while (off < total) {
                coroutineContext.ensureActive()
                val comp = minOf(L.toLong(), total - off).toInt()
                val delta = L - comp
                val ini0 = off - delta / 2
                java.util.Arrays.fill(tl, 0f); java.util.Arrays.fill(tr, 0f)
                val a = maxOf(0L, ini0); val b = minOf(total, ini0 + L)
                var idx = (a - ini0).toInt()
                lerQuadros(entrada, a, b - a) { l, r ->
                    tl[idx] = ((l - media) / desvio).toFloat(); tr[idx] = ((r - media) / desvio).toFloat(); idx++
                }
                esp.spec(tl, tr, L, mag)
                bufMix.clear(); bufMix.put(tl); bufMix.put(tr); bufMix.rewind()
                bufMag.clear(); bufMag.put(mag); bufMag.rewind()
                sessao.run(mapOf("mix" to tMix, "mag" to tMag), mapOf("spec" to tSpec, "wave" to tWave)).close()

                val base = (off - ini).toInt() // posição do trecho dentro dos acumuladores
                val c0 = delta / 2
                for (s in 0 until nFontes) {
                    val ofsSpec = s * 4 * Espectro.BINS * le
                    esp.ispec({ c, f, t -> bufSpec.get(ofsSpec + (c * Espectro.BINS + f) * le + t) }, le, L, yl, yr)
                    val ofsW = s * 2 * L
                    val a0 = acc[s][0]; val a1 = acc[s][1]
                    for (k in 0 until comp) {
                        val p = peso[k]
                        val j = c0 + k
                        a0[base + k] += p * (yl[j] + bufWave.get(ofsW + j)).toFloat()
                        a1[base + k] += p * (yr[j] + bufWave.get(ofsW + L + j)).toFloat()
                    }
                }
                for (k in 0 until comp) somaPeso[base + k] += peso[k]
                feitos++
                val prox = off + passo
                gravar(if (prox >= total) total else prox)
                off = prox

                val f = feitos.toFloat() / nTrechos
                val gasto = System.currentTimeMillis() - inicio
                val faltaS = (gasto / f * (1 - f) / 1000).toLong()
                progresso(f, if (feitos >= 1 && faltaS > 0) " faltam ${tempoTexto(faltaS)}" else "")
            }
        } finally {
            outs.forEach { runCatching { it.close() } }
            tMix.close(); tMag.close(); tSpec.close(); tWave.close()
        }
    }

    private fun tempoTexto(s: Long) = if (s >= 60) "${(s + 30) / 60} min" else "$s s"

    /** Lê quadros (L, R em -1..1) do PCM 16 bits. */
    private inline fun lerQuadros(arq: File, de: Long, qtd: Long, f: (Double, Double) -> Unit) {
        RandomAccessFile(arq, "r").use { ra ->
            ra.seek(de * 4)
            val buf = ByteArray(1 shl 16)
            var falta = qtd * 4
            while (falta > 0) {
                val n = ra.read(buf, 0, minOf(buf.size.toLong(), falta).toInt())
                if (n <= 0) break
                var i = 0
                while (i + 3 < n) {
                    val l = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort() / 32768.0
                    val r = ((buf[i + 3].toInt() shl 8) or (buf[i + 2].toInt() and 0xFF)).toShort() / 32768.0
                    f(l, r)
                    i += 4
                }
                falta -= n
            }
        }
    }

    /** Converte a taxa de amostragem (ex.: 48 kHz → 44,1 kHz) com filtro sinc janelado. */
    private suspend fun reamostrar(ent: File, taxaEnt: Int, sai: File, progresso: (Float) -> Unit) {
        val quadrosEnt = ent.length() / 4
        val razao = taxaEnt.toDouble() / TAXA
        val quadrosSai = (quadrosEnt / razao).toLong()
        val z = 16
        val corte = minOf(1.0, 1.0 / razao) * 0.97
        val bloco = 8192
        BufferedOutputStream(FileOutputStream(sai), 1 shl 16).use { out ->
            RandomAccessFile(ent, "r").use { ra ->
                val bb = ByteBuffer.allocate(bloco * 4).order(ByteOrder.LITTLE_ENDIAN)
                var j0 = 0L
                while (j0 < quadrosSai) {
                    coroutineContext.ensureActive()
                    val q = minOf(bloco.toLong(), quadrosSai - j0).toInt()
                    val iMin = maxOf(0L, floor(j0 * razao).toLong() - z - 1)
                    val iMax = minOf(quadrosEnt, floor((j0 + q) * razao).toLong() + z + 2)
                    val n = (iMax - iMin).toInt()
                    val raw = ByteArray(n * 4)
                    ra.seek(iMin * 4); ra.readFully(raw)
                    val ib = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    val xl = FloatArray(n); val xr = FloatArray(n)
                    for (i in 0 until n) { xl[i] = ib.short.toFloat(); xr[i] = ib.short.toFloat() }
                    bb.clear()
                    for (k in 0 until q) {
                        val t = (j0 + k) * razao
                        val c = floor(t).toLong()
                        var sl = 0.0; var sr = 0.0
                        for (i in c - z + 1..c + z) {
                            val p = (i - iMin).toInt()
                            if (p < 0 || p >= n) continue
                            val d = t - i
                            val x = PI * d * corte
                            val sinc = if (kotlin.math.abs(d) < 1e-9) corte else corte * sin(x) / x
                            val w = 0.5 + 0.5 * cos(PI * d / z)
                            val h = sinc * w
                            sl += xl[p] * h; sr += xr[p] * h
                        }
                        bb.putShort(sl.coerceIn(-32768.0, 32767.0).toInt().toShort())
                        bb.putShort(sr.coerceIn(-32768.0, 32767.0).toInt().toShort())
                    }
                    out.write(bb.array(), 0, q * 4)
                    j0 += q
                    progresso(j0.toFloat() / quadrosSai)
                }
            }
        }
    }

    private suspend fun codificarM4a(raw: File, destino: File) =
        CodificadorAac.codificar(raw, TAXA, { MediaMuxer(destino.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) })
}
