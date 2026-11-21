package br.com.begood.studio

import br.com.begood.studio.separador.Espectro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Confere o espectrograma contra os valores do HTDemucs (calculados no PC com numpy/torch). */
class EspectroTest {
    private val n = 343980
    private val l = FloatArray(n) { (0.5 * sin(2 * PI * 440 * it / 44100)).toFloat() }
    private val r = FloatArray(n) { (0.3 * cos(2 * PI * 1000 * it / 44100) + 0.05 * sin(it * 0.001)).toFloat() }

    @Test fun igualAoDemucs() {
        val le = Espectro.quadros(n)
        assertEquals(336, le)
        val z = FloatArray(4 * Espectro.BINS * le)
        Espectro().spec(l, r, n, z)
        fun v(c: Int, f: Int, t: Int) = z[((c * 2) * Espectro.BINS + f) * le + t] to z[((c * 2 + 1) * Espectro.BINS + f) * le + t]
        val esperado = listOf(
            Triple(0, 40, 0) to (5.103608395 to -0.445528635),
            Triple(0, 41, 100) to (7.703419681 to 1.792930310),
            Triple(1, 93, 5) to (1.189850634 to 4.604192984),
            Triple(1, 0, 335) to (-1.083861034 to 0.0),
            Triple(0, 2047, 200) to (0.0 to 0.0),
        )
        for ((pos, valor) in esperado) {
            val (re, im) = v(pos.first, pos.second, pos.third)
            assertEquals("re $pos", valor.first, re.toDouble(), 2e-4)
            assertEquals("im $pos", valor.second, im.toDouble(), 2e-4)
        }
    }

    @Test fun idaEVolta() {
        val le = Espectro.quadros(n)
        val z = FloatArray(4 * Espectro.BINS * le)
        val e = Espectro()
        e.spec(l, r, n, z)
        val yl = DoubleArray(n); val yr = DoubleArray(n)
        e.ispec({ c, f, t -> z[(c * Espectro.BINS + f) * le + t] }, le, n, yl, yr)
        var maior = 0.0
        for (k in 4096 until n - 4096) maior = maxOf(maior, abs(yl[k] - l[k]), abs(yr[k] - r[k]))
        assertTrue("erro $maior", maior < 1e-5)
    }
}
