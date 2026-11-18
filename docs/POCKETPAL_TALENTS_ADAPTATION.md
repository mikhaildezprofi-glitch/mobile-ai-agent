# PocketPal AI talent adaptation

This change adapts the useful part of PocketPal's TalentRegistry / AgentRunner pattern to
MOBILE AI AGENT's Kotlin and Android architecture. It does not copy PocketPal's React Native
runtime or replace the existing AgentCoordinator, model providers, planner, or task journal.

## Included in this phase

- A small allowlisted local talent registry.
- A bounded sequential tool runner (maximum five calls per request).
- A strict JSON tool-call envelope, because the current llama.cpp Android wrapper exposes
  text token streaming through `sendUserPrompt()`, not structured OpenAI `tool_calls` deltas.
- Two side-effect-free tools: arithmetic calculation and current date/time in an optional IANA
  time zone.
- Cancellation propagation and safe error results.
- Unit tests for parsing, arithmetic safety, time-zone validation, dispatch, and loop limits.

## Deliberate differences from PocketPal

PocketPal's runner consumes structured tool-call deltas from an OpenAI-compatible completion
engine and can execute multiple calls concurrently. This app's current native interface is
text-streaming only, so this adaptation uses one JSON envelope at a time and sequential
execution. It buffers each model turn until it can distinguish a tool call from a final answer;
tool-enabled responses therefore do not stream partial text from an intermediate turn.

No network search, URL fetching, file mutation, shell, ADB, or Android device-control tool is
registered. Those capabilities require separate permission, confirmation, and audit designs.
A model cannot enable a tool merely by naming it: dispatch is limited to the built-in registry.

## Future work

When the native bridge exposes structured function-call deltas, replace the JSON envelope parser
with the engine's typed tool-call API. Add additional tools only with explicit capability
registration, argument validation, permission checks, and user confirmation for side effects.
