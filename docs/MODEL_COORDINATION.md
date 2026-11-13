# Automatic model coordination

The chat owns one `AgentCoordinator` for the active session. The coordinator selects providers from the task type, hybrid/local mode, runtime routing policy, and provider availability.

## Routing

- General text, code, and complex reasoning use the configured cloud preference in hybrid mode, with local fallback.
- Local mode never sends requests to Gemini.
- Documents and their extracted contents remain on-device.
- Photo and video tasks always start with local Vision analysis. The app downloads the SmolVLM model/projector on the first visual request, verifies both SHA-256 hashes, temporarily loads the Vision model, and then restores the previous local language model.
- Video analysis samples up to four frames. It is not continuous full-motion video understanding.
- After local Vision analysis, the coordinator selects the language model for the final answer. In hybrid mode, only the text summary produced by Vision may be sent to Gemini; original image/video bytes are never uploaded by this flow.
- If Gemini is unavailable, the configured local fallback is used. If Vision assets cannot be downloaded or verified, the app reports the failure instead of pretending it saw the media.

## Memory behavior

The app uses one native inference engine and swaps the loaded GGUF model for visual tasks. It does not keep Qwen and SmolVLM loaded simultaneously. This avoids reserving RAM for both models on the phone.

## Cloud-only text

When hybrid mode and a Gemini API key are configured, ordinary text prompts can be sent even if the native local engine failed to initialize. Visual tasks still require the native Vision-capable engine and a local language model to restore after analysis.
