package br.com.begood.studio.separador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import br.com.begood.studio.R
import br.com.begood.studio.player.MontadorMultipista
import br.com.begood.studio.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Situação da separação (a tela observa). */
sealed class EstadoSeparacao {
    data object Parado : EstadoSeparacao()
    data class Trabalhando(val nome: String, val texto: String, val fracao: Float) : EstadoSeparacao()
    data class Pronto(val nome: String, val pasta: File) : EstadoSeparacao()
    data class Erro(val nome: String, val mensagem: String) : EstadoSeparacao()
}

/**
 * Separa os instrumentos em segundo plano (leva alguns minutos): continua mesmo com a tela apagada
 * ou com outro app aberto, mostrando o andamento na notificação.
 */
class ServicoSeparacao : Service() {
    companion object {
        private const val CANAL = "separacao"
        private const val ID = 7
        private const val ACAO_CANCELAR = "cancelar"
        private val _estado = MutableStateFlow<EstadoSeparacao>(EstadoSeparacao.Parado)
        val estado: StateFlow<EstadoSeparacao> = _estado

        fun iniciar(ctx: Context, musica: Uri, nome: String) {
            if (_estado.value is EstadoSeparacao.Trabalhando) return
            _estado.value = EstadoSeparacao.Trabalhando(nome, "Começando…", 0f)
            val i = Intent(ctx, ServicoSeparacao::class.java).setData(musica).putExtra("nome", nome)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        fun cancelar(ctx: Context) {
            ctx.startService(Intent(ctx, ServicoSeparacao::class.java).setAction(ACAO_CANCELAR))
        }

        /** A tela já mostrou o resultado: volta ao normal. */
        fun esquecerResultado() {
            if (_estado.value !is EstadoSeparacao.Trabalhando) _estado.value = EstadoSeparacao.Parado
        }
    }

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var trava: PowerManager.WakeLock? = null
    private var ultimaNotificacao = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_CANCELAR) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val musica = intent?.data
        val nome = intent?.getStringExtra("nome") ?: "Música"
        criarCanal()
        // "dataSync": o tipo "mediaProcessing" é recusado em alguns Androids novos
        val tipo = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, notificacao(nome, "Começando…", 0f, true), tipo)
        if (musica == null || job?.isActive == true) return START_NOT_STICKY

        trava = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BegoodStudio:separar").apply { acquire(3 * 60 * 60 * 1000L) }
        job = escopo.launch {
            val destino = MotorSeparador.pastaDaMusica(this@ServicoSeparacao, nome)
            try {
                MotorSeparador(this@ServicoSeparacao).separar(musica, destino) { e ->
                    _estado.value = EstadoSeparacao.Trabalhando(nome, e.texto, e.fracao)
                    val agora = System.currentTimeMillis()
                    if (agora - ultimaNotificacao > 1000) {
                        ultimaNotificacao = agora
                        gerente().notify(ID, notificacao(nome, e.texto, e.fracao, true))
                    }
                }
                _estado.value = EstadoSeparacao.Pronto(nome, destino)
                avisarFim(nome, "Instrumentos separados. Toque para abrir.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _estado.value = EstadoSeparacao.Parado
                gerente().cancel(ID)
            } catch (e: Throwable) {
                android.util.Log.e("BegoodStudio", "Erro ao separar", e)
                val msg = (e as? MontadorMultipista.Problema)?.message ?: when (e) {
                    is OutOfMemoryError -> "O celular ficou sem memória para separar. Feche outros apps e tente de novo."
                    else -> "Não deu para separar (${e.javaClass.simpleName}: ${e.message?.take(120)})."
                }
                _estado.value = EstadoSeparacao.Erro(nome, msg)
                avisarFim(nome, msg)
            } finally {
                runCatching { trava?.release() }
                ServiceCompat.stopForeground(this@ServicoSeparacao, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        escopo.cancel()
        runCatching { if (trava?.isHeld == true) trava?.release() }
        super.onDestroy()
    }

    private fun gerente() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            gerente().createNotificationChannel(
                NotificationChannel(CANAL, "Separar instrumentos", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificacao(nome: String, texto: String, fracao: Float, andando: Boolean): Notification {
        val b = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle(nome)
            .setContentText(texto)
            .setContentIntent(abrirApp())
            .setOnlyAlertOnce(true)
            .setSilent(true)
        if (andando) {
            b.setOngoing(true).setProgress(1000, (fracao * 1000).toInt(), false)
                .addAction(0, "Cancelar", PendingIntent.getService(
                    this, 1, Intent(this, ServicoSeparacao::class.java).setAction(ACAO_CANCELAR), PendingIntent.FLAG_IMMUTABLE,
                ))
        }
        return b.build()
    }

    private fun avisarFim(nome: String, texto: String) {
        gerente().notify(ID, notificacao(nome, texto, 1f, false).apply { flags = flags or Notification.FLAG_AUTO_CANCEL })
    }
}
