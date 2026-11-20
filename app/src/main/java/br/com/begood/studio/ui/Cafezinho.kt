package br.com.begood.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import java.text.Normalizer
import java.util.Locale

/**
 * Dados do Pix que recebe os cafezinhos (os mesmos dos outros apps).
 * Para trocar, edite só estes três valores.
 */
object Cafezinho {
    /** E-mail, celular (+5511999999999), CPF/CNPJ (só números) ou chave aleatória. */
    const val CHAVE_PIX = "robertobegood+itau@gmail.com"

    /** Nome de quem recebe, como aparece no app do banco (até 25 letras). */
    const val NOME = "Roberto Santos da Silva"

    /** Cidade de quem recebe (até 15 letras). */
    const val CIDADE = "São Paulo"
}

/**
 * Monta o "Pix copia e cola" (BR Code estático) no padrão do Banco Central:
 * campos EMV no formato ID + tamanho + valor, terminando com o CRC16.
 */
object Pix {
    fun payload(chave: String, nome: String, cidade: String, valorCentavos: Long? = null, mensagem: String = ""): String {
        val conta = campo("00", "br.gov.bcb.pix") +
            campo("01", chave.trim()) +
            (if (mensagem.isNotBlank()) campo("02", limpar(mensagem, 40)) else "")
        val semCrc = buildString {
            append(campo("00", "01"))
            append(campo("26", conta))
            append(campo("52", "0000"))
            append(campo("53", "986"))
            if (valorCentavos != null && valorCentavos > 0) {
                append(campo("54", String.format(Locale.US, "%.2f", valorCentavos / 100.0)))
            }
            append(campo("58", "BR"))
            append(campo("59", limpar(nome, 25)))
            append(campo("60", limpar(cidade, 15)))
            append(campo("62", campo("05", "***")))
            append("6304")
        }
        return semCrc + crc16(semCrc)
    }

    private fun campo(id: String, valor: String): String =
        id + valor.length.toString().padStart(2, '0') + valor

    /** Sem acentos e no tamanho máximo do campo. */
    private fun limpar(texto: String, maximo: Int): String =
        Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^A-Za-z0-9 .,@+\\-]"), "")
            .take(maximo)

    /** CRC16-CCITT (polinômio 0x1021, valor inicial 0xFFFF), em 4 dígitos hexadecimais. */
    private fun crc16(texto: String): String {
        var crc = 0xFFFF
        texto.toByteArray(Charsets.UTF_8).forEach { b ->
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc.toString(16).uppercase(Locale.ROOT).padStart(4, '0')
    }
}

/** Valores sugeridos. null = quem paga escolhe no app do banco. */
private enum class Oferta(val rotulo: String, val centavos: Long?) {
    CAFE("Café", 500),
    CAFE_PAO("Café e pão", 1000),
    ALMOCO("Almoço", 2500),
    LIVRE("Outro", null)
}

private fun qrCode(texto: String): BitMatrix =
    QRCodeWriter().encode(
        texto, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    )

private fun reais(centavos: Long) = "R$ %d,%02d".format(centavos / 100, centavos % 100)

/** Janela "Pague um cafezinho": escolher o valor, QR Code do Pix e botões de copiar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogoCafezinho(aoFechar: () -> Unit) {
    val area = LocalClipboardManager.current
    var oferta by remember { mutableStateOf(Oferta.CAFE) }
    var copiado by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiado) {
        if (copiado != null) { delay(2500); copiado = null }
    }
    val payload = remember(oferta) {
        Pix.payload(Cafezinho.CHAVE_PIX, Cafezinho.NOME, Cafezinho.CIDADE, oferta.centavos, "Cafezinho")
    }
    val matriz = remember(payload) { runCatching { qrCode(payload) }.getOrNull() }

    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth(),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("☕ Pague um cafezinho", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "O app é gratuito e sem anúncios. Se ele é útil para você, um Pix de qualquer valor ajuda a manter o projeto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    Oferta.entries.forEach { o ->
                        FilterChip(
                            selected = oferta == o,
                            onClick = { oferta = o },
                            label = { Text(o.rotulo, maxLines = 1) },
                            shape = RectangleShape,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                // QR sempre em fundo branco, para qualquer câmera ler bem.
                Box(Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(1f).background(Color.White).padding(16.dp)) {
                    if (matriz != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            val n = matriz.width
                            val modulo = size.minDimension / n
                            for (y in 0 until n) for (x in 0 until n) {
                                if (matriz.get(x, y)) {
                                    drawRect(Color(0xFF101014), Offset(x * modulo, y * modulo), Size(modulo + 0.5f, modulo + 0.5f))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    (oferta.centavos?.let { reais(it) } ?: "Valor livre") + " · " + Cafezinho.NOME,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    shape = RectangleShape,
                    onClick = {
                        area.setText(AnnotatedString(payload))
                        copiado = "Código copiado. Cole no app do seu banco, em Pix copia e cola."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Copiar Pix copia e cola") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    shape = RectangleShape,
                    onClick = {
                        area.setText(AnnotatedString(Cafezinho.CHAVE_PIX))
                        copiado = "Chave Pix copiada."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Copiar chave Pix") }
                copiado?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CD07D), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Aponte a câmera do app do banco para o código ou use o copia e cola. Obrigado pelo café!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(shape = RectangleShape, onClick = aoFechar) { Text("Fechar") }
            }
        }
    }
}
