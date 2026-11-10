# MOBILE AI AGENT

Основной репозиторий проекта: GitLab.

- GitLab: https://gitlab.com/lenives777/mobile-ai-agent
- Сборка и тесты: GitLab CI/CD (`.gitlab-ci.yml`)
- Очередь задач: `agent-control/queue.json`
- Роли AI-агентов: `agent-control/roles/`
- Документация миграции: `docs/GITLAB_PRIMARY_PLATFORM.md`

GitHub остаётся отдельной архивной копией. Новые задачи, сборки, проверки и агентная работа ведутся в GitLab. GitHub Actions и Copilot-профили удалены из миграционной ветки.

## Состояние переноса

- GitLab CI: валидатор очереди и Python-скриптов проходит проверку.
- Android APK: GitLab job добавлен; полная сборка ещё проходит проверку на GitLab Runner.
- AI executor: подготовлен Aider + Gemini API Free, но пока не активирован. Для запуска нужны masked CI variables `GEMINI_API_KEY` и `GITLAB_AGENT_TOKEN`.
- Очередь: первая задача source-sync audit остаётся `blocked` до подключения и проверки исполнителя.
- Мониторинг: подготовлен read-only job для CI, Merge Requests, очереди, реальной активности worker job и Termux/ADB heartbeat. Для ежечасного запуска требуется GitLab pipeline schedule.

## Android build environment

- JDK 17
- Gradle 8.14.3
- Android SDK Platform 36
- Android NDK 29.0.13113456
- CMake 3.31.6
- llama.cpp pinned at commit `3de52ac38818978d6da8f386eda43a856e53714a`

Запустите pipeline в GitLab и скачайте artifact `app/build/outputs/apk/debug/*.apk` из job `android-debug-apk`.

## Известное ограничение runtime

Ранее на Samsung S23 FE при загрузке native-библиотеки возникала ошибка `UnsatisfiedLinkError` с отсутствующим символом `__kmpc_global_thread_num`. Успешная сборка APK сама по себе не подтверждает исправление этой ошибки. Runtime считается исправленным только после установки APK и успешного запуска локальной модели на устройстве.

## Правила работы

- Одна задача за раз.
- Каждая задача имеет критерии приёмки и список разрешённых файлов.
- Изменения публикуются отдельной веткой и Draft Merge Request.
- Обязательные проверки до перехода к следующей задаче.
- Никаких прямых записей в `main`, автоматического слияния или разрушительных действий.
- Не хранить API keys, токены, модели, APK, signing keys и резервные копии в Git.
