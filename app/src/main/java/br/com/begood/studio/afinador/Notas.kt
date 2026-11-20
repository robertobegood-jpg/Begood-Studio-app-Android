package br.com.begood.studio.afinador

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Nomes das notas, conversão frequência ⇄ nota e afinações prontas. Notas em número MIDI (Lá 4 = 69). */
object Notas {
    private val LETRAS = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    private val SOLFEJO = arrayOf("Dó", "Dó♯", "Ré", "Ré♯", "Mi", "Fá", "Fá♯", "Sol", "Sol♯", "Lá", "Lá♯", "Si")

    /** Nota "contínua": 69,0 = Lá exato; 69,5 = meio caminho para o Lá♯. */
    fun midiReal(freq: Double, la4: Double): Double = 69 + 12 * log2(freq / la4)

    fun freq(midi: Int, la4: Double): Double = la4 * 2.0.pow((midi - 69) / 12.0)

    fun maisProxima(freq: Double, la4: Double): Int = midiReal(freq, la4).roundToInt()

    /** Diferença em cents entre a frequência e a nota [midi] (positivo = agudo/alto demais). */
    fun cents(freq: Double, midi: Int, la4: Double): Double = (midiReal(freq, la4) - midi) * 100

    fun nome(midi: Int, solfejo: Boolean): String = (if (solfejo) SOLFEJO else LETRAS)[Math.floorMod(midi, 12)]

    fun oitava(midi: Int): Int = Math.floorDiv(midi, 12) - 1

    fun nomeCompleto(midi: Int, solfejo: Boolean): String = nome(midi, solfejo) + oitava(midi)
}

data class Afinacao(val nome: String, val notas: List<Int>) {
    val cromatica get() = notas.isEmpty()
}

val AFINACOES = listOf(
    Afinacao("Cromático (todas as notas)", emptyList()),
    Afinacao("Violão / Guitarra – padrão (E A D G B E)", listOf(40, 45, 50, 55, 59, 64)),
    Afinacao("Violão / Guitarra – meio tom abaixo", listOf(39, 44, 49, 54, 58, 63)),
    Afinacao("Violão / Guitarra – um tom abaixo", listOf(38, 43, 48, 53, 57, 62)),
    Afinacao("Drop D (D A D G B E)", listOf(38, 45, 50, 55, 59, 64)),
    Afinacao("Open G (D G D G B D)", listOf(38, 43, 50, 55, 59, 62)),
    Afinacao("Open D (D A D F♯ A D)", listOf(38, 45, 50, 54, 57, 62)),
    Afinacao("DADGAD", listOf(38, 45, 50, 55, 57, 62)),
    Afinacao("Violão 7 cordas – em Dó (C E A D G B E)", listOf(36, 40, 45, 50, 55, 59, 64)),
    Afinacao("Violão 7 cordas – em Si (B E A D G B E)", listOf(35, 40, 45, 50, 55, 59, 64)),
    Afinacao("Baixo 4 cordas (E A D G)", listOf(28, 33, 38, 43)),
    Afinacao("Baixo 5 cordas (B E A D G)", listOf(23, 28, 33, 38, 43)),
    Afinacao("Cavaquinho (D G B D)", listOf(62, 67, 71, 74)),
    Afinacao("Ukulele (G C E A)", listOf(67, 60, 64, 69)),
    Afinacao("Violino / Bandolim (G D A E)", listOf(55, 62, 69, 76)),
    Afinacao("Viola de arco (C G D A)", listOf(48, 55, 62, 69)),
    Afinacao("Violoncelo (C G D A)", listOf(36, 43, 50, 57)),
)
