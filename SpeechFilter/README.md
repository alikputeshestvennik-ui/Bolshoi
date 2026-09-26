# Speech Filter — Phone + Galaxy Watch 8

Прототип приложения для выделения человеческой речи по схеме:

Microphone → High-Pass Filter → Silero VAD → YAMNet → speech PCM → WAV

## Режимы

- **AUTO** — если рядом подключён Galaxy Watch 8, захват и Silero VAD + YAMNet выполняются на часах; на телефон передаются только подтверждённые speech-сегменты.
- **PHONE** — обработка полностью на телефоне.
- **WATCH** — запрашивает часы; если часы недоступны, автоматически используется телефон.

Для сегментов используются:
- 500 ms pre-roll
- 700 ms post-roll
- 16 kHz / mono / PCM16
- Silero VAD threshold 0.50
- YAMNet speech score threshold 0.18

YAMNet работает на 0.975 s окнах при 16 kHz. Это намеренно означает, что подтверждение YAMNet добавляет задержку на старт сегмента, зато выходной файл содержит только кандидаты, прошедшие VAD + YAMNet.

## Wear OS transport

Команды: `MessageClient`.

Аудио: `ChannelClient`, path `/speech-audio`, бинарные PCM packets.

Android documentation explicitly describes ChannelClient as stream-oriented and lists microphone voice data as a use case. ChannelClient is connection-oriented and does not provide persistence, поэтому архитектура использует ring buffer/pre-roll на устройстве. 

## Модели

Модели не включены в git-архив, чтобы не тащить бинарные ML-файлы в репозиторий.

Перед сборкой:

```bash
bash scripts/download_models.sh
```

Это положит в `shared/src/main/assets/`:

- `silero_vad.onnx`
- `yamnet.tflite`
- `yamnet_class_map.csv`

Silero VAD используется через ONNX Runtime. Репозиторий Silero публикует ONNX-модели, включая `silero_vad.onnx`; для 16 kHz также доступна отдельная ONNX-модель. YAMNet ожидает mono 16 kHz audio.

## Сборка локально

Требуется JDK 17 и Gradle 8.9+.

```bash
bash scripts/download_models.sh
gradle :phone:assembleDebug :wear:assembleDebug
```

APK:

```text
phone/build/outputs/apk/debug/phone-debug.apk
wear/build/outputs/apk/debug/wear-debug.apk
```

## Постоянная подпись

В репозиторий **не добавляйте keystore**.

GitHub Actions ожидает следующие Secrets:

```text
ANDROID_KEYSTORE_B64
ANDROID_KEY_ALIAS
ANDROID_STORE_PASSWORD
ANDROID_KEY_PASSWORD
```

`ANDROID_KEYSTORE_B64` — base64 вашего постоянного `.jks/.keystore`.

Workflow восстанавливает этот keystore только внутри runner и подписывает оба APK одной и той же ключевой парой. Это важно: Android Wear Data Layer требует совпадения package name и подписи между приложением телефона и приложением часов.

## GitHub Actions

После push workflow:

1. скачивает ML-модели;
2. восстанавливает ваш signing keystore из GitHub Secret;
3. собирает `phone` и `wear`;
4. загружает оба подписанных APK как workflow artifacts.

## Важное ограничение текущего прототипа

Это готовый исходный проект для сборки, но фактическую производительность Silero + YAMNet на конкретном Galaxy Watch 8 нужно измерить. Особенно важны CPU, задержка первого speech-сегмента и расход батареи.

Также Android/Wear OS может ограничивать запуск microphone foreground service из фонового состояния. Для production-версии следует протестировать сценарий «нажать START на телефоне → автоматически запустить захват на часах» на реальном Watch 8 и при необходимости запускать watch recording через видимую watch-side activity.

## License / model notices

Перед публикацией приложения проверьте лицензии самого приложения, Silero VAD, YAMNet/AudioSet labels и используемых Android/Google Play services компонентов.


## WAV playback

The phone app includes a local recordings list with built-in WAV playback, pause/seek controls and WAV export/share. Playback uses AndroidX Media3 ExoPlayer, which supports WAV progressive playback.
