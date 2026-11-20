package br.com.begood.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

val Ambar = Color(0xFFE0A030)
val AmbarClaro = Color(0xFFF5D08A)
val Fundo = Color(0xFF14161C)
val Verde = Color(0xFF4CD07D)
val Cinza = Color(0xFF6B7180)
val Painel = Color(0xFF1C1F26)

val CoresEscuras = darkColorScheme(
    primary = Ambar,
    onPrimary = Color(0xFF1E1400),
    secondary = AmbarClaro,
    secondaryContainer = Color(0xFF4A3A16),
    onSecondaryContainer = AmbarClaro,
    background = Fundo,
    surface = Fundo,
    surfaceVariant = Color(0xFF262A33),
    surfaceContainer = Painel,
    surfaceContainerHigh = Color(0xFF232731),
    surfaceContainerHighest = Color(0xFF2B303B),
)

/** Bloco com título, usado para agrupar opções. */
@Composable
fun Secao(titulo: String, modifier: Modifier = Modifier, conteudo: @Composable ColumnScope.() -> Unit) {
    Surface(color = Painel, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleSmall, color = Ambar, fontWeight = FontWeight.Bold)
            conteudo()
        }
    }
}

/** "−  valor  +" */
@Composable
fun Contador(texto: String, menos: () -> Unit, mais: () -> Unit, modifier: Modifier = Modifier, rotulo: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (rotulo != null) Text(rotulo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = menos, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Remove, "Menos") }
        Text(texto, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp))
        FilledTonalIconButton(onClick = mais, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Add, "Mais") }
    }
}

/** Tempo em m:ss,d */
fun formatarTempo(ms: Long, decimos: Boolean = false): String {
    val total = ms.coerceAtLeast(0)
    val min = total / 60000
    val seg = (total / 1000) % 60
    return if (decimos) "%d:%02d,%d".format(min, seg, (total / 100) % 10) else "%d:%02d".format(min, seg)
}
