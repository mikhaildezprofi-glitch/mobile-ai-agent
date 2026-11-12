#!/usr/bin/env python3
"""Validate the non-executable MOBILE AI AGENT runtime configuration bundle."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "runtime-config"

def load_json(relative: str):
    path = (ROOT / relative).resolve()
    if ROOT.resolve() not in path.parents:
        raise ValueError(f"Unsafe config path: {relative}")
    return json.loads(path.read_text(encoding="utf-8"))

def require(condition: bool, message: str):
    if not condition:
        raise ValueError(message)

def main():
    manifest = load_json("manifest.json")
    require(manifest.get("schemaVersion") == 1, "Unsupported manifest schemaVersion")
    require(manifest.get("channel") == "stable", "Unexpected release channel")
    require(manifest.get("security", {}).get("containsSecrets") is False, "Remote config must not contain secrets")
    require(manifest.get("security", {}).get("remoteCodeAllowed") is False, "Remote executable code must stay disabled")

    ui = load_json("ui.json")
    require(1 <= int(ui.get("inputMaxLines", 1)) <= 3, "inputMaxLines must be 1..3")
    limits = ui.get("limits", {})
    require(1 <= int(limits.get("maxPromptCharacters", 0)) <= 100000, "Invalid prompt size limit")
    require(1 <= int(limits.get("maxAttachmentCount", 0)) <= 20, "Invalid attachment count")

    modules = load_json("modules.json")
    require(isinstance(modules, dict) and modules.get("schemaVersion") == 1, "Invalid modules schema")
    for name, config in modules.items():
        if name == "schemaVersion":
            continue
        require(isinstance(config, dict) and isinstance(config.get("enabled"), bool), f"Invalid module flag: {name}")

    models = load_json("models.json")
    require(models.get("schemaVersion") == 1, "Invalid models schema")
    ids = [item.get("id") for item in models.get("models", [])]
    require(len(ids) == len(set(ids)) and all(ids), "Model ids must be unique and non-empty")
    require(models.get("defaultLocalModel") in ids, "Default local model is not listed")

    routing = load_json("routing.json")
    require(routing.get("schemaVersion") == 1, "Invalid routing schema")
    require(routing.get("privacy", {}).get("cloudUploadsRequireExplicitUserAction") is True,
            "Cloud uploads must require explicit user action")

    prompts_index = load_json("prompts/index.json")
    require(prompts_index.get("schemaVersion") == 1, "Invalid prompts schema")
    for profile in prompts_index.get("profiles", []):
        prompt_path = (ROOT / "prompts" / profile["file"]).resolve()
        require(ROOT.resolve() in prompt_path.parents, "Unsafe prompt path")
        require(prompt_path.is_file(), f"Missing prompt file: {profile['file']}")
        require(0 < prompt_path.stat().st_size <= 20000, f"Invalid prompt size: {profile['file']}")

    response_profiles = load_json("response_profiles.json")
    require(response_profiles.get("schemaVersion") == 1, "Invalid response profiles schema")
    require(response_profiles.get("default") in [p.get("id") for p in response_profiles.get("profiles", [])],
            "Default response profile is missing")

    privacy = load_json("privacy.json")
    require(privacy.get("schemaVersion") == 1, "Invalid privacy schema")
    require(privacy.get("cloudUploadsRequireExplicitUserAction") is True, "Cloud uploads must be opt-in")
    require(privacy.get("apiKeysInRemoteConfig") is False, "API keys must not be in remote config")

    voice = load_json("voice.json")
    require(voice.get("schemaVersion") == 1, "Invalid voice schema")
    require(voice.get("wakeWord", {}).get("enabled") is False, "Wake word must remain disabled until native service is implemented")

    network = load_json("network.json")
    require(network.get("schemaVersion") == 1, "Invalid network schema")
    require(1 <= int(network.get("timeouts", {}).get("connectSeconds", 0)) <= 60, "Invalid connect timeout")

    diagnostics = load_json("diagnostics.json")
    require(diagnostics.get("schemaVersion") == 1, "Invalid diagnostics schema")
    require(diagnostics.get("includeApiKeys") is False, "Diagnostics must not include API keys")

    commands = load_json("commands.json")
    require(commands.get("schemaVersion") == 1, "Invalid commands schema")
    allowed_actions = {"ui.new_chat", "ui.open_settings", "ui.open_diagnostics", "generation.cancel"}
    for command in commands.get("commands", []):
        require(command.get("action") in allowed_actions, f"Action is not allowlisted: {command.get('action')}")
        require(command.get("enabled") is True, "Unexpected disabled command in baseline")
    require(commands.get("safety", {}).get("remoteCommandsAreNotExecutable") is True,
            "Remote commands must never be executable")

    print("Runtime configuration: OK")
    print(f"Profiles: {len(prompts_index.get('profiles', []))}")
    print(f"Models: {len(models.get('models', []))}")
    print(f"Commands: {len(commands.get('commands', []))}")
    print(f"Response profiles: {len(response_profiles.get('profiles', []))}")

if __name__ == "__main__":
    main()
