# Local app data backup

The drawer provides **Экспорт резервной копии** and **Восстановить резервную копию**.

The backup is a local JSON file selected through Android's document picker. It contains:
- saved chat history (up to the app's existing 50-chat limit);
- selected local/hybrid mode and runtime module overrides;
- custom system prompt and local runtime UI/module settings.

It deliberately does not contain:
- Gemini API credentials (they are protected by Android Keystore and must be entered again after reinstall);
- model binaries (Qwen remains a separate 1.2 GB file and can be selected again from shared Download);
- temporary attachments or generated media.

Restore validates the format, app ID, size limits, chat structure and runtime JSON before applying the backup. Only files and preference keys owned by the runtime configuration are replaced. Export and restore run locally; no backup is uploaded to GitLab, GitHub or another service.

**Migration rule:** create a backup and verify that the JSON file exists before any uninstall/reinstall. Do not remove the installed app until the user explicitly approves migration.
