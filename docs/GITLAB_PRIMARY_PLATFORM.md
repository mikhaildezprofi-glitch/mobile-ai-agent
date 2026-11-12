# Репозитории и CI/CD MOBILE AI AGENT

## Источник исходников

- GitLab — единственный основной репозиторий: https://gitlab.com/lenives777/mobile-ai-agent
- GitHub — downstream mirror, облачная сборка APK и доставка артефактов: https://github.com/mikhaildezprofi-glitch/mobile-ai-agent
- Не разрабатывать независимые изменения напрямую в GitHub. Все исходные изменения сначала фиксируются в GitLab.

## GitLab → GitHub

Workflow `.github/workflows/gitlab-source-sync.yml` в GitHub:

- запускается при push в GitHub main, вручную и по расписанию каждый час на 17-й минуте;
- получает публичный GitLab main и сравнивает SHA;
- перед заменой отличающегося GitHub main сохраняет прежнюю вершину в `archive/github-main-before-sync-<run-id>`;
- синхронизирует GitHub main и `sync/current` с точным GitLab SHA;
- проверяет наличие `gradle/libs.versions.toml` и `scripts/patch-llama-diagnostics.py`;
- запускает Android build через `workflow_dispatch`, только если для SHA ещё нет релиза или уже выполненной/активной сборки.

Это односторонняя синхронизация. GitHub не является альтернативным источником исходников.

## Облачная Android-сборка

Workflow `.github/workflows/android-build.yml`:

1. Проверяет исходники и загружает зафиксированный llama.cpp в `third_party/llama.cpp`.
2. Устанавливает JDK 17, Gradle, Android SDK, NDK и CMake.
3. Применяет патч диагностики llama.cpp.
4. Запускает ` :app:testDebugUnitTest ` перед APK сборкой.
5. Собирает ` :app:assembleDebug `.
6. Создаёт `build-metadata.json` и SHA-256.
7. Загружает APK artifact и публикует Release для веток `sync/*`.

Release содержит точный исходный SHA, номер запуска, версию, размер и SHA-256 APK.

## GitLab CI и квота

Job `github-apk-sync` оставлен ручным для push в ветки `sync/*`. Он скачивает Release для точного SHA и сверяет APK, metadata и sidecar checksum. Автоматически он не запускается, чтобы не расходовать ограниченную GitLab CI-квоту.

## Ограничения проверки

Успешные unit tests и APK build не доказывают успешную загрузку JNI, GGUF, vision projector или inference на телефоне. Для этого нужны отдельные runtime-проверки на Samsung S23 FE.

## Безопасность

- Не хранить токены, API keys, signing keys, APK, GGUF-модели и пользовательские резервные копии в Git.
- Не удалять приложение и не очищать его данные при обновлении.
- Перед обновлением APK проверять SHA-256 и подпись. Debug APK из GitHub Actions подписан runner debug keystore и не гарантированно совместим с уже установленной копией, если fingerprint подписи отличается.
- Не считать совпадение имени или версии APK доказательством совпадения исходного SHA.
