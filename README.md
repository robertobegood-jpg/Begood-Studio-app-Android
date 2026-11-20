# Begood Studio

Afinador cromático de alta precisão, metrônomo e player para tirar solos — tudo num app só.

## Abas

### 🎯 Afinador
- Toque uma corda: aparece a nota, quantos **cents** está acima/abaixo e a frequência.
- **Ponteiro** (−50 a +50 cents, faixa verde = ±2 cents) ou **Estrobo** (faixas paradas = afinado; anda para a direita = alto).
- Afinações prontas: violão/guitarra (padrão, meio tom/um tom abaixo, Drop D, Open G/D, DADGAD), 7 cordas, baixo 4/5,
  cavaquinho, ukulele, violino/bandolim, viola de arco, violoncelo ou **cromático**.
- Toque numa corda na tela para **fixá-la** (útil quando a corda está muito longe da nota).
- Engrenagem: Lá de referência (415–466 Hz; atalhos 432/440/442), nomes Dó-Ré-Mi ou C-D-E, filtro de barulho.
- Precisão medida nos testes: ~0,01 cent em som limpo; abaixo de 0,5 cent com harmônicos e ruído.
  Notas graves (baixo) usam uma janela mais longa para continuar precisas.

### 🥁 Metrônomo
- 20 a 300 BPM (−1/+1/−5/+5, barra ou **toque no ritmo**).
- Compassos 2/4 a 12/8 ou qualquer número de tempos; subdivisão em colcheias, tercinas e semicolcheias.
- Acento no 1º tempo, pisca e vibra no tempo, volume.
- **Treino de velocidade**: sobe X BPM a cada N compassos até um limite.
- **Compassos mudos**: toca alguns compassos e silencia outros, para treinar o tempo interno.
- Continua tocando ao trocar de aba (dá para usar junto com o player).

### 🎸 Player
- Abra qualquer música do celular (MP3, M4A, FLAC, WAV, OGG...).
- **Velocidade** de 25% a 200% sem mudar o tom; **tom** ±12 semitons e ajuste fino em cents.
- **Repeat A/B**: botões A e B no ponto atual, ajuste de ±0,1 s, ou **arraste o dedo na forma de onda**.
  Pausa antes de repetir, **acelerar a cada volta** (ex.: de 60% até 100%), contador de voltas.
- **Trechos salvos** por música ("Solo 1", "Riff do refrão"...).
- Lembra as músicas recentes e reabre a última estudada.

A tela fica acesa enquanto o app está aberto.

## Técnico
- Kotlin + Jetpack Compose, minSdk 26, targetSdk 35. Pacote `br.com.begood.studio`.
- `afinador/DetectorTom.kt`: método de McLeod (NSDF) com autocorrelação por FFT, interpolação parabólica e
  refinamento no múltiplo do período; `Analisador` junta janela de 4096 e 16384 amostras (graves).
  `MotorAfinador.kt`: AudioRecord em float (fonte UNPROCESSED quando o celular tem), mediana das leituras.
- `metronomo/MotorMetronomo.kt`: AudioTrack em modo baixa latência; cliques posicionados em amostras exatas.
- `player/ControlePlayer.kt`: Media3 ExoPlayer (`PlaybackParameters` para tempo/tom; Sonic interno),
  busca exata em MP3 (`FLAG_ENABLE_INDEX_SEEKING`), ponto B por `PlayerMessage`. `FormaDeOnda.kt` decodifica com MediaCodec.

## Compilar
```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew testDebugUnitTest assembleDebug
```
APK em `app/build/outputs/apk/debug/`. Assinatura oficial igual aos outros apps (`keystore.properties` ou
segredos `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD` no GitHub Actions).
