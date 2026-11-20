package br.com.begood.studio.afinador

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Descobre a frequência fundamental de um trecho de áudio.
 *
 * Usa o método de McLeod (MPM / NSDF): a autocorrelação normalizada é calculada com FFT
 * (rápida mesmo em celulares simples), o primeiro pico "forte" dá o período e uma interpolação
 * parabólica acha a posição exata entre duas amostras. Para ganhar ainda mais precisão, o período
 * é medido de novo num múltiplo dele (2, 3, 4... períodos adiante), o que divide o erro.
 *
 * Não depende do Android: é testado direto no PC (DetectorTomTest).
 */
class DetectorTom(
    val taxa: Int,
    val tamanho: Int = 4096,
    freqMin: Double = 27.0,
    freqMax: Double = 4200.0,
) {
    data class Resultado(val freq: Double, val clareza: Double, val nivelDb: Double)

    private val nFft: Int = run { var n = 1; while (n < tamanho * 2) n = n shl 1; n }
    private val re = DoubleArray(nFft)
    private val im = DoubleArray(nFft)
    private val nsdf = DoubleArray(tamanho)
    private val cosT = DoubleArray(nFft / 2) { cos(2 * PI * it / nFft) }
    private val sinT = DoubleArray(nFft / 2) { sin(2 * PI * it / nFft) }
    private val reverso = IntArray(nFft).also { r ->
        val bits = Integer.numberOfTrailingZeros(nFft)
        for (i in 0 until nFft) r[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val lagMin = (taxa / freqMax).toInt().coerceAtLeast(2)
    private val lagMax = min((taxa / freqMin).toInt(), tamanho / 2)

    /** Pico com clareza mínima aceita (0..1). Abaixo disso o som é considerado ruído. */
    var clarezaMinima = 0.80

    /** Ganho do primeiro pico em relação ao maior (padrão do método de McLeod). */
    private val k = 0.93

    /** Analisa [x] (pelo menos [tamanho] amostras, entre -1 e 1). Retorna null se não houver nota clara. */
    fun analisar(x: FloatArray, inicio: Int = 0, nivelMinimoDb: Double = -60.0): Resultado? {
        val n = tamanho
        // Nível (RMS) e remoção da componente contínua
        var media = 0.0
        for (i in 0 until n) media += x[inicio + i]
        media /= n
        var energia = 0.0
        for (i in 0 until n) {
            val v = x[inicio + i] - media
            re[i] = v; im[i] = 0.0
            energia += v * v
        }
        val rms = sqrt(energia / n)
        val nivelDb = if (rms > 0) 20 * log10(rms) else -120.0
        if (nivelDb < nivelMinimoDb) return null
        for (i in n until nFft) { re[i] = 0.0; im[i] = 0.0 }

        // Autocorrelação r(τ) = IFFT(|FFT(x)|²)
        fft(re, im, false)
        for (i in 0 until nFft) { re[i] = re[i] * re[i] + im[i] * im[i]; im[i] = 0.0 }
        fft(re, im, true) // re agora tem r(τ) * nFft

        // NSDF: n(τ) = 2 r(τ) / m(τ), m(τ) = Σ x[j]² + x[j+τ]²
        val escala = 1.0 / nFft
        var m = 2 * energia
        for (tau in 0 until n) {
            nsdf[tau] = if (m > 1e-12) 2 * re[tau] * escala / m else 0.0
            val a = x[inicio + tau] - media
            val b = x[inicio + n - 1 - tau] - media
            m -= a * a + b * b
        }

        // Picos: o maior valor de cada trecho positivo (depois do primeiro cruzamento por zero)
        val picos = IntArray(64)
        var qtd = 0
        var tau = 1
        while (tau < lagMax && nsdf[tau] > 0) tau++
        var maxPos = -1
        while (tau < lagMax && qtd < picos.size) {
            if (nsdf[tau] > 0) {
                if (maxPos < 0 || nsdf[tau] > nsdf[maxPos]) maxPos = tau
            } else if (maxPos >= 0) {
                picos[qtd++] = maxPos; maxPos = -1
            }
            tau++
        }
        if (maxPos >= 0 && qtd < picos.size && maxPos < lagMax - 1) picos[qtd++] = maxPos
        if (qtd == 0) return null

        var maior = 0.0
        for (i in 0 until qtd) if (nsdf[picos[i]] > maior) maior = nsdf[picos[i]]
        val limite = k * maior
        var escolhido = -1
        for (i in 0 until qtd) if (picos[i] >= lagMin && nsdf[picos[i]] >= limite) { escolhido = picos[i]; break }
        if (escolhido < 1) return null

        val (periodo, clareza) = interpolar(escolhido)
        if (clareza < clarezaMinima) return null

        // Refinamento: mede o pico de vários períodos adiante (erro da interpolação fica dividido).
        var periodoFinal = periodo
        val multiplo = min(8, floor((n / 3.0) / periodo).toInt())
        if (multiplo >= 2) {
            val alvo = (periodo * multiplo).roundToInt()
            var melhor = -1
            for (t in (alvo - 2).coerceAtLeast(2)..(alvo + 2).coerceAtMost(n - 2)) {
                if (melhor < 0 || nsdf[t] > nsdf[melhor]) melhor = t
            }
            if (melhor in 2 until n - 1 && nsdf[melhor] > nsdf[melhor - 1] && nsdf[melhor] >= nsdf[melhor + 1]) {
                val (p2, c2) = interpolar(melhor)
                val candidato = p2 / multiplo
                // Só aceita se o pico distante for confiável e concordar com o primeiro (± 1%).
                if (c2 > 0.5 * clareza && kotlin.math.abs(candidato - periodo) < periodo * 0.01) periodoFinal = candidato
            }
        }
        return Resultado(taxa / periodoFinal, clareza.coerceAtMost(1.0), nivelDb)
    }

    private fun interpolar(t: Int): Pair<Double, Double> {
        val a = nsdf[t - 1]; val b = nsdf[t]; val c = nsdf[t + 1]
        val den = a - 2 * b + c
        if (den == 0.0) return t.toDouble() to b
        val d = (0.5 * (a - c) / den).coerceIn(-1.0, 1.0)
        return (t + d) to (b - 0.25 * (a - c) * d)
    }

    /** FFT complexa in-place (raiz 2). [inversa] não divide por n (o chamador compensa). */
    private fun fft(re: DoubleArray, im: DoubleArray, inversa: Boolean) {
        val n = nFft
        for (i in 0 until n) {
            val j = reverso[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var tam = 2
        while (tam <= n) {
            val meio = tam / 2
            val passo = n / tam
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + meio) {
                    val wr = cosT[k]
                    val wi = if (inversa) sinT[k] else -sinT[k]
                    val xr = re[j + meio] * wr - im[j + meio] * wi
                    val xi = re[j + meio] * wi + im[j + meio] * wr
                    re[j + meio] = re[j] - xr; im[j + meio] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += passo
                }
                i += tam
            }
            tam = tam shl 1
        }
    }
}

/**
 * Junta dois detectores: um com janela curta (85 ms, resposta rápida) e, para notas graves
 * (baixo, violão 7 cordas), um com janela longa (340 ms), que mede com muito mais precisão
 * quando há poucos ciclos da onda na janela curta.
 */
class Analisador(val taxa: Int) {
    val curto = DetectorTom(taxa, 4096)
    val longo = DetectorTom(taxa, 16384, freqMin = 27.0, freqMax = 500.0)
    /** Quantas amostras recentes [analisar] precisa receber. */
    val tamanho = longo.tamanho

    var clarezaMinima: Double
        get() = curto.clarezaMinima
        set(v) { curto.clarezaMinima = v; longo.clarezaMinima = v }

    /** [x] tem [tamanho] amostras, a mais recente no fim. */
    fun analisar(x: FloatArray, nivelMinimoDb: Double = -60.0): DetectorTom.Resultado? {
        val r = curto.analisar(x, x.size - curto.tamanho, nivelMinimoDb)
        if (r != null && r.freq > 160) return r
        val l = longo.analisar(x, x.size - longo.tamanho, nivelMinimoDb) ?: return r
        if (l.freq > 170) return r
        if (r == null) return l
        // As duas janelas precisam concordar (senão a nota acabou de mudar: vale a curta).
        val diferenca = kotlin.math.abs(1200 * kotlin.math.log2(l.freq / r.freq))
        return if (diferenca < 30) l else r
    }
}
