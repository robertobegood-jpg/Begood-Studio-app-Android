package br.com.begood.studio.afinador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class DetectorTomTest {
    private val taxa = 48000
    private val det = DetectorTom(taxa)

    private fun gerar(freq: Double, harmonicos: Int = 1, ruido: Double = 0.0, fase: Double = 0.3, seed: Int = 1): FloatArray {
        val r = Random(seed)
        return FloatArray(det.tamanho) { i ->
            var v = 0.0
            for (h in 1..harmonicos) v += sin(2 * PI * freq * h * i / taxa + fase * h) / h
            (0.4 * v + ruido * (r.nextDouble() * 2 - 1)).toFloat()
        }
    }

    private fun gerarLongo(freq: Double, harmonicos: Int, ruido: Double, seed: Int): FloatArray {
        val r = Random(seed)
        return FloatArray(16384) { i ->
            var v = 0.0
            for (h in 1..harmonicos) v += sin(2 * PI * freq * h * i / taxa + 0.7 * h) / h
            (0.4 * v + ruido * (r.nextDouble() * 2 - 1)).toFloat()
        }
    }

    private fun erroCents(medida: Double, real: Double) = 1200 * log2(medida / real)

    @Test
    fun senoidesPurasTemErroMenorQueUmDecimoDeCent() {
        var pior = 0.0
        val r = Random(7)
        for (midi in 28..96) { // de E1 (41 Hz) a C7 (2093 Hz)
            val f = 440.0 * 2.0.pow((midi - 69) / 12.0) * 2.0.pow(r.nextDouble(-0.5, 0.5) / 12)
            val res = det.analisar(gerar(f, fase = r.nextDouble(0.0, 6.0)))
            assertNotNull("sem leitura em $f Hz", res)
            pior = maxOf(pior, abs(erroCents(res!!.freq, f)))
        }
        println("Pior erro (senoides): %.4f cents".format(pior))
        assertTrue("erro $pior cents", pior < 0.1)
    }

    @Test
    fun somComHarmonicosERuidoAcertaANotaENaoPulaOitava() {
        var pior = 0.0
        for (midi in listOf(23, 28, 33, 40, 45, 50, 55, 59, 64, 69, 76, 88)) { // baixo 5 cordas até agudos
            val f = 440.0 * 2.0.pow((midi - 69) / 12.0) * 1.0017
            val res = Analisador(taxa).analisar(gerarLongo(f, harmonicos = 8, ruido = 0.05, seed = midi))
            assertNotNull("sem leitura em $f Hz", res)
            assertEquals("nota errada em $f Hz", midi, Notas.maisProxima(res!!.freq, 440.0))
            // Abaixo de 45 Hz o ruído pesa mais numa leitura só (no app a leitura é suavizada).
            val tolerancia = if (midi < 30) 1.5 else 0.5
            val erro = abs(erroCents(res.freq, f))
            assertTrue("midi $midi: erro $erro cents", erro < tolerancia)
            pior = maxOf(pior, erro)
        }
        println("Pior erro (harmônicos + ruído): %.4f cents".format(pior))
    }

    @Test
    fun graveComRuidoMedioDeVariasLeiturasFicaPreciso() {
        val f = 30.8677 * 1.0017 // Si 0 (5ª corda do baixo)
        val a = Analisador(taxa)
        val medidas = (1..12).mapNotNull { a.analisar(gerarLongo(f, 8, 0.05, it))?.freq }
        val media = medidas.sorted().subList(2, medidas.size - 2).average()
        println("Si 0 (média de leituras): %.4f cents".format(erroCents(media, f)))
        assertTrue(abs(erroCents(media, f)) < 0.5)
    }

    @Test
    fun cordaDecaindoComoViolao() {
        val f = 110.0 * 2.0.pow(3.0 / 1200) // Lá 2 três cents acima
        val x = FloatArray(det.tamanho) { i ->
            val t = i.toDouble() / taxa
            var v = 0.0
            for (h in 1..10) v += sin(2 * PI * f * h * t) * exp(-t * (2.0 + h)) / h
            (0.5 * v).toFloat()
        }
        val res = det.analisar(x)!!
        assertTrue(abs(erroCents(res.freq, f)) < 0.2)
        assertEquals(3.0, Notas.cents(res.freq, 45, 440.0), 0.2)
    }

    @Test
    fun silencioERuidoNaoDaoNota() {
        assertNull(det.analisar(FloatArray(det.tamanho)))
        val r = Random(3)
        assertNull(det.analisar(FloatArray(det.tamanho) { (r.nextDouble() * 2 - 1).toFloat() * 0.3f }))
    }

    @Test
    fun nomesDasNotas() {
        assertEquals("A4", Notas.nomeCompleto(69, false))
        assertEquals("Mi2", Notas.nomeCompleto(40, true))
        assertEquals("C♯-1", Notas.nomeCompleto(1, false))
        assertEquals(432.0, Notas.freq(69, 432.0), 1e-9)
        assertEquals(0.0, Notas.cents(82.4069, 40, 440.0), 0.01)
    }
}
