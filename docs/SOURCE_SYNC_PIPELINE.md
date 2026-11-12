# GitLab → GitHub → Android: CI-independent sync

## Каноническая схема

1. GitLab `main` остаётся единственным местом разработки и изменения исходников.
2. GitLab CI намеренно отключён: `.gitlab-ci.yml` содержит `workflow: rules: - when: never`. GitLab Runner больше не участвует в синхронизации и сборке, поэтому исчерпание его минут не блокирует выпуск.
3. GitHub Actions выполняет синхронизацию GitLab `main` → GitHub `main` и `sync/current`, проверяет SHA и запускает Android APK Build при необходимости.
4. Резервный опрос GitLab выполняется каждый час в 17 минут каждого часа (`17 * * * *`).
5. После настройки прямого GitLab Project Webhook события push в `main` будут запускать GitHub workflow немедленно через GitHub `repository_dispatch`. Cloudflare Worker для этого не требуется.
6. Перед сборкой проверяются обязательный Gradle version catalog и скрипт патча llama.cpp.
7. Для одного SHA проверяются Release и существующие активные/завершённые попытки сборки, чтобы не создавать дубликаты.
8. Android Build запускает unit tests, собирает debug APK, формирует metadata/checksum и публикует artifact/release.

GitHub — зеркало и build runner, а не самостоятельная ветка разработки. При расхождении прежний GitHub main архивируется перед обновлением.

## Одноразовая настройка прямого события GitLab → GitHub

Нужно создать GitHub fine-grained personal access token:
- Resource owner: владелец репозитория.
- Repository access: только `mikhaildezprofi-glitch/mobile-ai-agent`.
- Repository permissions: **Contents: Read and write**.
- Срок действия: ограниченный, с последующей ротацией.

Токен не отправлять в чат и не коммитить в репозиторий.

В GitLab открыть **Project → Settings → Webhooks → Add new webhook** и задать:

- URL: `https://api.github.com/repos/mikhaildezprofi-glitch/mobile-ai-agent/dispatches`
- Trigger: только **Push events**.
- Branch filter strategy: **Wildcard**.
- Push events branch filter: `main`.
- Enable SSL verification: включено.
- Custom webhook template:
  ```json
  {"event_type":"gitlab-main-updated"}
  ```
- Custom headers:
  - `Authorization`: `Bearer <GitHub fine-grained token>`
  - `Accept`: `application/vnd.github+json`
  - `X-GitHub-Api-Version`: `2022-11-28`
  - `Content-Type`: `application/json`

GitLab webhook должен отправлять только push в main. GitHub API принимает событие `repository_dispatch`; workflow уже слушает тип `gitlab-main-updated`. При получении события GitHub самостоятельно забирает текущий GitLab SHA, поэтому в webhook payload не требуется передавать SHA.

После добавления нажать **Test → Push events** и проверить ответ GitHub API: HTTP 204. Затем проверить появление запуска **GitLab Canonical Sync** в GitHub Actions. Не вставлять токен в URL, тело webhook или обычные поля комментариев.

Если UI GitLab не показывает поле Custom webhook template или Custom headers, не создавать упрощённый webhook без них: он не сможет корректно авторизоваться и отправить ожидаемое тело GitHub API.

## Резервный механизм

GitHub workflow остаётся с расписанием `17 * * * *` и ручным `workflow_dispatch`. Если прямой webhook временно не доставлен, очередная часовая проверка всё равно заберёт актуальный GitLab main.

## Защита от повторных сборок

Синхронизация сравнивает GitLab SHA с GitHub main и `sync/current`. Перед запуском Android Build проверяются существующий Release, активные и завершённые попытки сборки для того же SHA. Для одного исходного SHA повторная сборка не запускается автоматически.

## Установка на устройство

1. Скачать APK из Release, соответствующего нужному GitLab SHA.
2. Сверить SHA-256 с `build-metadata.json` и sidecar checksum.
3. Проверить подпись APK относительно установленной версии.
4. Устанавливать обновление только если подписи совместимы. Не удалять приложение и не очищать данные.
5. После установки отдельно проверить запуск, JNI, Qwen GGUF, тестовую генерацию, Gemini API и vision-модуль.

Если подписи не совпадают, не удалять установленное приложение автоматически.

## Критерии подтверждения

- GitLab CI не выполняет jobs.
- GitHub main и `sync/current` совпадают с GitLab main.
- Прямой webhook возвращает HTTP 204 и запускает GitHub workflow.
- Резервный часовой workflow остаётся включён.
- Unit tests и Android APK Build проходят успешно.
- SHA-256 APK совпадает с metadata.
- Runtime отдельно проверен на телефоне.

Успешная облачная сборка не равна успешной работе приложения на телефоне.
