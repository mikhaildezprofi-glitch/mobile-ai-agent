# GitLab → GitHub → Android: актуальный поток

## Канонический поток

1. Все исходные изменения фиксируются в GitLab main через отдельные ветки и Merge Requests.
2. После каждого merge в GitLab main запускается GitLab push pipeline. Если задана защищённая CI/CD variable `GH_DISPATCH_TOKEN`, job `github-repository-dispatch` отправляет GitHub `repository_dispatch` с SHA коммита.
3. GitHub Actions workflow `GitLab Canonical Sync` реагирует на событие `gitlab-main-updated`, синхронизирует GitHub main и `sync/current` с точным GitLab SHA и запускает APK Build при необходимости.
4. Резервная проверка GitHub расписана каждые 5 минут. Ещё доступны ручной `workflow_dispatch` и push-триггер.
5. Перед сборкой проверяется наличие обязательного Gradle version catalog и скрипта патча llama.cpp.
6. Если Release или успешная/активная сборка для SHA отсутствует, workflow вызывает `workflow_dispatch` для **Android APK Build** на `sync/current`.
7. Android build запускает unit tests, собирает debug APK, формирует metadata/checksum и загружает artifact.

GitHub — зеркало и build runner, а не самостоятельная ветка разработки. При расхождении прежний GitHub main сохраняется в архивной ветке перед обновлением.

## Одноразовая настройка секретного токена

Для мгновенного события требуется GitHub fine-grained personal access token:
- Resource owner: владелец репозитория.
- Repository access: только `mikhaildezprofi-glitch/mobile-ai-agent`.
- Repository permissions: **Contents: Read and write**.
- Срок действия: ограниченный, с последующей ротацией.

Добавить токен в GitLab:
- Project → Settings → CI/CD → Variables → Add variable.
- Key: `GH_DISPATCH_TOKEN`.
- Value: токен.
- Visibility: Masked (и Hidden, если GitLab позволяет).
- Protect variable: включить, поскольку целевая ветка `main` защищена.
- Environment scope: `*`.

Токен нельзя отправлять в чат, коммитить в репозиторий или помещать в обычный файл. Не включать debug-трассировку shell в job с секретом.

Если переменная не задана, event job автоматически пропускается, а резервный 5-минутный опрос продолжает работать.

## GitLab CI

Job `github-repository-dispatch` запускается только при push в main и наличии `GH_DISPATCH_TOKEN`. Job `github-apk-sync` остаётся ручным только на ветках `sync/*`: он скачивает уже опубликованный GitHub Release по точному исходному SHA и проверяет SHA-256. Это не создаёт лишних сборок на каждом MR.

## Защита от повторных сборок

GitHub workflow сверяет SHA источника, существующий Release и активные/завершённые попытки Android Build. Для одного SHA повторная сборка автоматически не запускается.

## Установка на устройство

1. Скачать APK из Release, соответствующего нужному GitLab SHA.
2. Сверить SHA-256 с `build-metadata.json` и sidecar checksum.
3. Проверить подпись APK относительно установленной версии.
4. Устанавливать обновление только если подписи совместимы. Не удалять приложение и не очищать данные.
5. После установки проверить запуск, JNI, Qwen GGUF, тестовую генерацию, Gemini API и vision-модуль отдельно.

Если подписи не совпадают, не удалять установленное приложение автоматически.

## Критерии подтверждения

- GitLab main SHA и совпадающий GitHub main / `sync/current`.
- Успешный GitLab push dispatch job либо успешная резервная синхронизация.
- Успешные unit tests и Android APK Build.
- SHA-256 и размер APK, совпадающие с metadata.
- Отдельная runtime-проверка на устройстве.

Успешная облачная сборка не равна успешной работе приложения на телефоне.
