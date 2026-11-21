package br.com.begood.studio.separador

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.coroutineContext

/** PCM 16 bits estéreo → AAC num arquivo .m4a (funciona em qualquer player e no WhatsApp). */
object CodificadorAac {
    suspend fun codificar(
        raw: File, taxa: Int, criarMuxer: () -> MediaMuxer, kbps: Int = 192, progresso: (Float) -> Unit = {},
    ) {
        val formato = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, taxa, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val total = raw.length().coerceAtLeast(1)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = criarMuxer()
        var faixa = -1
        try {
            codec.configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            FileInputStream(raw).buffered(1 shl 16).use { ent ->
                var fimEntrada = false
                var fim = false
                var quadros = 0L
                val tmp = ByteArray(16384)
                while (!fim) {
                    coroutineContext.ensureActive()
                    if (!fimEntrada) {
                        val i = codec.dequeueInputBuffer(5000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val max = minOf(tmp.size, buf.capacity()) / 4 * 4
                            var n = 0
                            while (n < max) { val r = ent.read(tmp, n, max - n); if (r <= 0) break; n += r }
                            val pts = quadros * 1_000_000L / taxa
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); fimEntrada = true
                            } else {
                                buf.clear(); buf.put(tmp, 0, n)
                                codec.queueInputBuffer(i, 0, n, pts, 0)
                                quadros += n / 4
                                progresso((quadros * 4f / total).coerceAtMost(1f))
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 5000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { faixa = muxer.addTrack(codec.outputFormat); muxer.start() }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && faixa >= 0) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                muxer.writeSampleData(faixa, buf, info)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fim = true
                            codec.releaseOutputBuffer(o, false)
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { if (faixa >= 0) muxer.stop() }; muxer.release()
        }
    }
}
