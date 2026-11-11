# Репозитории и CI/CD MOBILE AI AGENT

## Источник исходников

GitLab — основной репозиторий проекта: https://gitlab.com/lenives777/mobile-ai-agent

GitHub — синхронизируемая копия и исполнитель Android APK build workflow: https://github.com/mikhaildezprofi-glitch/mobile-ai-agent

## Сборка

- GitHub Actions workflow `.github/workflows/android-build.yml` собирает debug APK на push, pull request и по ручному запуску.
- GitLab job `github-apk-sync` запускается только для push в ветки `sync/*`.
- GitLab скачивает Release, соответствующий точному исходному SHA, и сверяет SHA-256 APK с метаданными и sidecar-файлом.
- APK artifact в GitLab является проверенной копией сборки GitHub Actions.

## Ограничения

Сборка не доказывает корректную загрузку JNI-библиотек, GGUF, vision projector или успешный inference. Для этого нужны отдельные проверки на Android-устройстве.

## Безопасность

- Не хранить токены, API keys, signing keys, APK, GGUF-модели и пользовательские резервные копии в Git.
- Не очищать данные приложения при обновлении.
- Не принимать совпадение версии или имени APK за доказательство совпадения исходного SHA.
