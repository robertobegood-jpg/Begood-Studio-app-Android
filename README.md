<p align="center">
  <img src="docs/icone.svg" width="112" alt="Ícone do Begood Studio">
</p>

<h1 align="center">Begood Studio</h1>

<p align="center">
  <b>Afinador, metrônomo e player de estudo</b> num app só. Android, gratuito, sem anúncios e de código aberto:<br>
  afinação com precisão de centésimos de cent, metrônomo com treino de velocidade e player para tirar solos em câmera lenta.
</p>

<p align="center">
  <a href="https://github.com/robertobegood-jpg/Begood-Studio-app-Android/releases/latest"><b>⬇️ Baixar o APK</b></a> ·
  <a href="#-instalação-fora-da-play-store"><b>📲 Como instalar</b></a> ·
  <a href="#-cafezinho-para-o-dev"><b>☕ Apoiar o projeto</b></a> ·
  <a href="https://github.com/robertobegood-jpg/Begood-Studio-app-Android/issues"><b>💬 Sugestões e problemas</b></a>
</p>

---

## Por que usar

Para estudar um instrumento você precisa de três coisas: afinar, marcar o tempo e ouvir a música devagar até pegar o trecho.
O Begood Studio junta as três, sem propaganda no meio, e a tela fica acesa enquanto você toca.

**Abas:** Afinador • Metrônomo • Player

### 🎯 Afinador
- Toque uma corda: aparece a nota, quantos **cents** está acima ou abaixo e a frequência.
- **Ponteiro** (−50 a +50 cents, faixa verde = ±2 cents) ou **Estrobo** (faixas paradas = afinado).
- Afinações prontas: violão e guitarra (padrão, meio tom e um tom abaixo, Drop D, Open G/D, DADGAD), 7 cordas,
  baixo 4 e 5 cordas, cavaquinho, ukulele, violino, bandolim, viola de arco, violoncelo ou **cromático**.
- Toque numa corda na tela para **fixá-la** (útil quando a corda está muito longe da nota).
- Lá de referência de 415 a 466 Hz (atalhos 432, 440 e 442), nomes Dó-Ré-Mi ou C-D-E e filtro de barulho.
- Precisão medida nos testes: cerca de **0,01 cent** em som limpo e menos de 0,5 cent com harmônicos e ruído.
  Notas graves (baixo) usam uma janela mais longa para continuar precisas.

### 🥁 Metrônomo
- 20 a 300 BPM, ajuste de 1 em 1 ou de 5 em 5, barra ou **toque no ritmo**.
- Compassos de 2/4 a 12/8 ou qualquer número de tempos; colcheias, tercinas e semicolcheias.
- Acento no primeiro tempo, pisca e vibra no tempo.
- **Treino de velocidade**: sobe alguns BPM a cada tantos compassos, até o limite que você escolher.
- **Compassos mudos**: toca alguns compassos e silencia outros, para treinar o tempo interno.
- Continua tocando ao trocar de aba, então dá para usar junto com o player.

### 🎸 Player de estudo
- **Biblioteca** com todas as músicas do celular: busca (sem se preocupar com acentos), recentes, músicas,
  artistas (pelo "artista do álbum") e pastas (a partir de Music, um nível por vez; dá para escolher outro local).
  Ao voltar de um artista ou pasta, a lista continua onde estava.
  Arquivos de outros lugares (Drive, pendrive) por "Procurar arquivo em outro lugar".
- **Multipista (instrumentos separados)**: abra uma pasta com as faixas (Voz, Guitarra, Baixo, Bateria, Piano, Outros)
  e mude o volume de cada uma, **tire** um instrumento para tocar a parte dele ou ouça **só** ele.
  Velocidade, tom e A/B continuam valendo. Serve para faixas do separador abaixo, do Moises ou de outro programa.
- **Velocidade** de 25% a 200% **sem mudar o tom**; **tom** ±12 semitons com ajuste fino em cents.
- **Repetir trecho A/B**: marque no ponto atual, ajuste de 0,1 em 0,1 s ou **arraste o dedo na forma de onda**.
- Pausa com contagem antes de repetir, **acelerar a cada volta** (ex.: de 60% até 100%) e contador de voltas.
- **Trechos salvos** por música ("Solo 1", "Riff do refrão"...) e lista das músicas recentes.

