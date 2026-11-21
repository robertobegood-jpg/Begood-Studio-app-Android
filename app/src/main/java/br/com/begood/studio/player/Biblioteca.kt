package br.com.begood.studio.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Uma música do celular, como o Android a conhece (MediaStore). */
data class MusicaLocal(
    val id: Long,
    val titulo: String,
    val artista: String,
    val album: String,
    /** "Artista do álbum" das tags (ex.: coletâneas e participações ficam juntas). */
    val artistaAlbum: String,
    val duracaoMs: Long,
    val pasta: String,
    val uri: Uri,
) {
    /** Texto para a busca: sem acentos e em minúsculas. */
    val busca: String = semAcento("$titulo $artista $artistaAlbum $album $pasta")

    /** Artista usado na aba Artistas: o do álbum; se não tiver, o da faixa. */
    val artistaDaAba: String get() = artistaAlbum.ifEmpty { artista }

    /** Nome mostrado no player. */
    val nomeCompleto: String get() = if (artista.isNotEmpty()) "$titulo — $artista" else titulo
}

fun semAcento(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** Lê todas as músicas do celular (sem precisar escolher arquivo por arquivo). */
object LeitorBiblioteca {
    val permissao: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun temPermissao(ctx: Context) = ContextCompat.checkSelfPermission(ctx, permissao) == PackageManager.PERMISSION_GRANTED

    suspend fun ler(ctx: Context): List<MusicaLocal> = withContext(Dispatchers.IO) {
        val colecao = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val colunas = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Audio.Media.ALBUM_ARTIST)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Audio.Media.RELATIVE_PATH)
            @Suppress("DEPRECATION") add(MediaStore.Audio.Media.DATA)
        }.toTypedArray()
        // Tudo com pelo menos 5 s (bases e playbacks nem sempre vêm marcados como "música"; toques curtos ficam de fora)
        val filtro = "${MediaStore.Audio.Media.DURATION} >= 5000"
        val lista = ArrayList<MusicaLocal>()
        runCatching {
            ctx.contentResolver.query(colecao, colunas, filtro, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTit = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArt = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlb = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iNome = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iAlbArt = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1
                val iRel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
                @Suppress("DEPRECATION") val iDados = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val nomeArquivo = c.getString(iNome).orEmpty()
                    val titulo = c.getString(iTit)?.takeIf { it.isNotBlank() } ?: nomeArquivo.substringBeforeLast('.')
                    val artista = c.getString(iArt)?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }.orEmpty()
                    val album = c.getString(iAlb)?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }.orEmpty()
                    val artistaAlbum = (if (iAlbArt >= 0) c.getString(iAlbArt) else null)
                        ?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }.orEmpty()
                    val pasta = when {
                        iRel >= 0 -> c.getString(iRel)?.trimEnd('/')
                        iDados >= 0 -> c.getString(iDados)?.substringBeforeLast('/')?.substringAfter("/emulated/0/")
                        else -> null
                    }.orEmpty().ifEmpty { "Outros" }
                    lista += MusicaLocal(id, titulo, artista, album, artistaAlbum, c.getLong(iDur), pasta, ContentUris.withAppendedId(colecao, id))
                }
            }
        }
        lista.sortedWith(compareBy({ semAcento(it.titulo) }, { semAcento(it.artista) }))
    }
}
