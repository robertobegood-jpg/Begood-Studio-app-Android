package br.com.begood.studio.player

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.begood.studio.ui.Ambar
import br.com.begood.studio.ui.AmbarClaro
import br.com.begood.studio.ui.AppViewModel
import br.com.begood.studio.ui.Cinza
import br.com.begood.studio.ui.Painel
import br.com.begood.studio.ui.formatarTempo

/**
 * Biblioteca: todas as músicas do celular, com busca e separadas por artista e por pasta.
 * [fechar] = null quando ainda não há música aberta (a biblioteca é a própria tela do player).
 */
@Composable
fun TelaBiblioteca(vm: AppViewModel, abrirArquivo: () -> Unit, abrirPasta: () -> Unit, fechar: (() -> Unit)?) {
    val ctx = LocalContext.current
    val p = vm.player
    var temPermissao by remember { mutableStateOf(LeitorBiblioteca.temPermissao(ctx)) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { temPermissao = it }
    LaunchedEffect(Unit) { if (!temPermissao) pedir.launch(LeitorBiblioteca.permissao) }
    LaunchedEffect(temPermissao) { if (temPermissao && vm.musicas == null) vm.carregarMusicas() }
    if (fechar != null) BackHandler { fechar() }

    var busca by rememberSaveable { mutableStateOf("") }
    val grupo = vm.grupoBiblioteca // artista ou pasta escolhida
    /** Lista que lembra onde estava (cada aba, artista e pasta tem a sua). */
    val estado: @Composable (String) -> LazyListState = { chave ->
        remember(chave) { vm.posicoesBiblioteca.getOrPut(chave) { LazyListState() } }
    }

    val escolher = { m: MusicaLocal ->
        p.abrir(m.uri, m.nomeCompleto)
        vm.mostrarBiblioteca = false
    }
    val todas = vm.musicas.orEmpty()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("Biblioteca", style = MaterialTheme.typography.titleLarge, color = Ambar, modifier = Modifier.weight(1f))
            if (temPermissao) IconButton(onClick = { vm.carregarMusicas() }) { Icon(Icons.Filled.Refresh, "Atualizar a lista", tint = Cinza) }
            if (fechar != null) IconButton(onClick = fechar) { Icon(Icons.Filled.Close, "Voltar ao player") }
        }
        if (fechar == null && p.recentes.isEmpty()) {
            Text(
                "Escolha uma música para estudar: dá para diminuir a velocidade sem mudar o tom, mudar o tom e repetir o trecho do solo.",
                style = MaterialTheme.typography.bodySmall, color = Cinza,
            )
        }

        if (!temPermissao) {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Para mostrar as músicas do celular, o app precisa da permissão de acesso às músicas.")
                Button(onClick = { pedir.launch(LeitorBiblioteca.permissao) }, modifier = Modifier.fillMaxWidth()) { Text("Permitir acesso às músicas") }
                BotaoProcurar(abrirArquivo, abrirPasta)
                if (p.recentes.isNotEmpty()) {
                    Text("Estudadas recentemente", color = Ambar)
                    LazyColumn { items(p.recentes.toList(), key = { it.uri }) { r -> LinhaRecente(vm, r) } }
                }
            }
            return@Column
        }

        OutlinedTextField(
            value = busca, onValueChange = { busca = it }, singleLine = true,
            placeholder = { Text("Procurar música, artista ou pasta") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (busca.isNotEmpty()) IconButton(onClick = { busca = "" }) { Icon(Icons.Filled.Close, "Limpar busca") } },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )

        if (busca.isBlank()) {
            val abas = listOf("Recentes", "Músicas", "Artistas", "Pastas")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                abas.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = vm.abaBiblioteca == i, onClick = { vm.mudarAbaBiblioteca(i) },
                        shape = SegmentedButtonDefaults.itemShape(i, abas.size), icon = {},
                    ) { Text(t, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }

        if (vm.carregandoMusicas && vm.musicas == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        val rodape: @Composable () -> Unit = { BotaoProcurar(abrirArquivo, abrirPasta) }
        when {
            busca.isNotBlank() -> {
                val palavras = semAcento(busca).split(' ').filter { it.isNotBlank() }
                val achadas = todas.filter { m -> palavras.all { m.busca.contains(it) } }
                ListaMusicas(achadas, p, escolher, vazio = "Nada encontrado para \"$busca\".", rodape = rodape, estado = estado("busca"))
            }
            vm.abaBiblioteca == 0 -> LazyColumn(Modifier.fillMaxSize(), state = estado("recentes"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.recentes.isEmpty()) item { Text("Nenhuma música estudada ainda.", color = Cinza) }
                items(p.recentes.toList(), key = { it.uri }) { r -> LinhaRecente(vm, r) }
                item { rodape() }
            }
            vm.abaBiblioteca == 1 -> ListaMusicas(todas, p, escolher, vazio = "Nenhuma música encontrada no celular.", rodape = rodape, estado = estado("musicas"))
            vm.abaBiblioteca == 2 -> {
                if (grupo != null) BackHandler { vm.grupoBiblioteca = null }
                if (grupo == null) {
                    val grupos = todas.groupBy { it.artistaDaAba.ifEmpty { "Artista desconhecido" } }.toList().sortedBy { semAcento(it.first) }
                    LazyColumn(Modifier.fillMaxSize(), state = estado("artistas"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(grupos, key = { it.first }) { (nome, lista) ->
                            LinhaGrupo(Icons.Filled.Person, nome, lista.size) { vm.grupoBiblioteca = nome }
                        }
                        item { rodape() }
                    }
                } else {
                    Cabecalho(grupo) { vm.grupoBiblioteca = null }
                    val doArtista = todas.filter { it.artistaDaAba.ifEmpty { "Artista desconhecido" } == grupo }
                        .sortedWith(compareBy({ semAcento(it.album) }, { semAcento(it.titulo) }))
                    ListaMusicas(doArtista, p, escolher, vazio = "", rodape = rodape, estado = estado("artista:$grupo"))
                }
            }
            else -> AbaPastas(vm, todas, escolher, rodape, estado)
        }
    }
}

@Composable
private fun Cabecalho(titulo: String, voltar: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = voltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
        Text(titulo, style = MaterialTheme.typography.titleMedium, color = AmbarClaro, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Aba Pastas: começa na pasta escolhida (Music, por padrão) e mostra um nível por vez,
 * como um gerenciador de arquivos: subpastas primeiro, depois as músicas que estão ali.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AbaPastas(
    vm: AppViewModel, todas: List<MusicaLocal>, escolher: (MusicaLocal) -> Unit,
    rodape: @Composable () -> Unit, estado: @Composable (String) -> LazyListState,
) {
    val p = vm.player
    // Locais possíveis: as pastas de primeiro nível que têm música (Music, Download...) e "Todas"
    val locais = remember(todas) { todas.map { it.pasta.substringBefore('/') }.distinct().sortedBy { semAcento(it) } }
    val base = vm.pastaBase.takeIf { it.isEmpty() || it in locais } ?: ""
    val atual = vm.grupoBiblioteca ?: base
    val subir = { vm.grupoBiblioteca = atual.substringBeforeLast('/', "").takeIf { it.length > base.length } }
    if (atual != base) BackHandler { subir() }

    if (locais.size > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            for (l in listOf("") + locais) {
                FilterChip(selected = base == l, onClick = { vm.mudarPastaBase(l) }, label = { Text(if (l.isEmpty()) "Todas" else l) })
            }
        }
    }
    if (atual != base) Cabecalho(atual.removePrefix(base).trimStart('/')) { subir() }
    val prefixo = if (atual.isEmpty()) "" else "$atual/"
    val dentro = todas.filter { atual.isEmpty() || it.pasta == atual || it.pasta.startsWith(prefixo) }
    val subpastas = dentro.filter { it.pasta != atual }
        .groupBy { it.pasta.removePrefix(prefixo).substringBefore('/') }
        .toList().sortedBy { semAcento(it.first) }
    val aqui = dentro.filter { it.pasta == atual }.sortedWith(compareBy({ semAcento(it.album) }, { semAcento(it.titulo) }))

    LazyColumn(
        Modifier.fillMaxSize(), state = estado("pasta:$atual"),
        verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (subpastas.isEmpty() && aqui.isEmpty()) item { Text("Nenhuma música nesta pasta.", color = Cinza) }
        items(subpastas, key = { "d:" + it.first }) { (nome, lista) ->
            LinhaGrupo(Icons.Filled.Folder, nome, lista.size) { vm.grupoBiblioteca = prefixo + nome }
        }
        items(aqui, key = { it.id }) { m -> LinhaMusica(m, p, escolher) }
        item { rodape() }
    }
}

@Composable
private fun ListaMusicas(
    lista: List<MusicaLocal>, p: ControlePlayer, escolher: (MusicaLocal) -> Unit, vazio: String,
    rodape: @Composable () -> Unit, estado: LazyListState,
) {
    LazyColumn(Modifier.fillMaxSize(), state = estado, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (lista.isEmpty() && vazio.isNotEmpty()) item { Text(vazio, color = Cinza, modifier = Modifier.padding(vertical = 8.dp)) }
        items(lista, key = { it.id }) { m -> LinhaMusica(m, p, escolher) }
        item { rodape() }
    }
}

@Composable
private fun LinhaMusica(m: MusicaLocal, p: ControlePlayer, escolher: (MusicaLocal) -> Unit) {
    val atual = p.uri == m.uri
    Surface(
        color = if (atual) MaterialTheme.colorScheme.secondaryContainer else Painel,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { escolher(m) },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MusicNote, null, tint = if (atual) Ambar else Cinza, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(m.titulo, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (atual) AmbarClaro else Color.Unspecified)
                Text(
                    listOf(m.artista, m.album).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { m.pasta },
                    style = MaterialTheme.typography.bodySmall, color = Cinza, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(formatarTempo(m.duracaoMs), style = MaterialTheme.typography.bodySmall, color = Cinza, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun LinhaGrupo(icone: ImageVector, nome: String, qtd: Int, onClick: () -> Unit) {
    Surface(color = Painel, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icone, null, tint = Ambar, modifier = Modifier.size(22.dp))
            Text(nome, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text("$qtd", color = Cinza, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LinhaRecente(vm: AppViewModel, r: Recente) {
    val p = vm.player
    Surface(
        color = Painel, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { p.abrirRecente(r); vm.mostrarBiblioteca = false },
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(r.nome, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { p.esquecerRecente(r) }) { Icon(Icons.Filled.Close, "Tirar da lista", tint = Cinza) }
        }
    }
}

/** Para arquivos fora da biblioteca (Google Drive, pendrive, downloads de apps...). */
@Composable
private fun BotaoProcurar(abrirArquivo: () -> Unit, abrirPasta: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = abrirArquivo, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.FolderOpen, null); Text("  Procurar arquivo em outro lugar")
        }
        // Pasta com Voz, Guitarra, Baixo, Bateria... (separadas no PC, no Moises etc.)
        OutlinedButton(onClick = abrirPasta, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Tune, null); Text("  Abrir faixas separadas (multipista)")
        }
    }
}

