package br.com.begood.studio.separador

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** FFT complexa (radix 2, no lugar) de tamanho fixo. */
class Fft(val n: Int) {
    private val bits = Integer.numberOfTrailingZeros(n)
    private val inverte = IntArray(n) { Integer.reverse(it) ushr (32 - bits) }
    private val cosT = DoubleArray(n / 2) { cos(-2 * PI * it / n) }
    private val senT = DoubleArray(n / 2) { sin(-2 * PI * it / n) }

    init { require(n > 1 && n and (n - 1) == 0) }

    /** Transformada direta (sem escala). Para a inversa use [inversa]. */
    fun direta(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = inverte[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var tam = 2
        while (tam <= n) {
            val meio = tam / 2
            val passo = n / tam
            var ini = 0
            while (ini < n) {
                var k = 0
                for (j in ini until ini + meio) {
                    val wr = cosT[k]; val wi = senT[k]
                    val l = j + meio
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += passo
                }
                ini += tam
            }
            tam *= 2
        }
    }

    /** Transformada inversa, já dividida por n. */
    fun inversa(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) im[i] = -im[i]
        direta(re, im)
        val k = 1.0 / n
        for (i in 0 until n) { re[i] *= k; im[i] = -im[i] * k }
    }
}

/**
 * Espectrograma igual ao do HTDemucs (n_fft 4096, salto 1024, janela de Hann, "normalized"):
 * [spec] prepara a entrada do modelo e [ispec] volta para o som.
 * Os dois canais (L e R) são calculados juntos numa FFT só.
 */
class Espectro {
    companion object {
        const val NFFT = 4096
        const val SALTO = 1024
        const val BINS = NFFT / 2
        fun quadros(comprimento: Int) = (comprimento + SALTO - 1) / SALTO
    }

    private val fft = Fft(NFFT)
    private val janela = DoubleArray(NFFT) { 0.5 - 0.5 * cos(2 * PI * it / NFFT) }
    private val re = DoubleArray(NFFT)
    private val im = DoubleArray(NFFT)

    /** Espelha as pontas (como numpy/torch "reflect"). */
    private fun refletir(x: DoubleArray, n: Int, a: Int, b: Int): DoubleArray {
        val y = DoubleArray(n + a + b)
        System.arraycopy(x, 0, y, a, n)
        for (i in 1..a) y[a - i] = x[i]
        for (j in 1..b) y[a + n - 1 + j] = x[n - 1 - j]
        return y
    }

    /**
     * Entrada: [l] e [r] com [n] amostras. Saída em [saida]: 4 canais (L real, L imag, R real, R imag)
     * × 2048 faixas de frequência × [quadros] (n) momentos.
     */
    fun spec(l: FloatArray, r: FloatArray, n: Int, saida: FloatArray) {
        val le = quadros(n)
        val pad = SALTO / 2 * 3
        val dir = pad + le * SALTO - n
        fun preparar(x: FloatArray): DoubleArray {
            val d = DoubleArray(n) { x[it].toDouble() }
            return refletir(refletir(d, n, pad, dir), n + pad + dir, NFFT / 2, NFFT / 2)
        }
        val pl = preparar(l)
        val pr = preparar(r)
        val escala = 1.0 / 64.0 // 1/sqrt(4096)
        for (t in 0 until le) {
            val ini = (t + 2) * SALTO
            for (k in 0 until NFFT) {
                val w = janela[k]
                re[k] = pl[ini + k] * w
                im[k] = pr[ini + k] * w
            }
            fft.direta(re, im)
            for (f in 0 until BINS) {
                val a = re[f]; val b = im[f]
                val o = if (f == 0) 0 else NFFT - f
                val c = re[o]; val d = im[o]
                saida[(0 * BINS + f) * le + t] = ((a + c) * 0.5 * escala).toFloat()
                saida[(1 * BINS + f) * le + t] = ((b - d) * 0.5 * escala).toFloat()
                saida[(2 * BINS + f) * le + t] = ((b + d) * 0.5 * escala).toFloat()
                saida[(3 * BINS + f) * le + t] = (-(a - c) * 0.5 * escala).toFloat()
            }
        }
    }

    /**
     * Volta para o som uma fonte da saída do modelo. [ler](canal, f, t) devolve o valor do espectrograma
     * (canal 0..3 = L real, L imag, R real, R imag), com [le] momentos. Escreve [n] amostras em [l] e [r].
     */
    fun ispec(ler: (Int, Int, Int) -> Float, le: Int, n: Int, l: DoubleArray, r: DoubleArray) {
        val quadrosTotal = le + 4
        val total = NFFT + SALTO * (quadrosTotal - 1)
        val yl = DoubleArray(total)
        val yr = DoubleArray(total)
        val env = DoubleArray(total)
        for (t in 0 until quadrosTotal) {
            val ini = t * SALTO
            for (k in 0 until NFFT) env[ini + k] += janela[k] * janela[k]
        }
        for (t in 0 until le) {
            // Y = XL + i·XR (os dois espectros "hermitianos" juntos)
            re[0] = ler(0, 0, t).toDouble(); im[0] = ler(2, 0, t).toDouble()
            re[BINS] = 0.0; im[BINS] = 0.0
            for (f in 1 until BINS) {
                val p = ler(0, f, t).toDouble(); val q = ler(1, f, t).toDouble()
                val rr = ler(2, f, t).toDouble(); val s = ler(3, f, t).toDouble()
                re[f] = p - s; im[f] = q + rr
                re[NFFT - f] = p + s; im[NFFT - f] = rr - q
            }
            fft.inversa(re, im)
            val ini = (t + 2) * SALTO
            for (k in 0 until NFFT) {
                val w = janela[k] * 64.0 // * sqrt(4096)
                yl[ini + k] += re[k] * w
                yr[ini + k] += im[k] * w
            }
        }
        val desloc = NFFT / 2 + SALTO / 2 * 3
        for (k in 0 until n) {
            val e = env[desloc + k]
            l[k] = yl[desloc + k] / e
            r[k] = yr[desloc + k] / e
        }
    }
}
