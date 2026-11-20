package br.com.begood.studio.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Decodifica a música inteira e guarda o pico de cada pedacinho, para desenhar a forma de onda. */
object FormaDeOnda {
    private val cache = HashMap<String, FloatArray>()

    suspend fun calcular(ctx: Context, uri: Uri, colunas: Int = 1200): FloatArray? = withContext(Dispatchers.Default) {
        synchronized(cache) { cache[uri.toString()] }?.let { return@withContext it }
        val extrator = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extrator.setDataSource(ctx, uri, null)
            val faixa = (0 until extrator.trackCount).firstOrNull {
                extrator.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            extrator.selectTrack(faixa)
            val formato = extrator.getTrackFormat(faixa)
            val duracaoUs = if (formato.containsKey(MediaFormat.KEY_DURATION)) formato.getLong(MediaFormat.KEY_DURATION) else 0L
            if (duracaoUs <= 0) return@withContext null
            val c = MediaCodec.createDecoderByType(formato.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(formato, null, null, 0)
            c.start()

            val picos = FloatArray(colunas)
            val info = MediaCodec.BufferInfo()
            var fimEntrada = false
            var fimSaida = false
            var pcmFloat = false
            while (!fimSaida) {
                coroutineContext.ensureActive()
                if (!fimEntrada) {
                    val i = c.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = extrator.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fimEntrada = true
                        } else {
                            c.queueInputBuffer(i, 0, n, extrator.sampleTime, 0)
                            extrator.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 5000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        if (info.size > 0) {
                            val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            var pico = 0f
                            if (pcmFloat) {
                                val fb = buf.asFloatBuffer()
                                while (fb.hasRemaining()) { val v = abs(fb.get()); if (v > pico) pico = v }
                            } else {
                                val sb = buf.asShortBuffer()
                                while (sb.hasRemaining()) { val v = abs(sb.get().toInt()) / 32768f; if (v > pico) pico = v }
                            }
                            val col = ((info.presentationTimeUs.toDouble() / duracaoUs) * colunas).toInt().coerceIn(0, colunas - 1)
                            if (pico > picos[col]) picos[col] = pico
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fimSaida = true
                        c.releaseOutputBuffer(o, false)
                    }
                }
            }
            // Colunas que ficaram sem bloco (blocos maiores que a coluna) herdam a vizinha
            for (i in 1 until colunas) if (picos[i] == 0f) picos[i] = picos[i - 1] * 0.98f
            val maior = picos.maxOrNull()?.takeIf { it > 0 } ?: 1f
            for (i in picos.indices) picos[i] = picos[i] / maior
            synchronized(cache) { cache[uri.toString()] = picos }
            picos
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extrator.release()
        }
    }
}
