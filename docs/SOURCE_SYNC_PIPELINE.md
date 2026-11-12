# GitLab → GitHub → Android: актуальный поток

## Канонический поток

1. Все исходные изменения фиксируются в GitLab main через отдельные ветки и Merge Requests.
2. GitHub Actions workflow `GitLab Canonical Sync` опрашивает GitLab main каждый час на 17-й минуте, а также может запускаться вручную.
3. При обнаружении нового SHA workflow архивирует прежний GitHub main, затем обновляет GitHub main и `sync/current` до точного GitLab SHA.
4. Workflow проверяет наличие обязательного Gradle version catalog и скрипта патча llama.cpp.
5. Если Release или успешная/активная сборка для SHA отсутствует, workflow вызывает `workflow_dispatch` для **Android APK Build** на `sync/current`.
6. Android build запускает unit tests, собирает debug APK, формирует metadata/checksum и загружает artifact.
7. Для `sync/*` refs создаётся Release с APK, `app-debug.apk.sha256` и `build-metadata.json`.

GitHub — зеркало и build runner, а не самостоятельная ветка разработки. При расхождении прежний GitHub main сохраняется в архивной ветке перед обновлением.

## GitLab CI

Job `github-apk-sync` запускается вручную только на ветках `sync/*`. Он предназначен для скачивания уже опубликованного GitHub Release по точному исходному SHA и проверки совпадения SHA-256. Не запускать его автоматически на каждом MR или push: это расходует ограниченную GitLab CI-квоту.

## Установка на устройство

1. Скачать APK из Release, соответствующего нужному GitLab SHA.
2. Сверить SHA-256 с `build-metadata.json` и sidecar checksum.
3. Проверить подпись APK относительно установленной версии.
4. Устанавливать обновление только если подписи совместимы. Не удалять приложение и не очищать данные, чтобы не потерять локальную модель, настройки и историю.
5. После установки проверить запуск, состояние JNI, загрузку Qwen GGUF, тестовую генерацию, Gemini API и vision-модуль отдельно.

Если подписи не совпадают, не удалять установленное приложение автоматически: сначала нужно решить вопрос сохранения данных и модели.

## Критерии подтверждения

- GitLab main SHA и совпадающий GitHub main / `sync/current`.
- Успешный GitLab Canonical Sync run.
- Успешные unit tests и Android APK Build run.
- SHA-256 и размер APK, совпадающие с metadata.
- Отдельная runtime-проверка на устройстве.

Успешная облачная сборка не равна успешной работе приложения на телефоне.
