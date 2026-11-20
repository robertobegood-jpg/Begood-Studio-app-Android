# Begood Studio — afinador, metrônomo e player de estudo (Android)

App para músicos, com o mesmo visual (âmbar sobre fundo escuro) do Begood Music Player (`D:\Projetos iA\Begood Music`).
Três abas na barra de baixo: **Afinador · Metrônomo · Player**. Detalhes de uso e técnicos: `README.md`.

## Funções
- Afinador cromático (MPM/NSDF com FFT + refinamento em múltiplos do período; janela longa para graves).
  Ponteiro e estrobo, Lá 415–466 Hz, afinações prontas, corda fixa, C/Dó, filtro de barulho.
- Metrônomo gerado amostra por amostra (AudioTrack): compassos, subdivisões, tap tempo, treino de velocidade, compassos mudos.
- Player (Media3): velocidade 25–200% sem mudar o tom, tom ±12 semitons + cents, repeat A/B exato (PlayerMessage),
  forma de onda, pausa entre voltas, acelerar a cada volta, trechos salvos por música.

## Como trabalhar comigo
- Responda e escreva tudo em **português do Brasil**, linguagem simples.
- **Antes de começar, rode `git pull`** (quando o repositório estiver no GitHub).
- **Teste antes de entregar**: `set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` e
  `gradlew testDebugUnitTest assembleDebug`; corrija todos os erros. Copie o APK para `BegoodStudio.apk` na raiz (ignorado pelo git).
- Os testes do afinador (`DetectorTomTest`) conferem a precisão em cents: não deixe piorar.
- Ao terminar uma mudança: commit com mensagem clara e **pergunte antes do `git push`**.
- Peça confirmação antes de apagar arquivos ou mudar o `applicationId` (`br.com.begood.studio`).
- **NUNCA** commitar `*.jks`, `*.keystore`, `keystore.properties`, `app/assinatura/`, `local.properties`.
- Ao lançar versão: aumente `versionCode` e `versionName` em `app/build.gradle.kts`.