### 🎚️ Separar os instrumentos no PC (grátis)
Na pasta [`ferramentas/separar-instrumentos`](ferramentas/separar-instrumentos) há um separador que usa o
[Demucs](https://github.com/facebookresearch/demucs) (inteligência artificial que roda no próprio PC, sem internet depois de instalado).

1. Precisa do Python instalado ([python.org](https://www.python.org/downloads/)).
2. Arraste as músicas para **`Separar instrumentos.bat`** (ou dê dois cliques nele e escolha).
   Na primeira vez ele instala o separador (cerca de 1 GB). Uma música leva de 1 a 3 minutos num PC comum.
3. As faixas ficam em `Documentos\Begood Studio\Faixas separadas\<música>`. Copie essa pasta para o celular e,
   no Studio, abra **Player → Biblioteca → Abrir faixas separadas (multipista)**.

Com 6 faixas sai voz, guitarra, baixo, bateria, piano e outros. Para 4 faixas, um pouco mais limpas
(voz, baixo, bateria e outros), rode `separar.ps1 -Faixas 4`.

---

## 📲 Instalação (fora da Play Store)

O app não está na Play Store: ele é instalado direto pelo arquivo **APK**, que fica aqui no GitHub. É seguro e leva um minuto.

1. **No celular**, abra a página da [última versão](https://github.com/robertobegood-jpg/Begood-Studio-app-Android/releases/latest)
   e toque no arquivo **`BegoodStudio-vX.Y.Z.apk`** (em *Assets*) para baixar.
2. Quando o download terminar, toque em **Abrir** (ou abra o arquivo na pasta **Downloads**).
3. O Android vai avisar que não pode instalar apps desta fonte. Toque em **Configurações** e ative
   **"Permitir desta fonte"** para o navegador (Chrome) ou o gerenciador de arquivos. Volte e toque em **Instalar**.
4. Se aparecer o **Play Protect** dizendo que o app é desconhecido, toque em **Mais detalhes › Instalar mesmo assim**.
   Esse aviso aparece para qualquer app de fora da loja; o código está todo aqui para quem quiser conferir.
5. Abra o app e, no afinador, toque em **Permitir** o uso do microfone.

> Requer **Android 8.0** ou superior.

<details>
<summary>Onde fica a opção "instalar apps desconhecidos" em cada marca</summary>

- **Samsung:** *Configurações › Aplicativos › (menu ⋮) Acesso especial › Instalar aplicativos desconhecidos*.
- **Xiaomi / Redmi / Poco:** *Configurações › Privacidade (ou Proteção) › Fontes desconhecidas*.
- **Motorola e Android puro:** *Configurações › Apps › Acesso especial a apps › Instalar apps desconhecidos*.

Escolha o app usado para abrir o APK (Chrome, Arquivos...) e ative **Permitir desta fonte**. Depois de instalar, pode desativar de novo.
</details>

### Atualizar
Baixe o APK da versão nova e instale **por cima**. Os ajustes e os trechos salvos continuam.

> 💡 Para ser avisado das versões novas, use o [Obtainium](https://github.com/ImranR98/Obtainium) (gratuito e de código aberto):
> toque em *Adicionar app*, cole o endereço deste repositório e ele avisa e instala as atualizações sozinho.

---

## Privacidade

- **Sem cadastro, sem anúncios e sem internet:** o app não tem permissão de internet, nada sai do celular.
- O som do microfone é analisado na hora, só para o afinador, e **nunca é gravado**.
- As músicas abertas no player são lidas do próprio celular.

### Permissões
| Permissão | Para quê |
|---|---|
| Microfone | Ouvir o instrumento no afinador |
| Vibração | Vibrar no tempo do metrônomo (opcional) |

---

## ☕ Cafezinho para o Dev

O app é gratuito e sem anúncios. Se ele ajuda nos seus estudos, pode apoiar o desenvolvimento com um Pix de qualquer valor.
Também dá para fazer isso direto no app, tocando em **☕ Cafezinho** na barra de baixo, com QR Code.

- **Chave Pix (e-mail):** `robertobegood+itau@gmail.com`
- **Nome:** Roberto Santos da Silva

**Pix copia e cola** (valor livre):
```
00020126630014br.gov.bcb.pix0128robertobegood+itau@gmail.com0209Cafezinho5204000053039865802BR5923Roberto Santos da Silva6009Sao Paulo62070503***63047463
```

Obrigado! 💛

---

## Para desenvolvedores

Kotlin + Jetpack Compose, minSdk 26, targetSdk 35. Pacote `br.com.begood.studio`.

- `afinador/DetectorTom.kt`: método de McLeod (NSDF) com autocorrelação por FFT, interpolação parabólica e
  refinamento no múltiplo do período; janelas de 4096 e 16384 amostras (graves).
  `MotorAfinador.kt`: AudioRecord em float (fonte UNPROCESSED quando o celular tem), mediana das leituras.
- `metronomo/MotorMetronomo.kt`: AudioTrack em baixa latência, cliques posicionados em amostras exatas.
- `player/ControlePlayer.kt`: Media3 ExoPlayer (`PlaybackParameters` para tempo e tom), busca exata em MP3
  (`FLAG_ENABLE_INDEX_SEEKING`), ponto B por `PlayerMessage`. `FormaDeOnda.kt` decodifica com MediaCodec.
- `player/Multipista.kt`: junta as faixas num WAV de 2 canais por faixa (cache do app) e o `MixerMultipista`
  (AudioProcessor antes do Sonic) mistura com o volume de cada uma; assim as faixas nunca saem de sincronia.
- `ui/Cafezinho.kt`: Pix copia e cola (BR Code) e QR Code com ZXing.

**Compilar e testar:** `./gradlew testDebugUnitTest assembleDebug`. Os testes do afinador (`DetectorTomTest`) conferem a precisão em cents; `MixerMultipistaTest` confere a mistura.
Sem a chave oficial, o APK sai com a chave de debug: funciona normalmente, mas não atualiza por cima da versão oficial.

**Publicar uma versão** (mantenedor): suba `versionCode` e `versionName` em `app/build.gradle.kts`, faça o commit e crie
uma tag `vX.Y.Z`. O GitHub Actions compila, assina e publica a Release com o APK.

Sugestões e correções são bem-vindas por [issues](https://github.com/robertobegood-jpg/Begood-Studio-app-Android/issues) e pull requests.

## Licença

[GPL-3.0](LICENSE): você pode usar, estudar, modificar e redistribuir, desde que as versões derivadas também sejam abertas sob a mesma licença.

Desenvolvido por Roberto Santos da Silva.
