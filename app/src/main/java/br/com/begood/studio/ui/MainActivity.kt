package br.com.begood.studio.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import br.com.begood.studio.afinador.TelaAfinador
import br.com.begood.studio.metronomo.TelaMetronomo
import br.com.begood.studio.player.TelaPlayer

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Estudando com o instrumento na mão: a tela não apaga enquanto o app está aberto.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) abrirDoIntent(intent)

        setContent {
            MaterialTheme(colorScheme = CoresEscuras) {
                var cafezinho by rememberSaveable { mutableStateOf(false) }
                if (cafezinho) DialogoCafezinho { cafezinho = false }
                Scaffold(
                    containerColor = Fundo,
                    bottomBar = {
                        NavigationBar(containerColor = Painel) {
                            val itens = listOf(
                                Triple("Afinador", Icons.Filled.Speed, 0),
                                Triple("Metrônomo", Icons.Filled.AvTimer, 1),
                                Triple("Player", Icons.Filled.MusicNote, 2),
                            )
                            for ((rotulo, icone, n) in itens) {
                                NavigationBarItem(
                                    selected = vm.aba == n,
                                    onClick = { vm.mudarAba(n) },
                                    icon = { Icon(icone, null) },
                                    label = { Text(rotulo) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                    ),
                                )
                            }
                            // Não é uma aba: abre a janela do Pix por cima da tela atual.
                            NavigationBarItem(
                                selected = false,
                                onClick = { cafezinho = true },
                                icon = { Icon(Icons.Filled.Coffee, null) },
                                label = { Text("Cafezinho") },
                            )
                        }
                    },
                ) { espaco ->
                    Box(Modifier.fillMaxSize().padding(espaco)) {
                        when (vm.aba) {
                            0 -> TelaAfinador(vm)
                            1 -> TelaMetronomo(vm)
                            else -> TelaPlayer(vm)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        abrirDoIntent(intent)
    }

    /** "Abrir com Begood Studio" a partir de um gerenciador de arquivos. */
    private fun abrirDoIntent(intent: Intent?) {
        val dados = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW) {
            vm.player.abrir(dados)
            vm.mudarAba(2)
        }
    }
}
