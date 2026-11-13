# Persistent APK signing

The Android build workflow can sign the debug APK with a stable PKCS#12 keystore supplied through GitHub Actions secrets.

## Required GitHub repository secrets

Configure both secrets in **GitHub → repository Settings → Secrets and variables → Actions → New repository secret**:

- `ANDROID_DEBUG_KEYSTORE_BASE64`: the keystore file encoded as one Base64 line.
- `ANDROID_DEBUG_KEYSTORE_PASSWORD`: the PKCS#12 store/key password.

The workflow expects the key alias `mobile-ai-agent`. It deliberately fails if only one secret is configured. If neither secret exists, the workflow uses Android's standard debug signer and marks the build metadata as `ephemeral-debug`.

## Create a new keystore locally

Do this only after confirming that the existing installed app can be updated with this certificate, or after explicitly planning a one-time reinstall. Android will reject an update signed by a different certificate. Uninstalling the current app can erase its private app data.

On a trusted local machine or the phone's Termux shell:

```bash
mkdir -p "$HOME/.config/mobile-ai-agent-signing"
chmod 700 "$HOME/.config/mobile-ai-agent-signing"
umask 077
openssl rand -hex 32 > "$HOME/.config/mobile-ai-agent-signing/password"
PASS="$(cat "$HOME/.config/mobile-ai-agent-signing/password")"
keytool -genkeypair -v \
  -keystore "$HOME/.config/mobile-ai-agent-signing/mobile-ai-agent.p12" \
  -storetype PKCS12 \
  -storepass "$PASS" -keypass "$PASS" \
  -alias mobile-ai-agent -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=MOBILE AI AGENT, OU=Android, O=Private Build, C=KZ"
base64 -w 0 "$HOME/.config/mobile-ai-agent-signing/mobile-ai-agent.p12"
printf '\nPassword is stored locally at: %s\n' "$HOME/.config/mobile-ai-agent-signing/password"
```

Copy the single Base64 line into `ANDROID_DEBUG_KEYSTORE_BASE64`, and the password from the local password file into `ANDROID_DEBUG_KEYSTORE_PASSWORD`. Never commit the keystore, Base64 value, or password to GitLab/GitHub source code, issues, or chat.

## Migration warning

A newly generated key does **not** match an app previously signed with another key. The current installed app's signing certificate must be identified before enabling this secret. If its original private key is unavailable, a one-time reinstall may be unavoidable; do not uninstall until app data has been backed up and that migration is explicitly approved.

## Verification

Every build runs `apksigner verify --verbose`, records the signer certificate SHA-256 fingerprint, and includes `apk_signing_mode` in `build-metadata.json`. Compare the fingerprint with the installed app before installing an update.
