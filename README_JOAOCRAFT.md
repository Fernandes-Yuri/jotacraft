# JoãoCraft

Jogo voxel para Android feito a partir da base open-source **Luanti**
(antigo Minetest, LGPL-2.1). Mesma engine, experiência própria para criança:
botão de microfone com ditado em pt-BR, assistente que fala (TTS do Android),
memória de preferências e poderes liberados no servidor da família.

> Base: https://github.com/luanti-org/luanti
> Licenças e créditos originais mantidos (`LICENSE.txt`, `COPYING.LESSER`,
> `CREDITS.md` e avisos de copyright no código). JoãoCraft é um fork
> independente, sem vínculo com o projeto Luanti.

## Estrutura do fork

* `android/.../VoiceOverlay.java` — botão 🎤 flutuante, SpeechRecognizer pt-BR,
  TextToSpeech pt-BR, fila de voz via ponte HTTP. Sem JNI, sem mexer na engine.
* `android/app` — applicationId `com.joaocraft.game`, nome `JoaoCraft`,
  permissão de microfone.
* Servidor do jogo + ponte do assistente: ver repo da infra (fora deste repo).

## Compilar (nuvem, recomendado)

Push na `main` dispara `.github/workflows/joaocraft.yml`: compila e publica
os APKs assinados (chave de desenvolvimento em `android/joaocraft-debug.keystore`,
só para sideload na família — **não** usar para Play Store).
Baixar em Actions > último run > `JoaoCraft-apks` (`arm64` para celulares novos).

## Testes locais

Neste repo não se compila nada local (regra da casa): só testes unitários
da ponte ficam no repo da infra. Build pesado é só no Actions.
