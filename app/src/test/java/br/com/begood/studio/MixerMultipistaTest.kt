package br.com.begood.studio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import br.com.begood.studio.player.MixerMultipista
import br.com.begood.studio.player.MontadorMultipista
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MixerMultipistaTest {
    /** 3 faixas, 1000 quadros: faixa k vale (k+1)*1000 no L e -(k+1)*1000 no R. */
    private fun entrada(quadros: Int = 1000): ByteBuffer {
        val b = ByteBuffer.allocateDirect(quadros * 3 * 2 * 2).order(ByteOrder.nativeOrder())
        repeat(quadros) { for (k in 0 until 3) { b.putShort(((k + 1) * 1000).toShort()); b.putShort((-(k + 1) * 1000).toShort()) } }
        b.flip(); return b
    }

    private fun mixar(ganhos: FloatArray, blocos: Int = 2): Pair<Int, Int> {
        val m = MixerMultipista()
        m.faixas = 3; m.ganhos = ganhos
        m.configure(AudioProcessor.AudioFormat(44100, 6, C.ENCODING_PCM_16BIT))
        m.flush()
        var l = 0; var r = 0
        repeat(blocos) {
            m.queueInput(entrada())
            val out = m.output.order(ByteOrder.nativeOrder())
            while (out.remaining() >= 4) { l = out.short.toInt(); r = out.short.toInt() }
        }
        return l to r
    }

    @Test fun somaTodas() = assertEquals(6000 to -6000, mixar(floatArrayOf(1f, 1f, 1f)))
    @Test fun tiraUma() = assertEquals(4000 to -4000, mixar(floatArrayOf(1f, 0f, 1f)))
    @Test fun soUma() = assertEquals(3000 to -3000, mixar(floatArrayOf(0f, 0f, 1f)))
    @Test fun metadeDoVolume() = assertEquals(3000 to -3000, mixar(floatArrayOf(0.5f, 0.5f, 0.5f)))

    @Test fun saiEmEstereo() {
        val m = MixerMultipista(); m.faixas = 3
        val f = m.configure(AudioProcessor.AudioFormat(44100, 6, C.ENCODING_PCM_16BIT))
        assertEquals(2, f.channelCount); assertTrue(m.isActive)
    }

    @Test fun musicaNormalPassaDireto() {
        val m = MixerMultipista(); m.faixas = 0
        m.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        assertFalse(m.isActive)
    }

    @Test fun nomesDasFaixas() {
        assertEquals("Voz", MontadorMultipista.nomeDaFaixa("vocals.flac").first)
        assertEquals("Bateria", MontadorMultipista.nomeDaFaixa("Drums.mp3").first)
        assertEquals("Baixo", MontadorMultipista.nomeDaFaixa("bass.wav").first)
        assertEquals("Outros", MontadorMultipista.nomeDaFaixa("other.flac").first)
        assertEquals("Guitarra", MontadorMultipista.nomeDaFaixa("Guitarra.flac").first)
        assertEquals("Piano", MontadorMultipista.nomeDaFaixa("Keys.m4a").first)
    }
}
