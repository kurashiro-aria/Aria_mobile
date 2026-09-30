# ARIA Cloud architecture — Stage 1

Branch: `aria-cloud-brain-prototype`

Stage 1 establishes a provider-neutral brain boundary. It does **not** migrate inference, remove local code, add credentials, or change normal user behavior.

## Baseline and protection

The Cloud prototype was created from commit `6dd516c182e2e9295cd971d526f93eb9944a277a`, which was also the tip of `aria-alpha-0.2-local-ai` at the start of this stage. The local branch is the functional rollback point and must not receive Cloud commits or merges.

At the start of Stage 1 the two branches compared as identical. All changes described here belong only to `aria-cloud-brain-prototype`.

## Current architecture

`MainActivity` currently coordinates UI, GGUF selection/restoration, llama `InferenceEngine`, conversation preparation, generation streaming, reply filtering, emotions, memory, initiative, voice and wake-word behavior. `ConversationBrain` interprets a turn and continuity; `ConversationContext` builds the context/prompt; `AriaPersonality` supplies ARIA identity instructions; llama.cpp performs local text inference.

The native build also contains Qwen3 TTS. That voice implementation has its own GGML/native concerns and must not be confused with the text-brain migration.

## Classification

### A — ARIA identity: preserve

These are product/character behavior and remain independent of where inference runs:

- `AriaPersonality`, expression style and personality prompt policy.
- `ConversationBrain`, `ConversationManager`, `ConversationContext`, perspective and conversation state.
- `AriaMemory` and memory selection/storage behavior.
- `AriaEmotion`, `ConversationMood`, `ExpressionResolver` and avatar expression mapping.
- `RoleplayInterpreter`.
- `ConversationInitiative` and `InitiativePolicy`.
- `ReplyQuality` and `VisibleReplyFilter`.
- `ChatHistory`.
- UI/avatar behavior.
- Useful voice direction, speech output/input and wake-word behavior. Qwen B2 remains isolated and is not part of Cloud text inference.

### B — local brain infrastructure: retain now, removable later

- llama.cpp module and its native libraries/backends.
- `com.arm.aichat.InferenceEngine` and `AiChat` local-engine acquisition.
- GGUF discovery, inspection, import, selection and persistence (`ModelSelection`, model folder, `LAST_MODEL`).
- Local model loading, cleanup, model-ready checks, tensor loading progress and system-prompt loading into llama.
- JNI/native code that exists solely to run the local text LLM.
- Logic whose only purpose is keeping/reconnecting the local GGUF model.

None of these are deleted in Stage 1.

### C — shared / refactor boundary

- Generation orchestration in `MainActivity` currently knows llama states and APIs directly.
- Performance telemetry mixes conversation preparation metrics with GGUF/llama metrics.
- Loading/status UI is expressed in local-model terms.
- Prompt preparation is shared ARIA behavior, but transport/inference invocation must sit behind the brain interface.

These areas should migrate incrementally. Stage 1 adds the seam without a risky rewrite of `MainActivity`.

## New brain boundary

`AriaBrainEngine` is the provider-neutral inference contract. `BrainRequest` carries the prepared prompt and output budget. `BrainState` exposes only ARIA-level lifecycle states: `Disconnected`, `Connecting`, `Ready`, `Generating`, and `Error`. `BrainResponse` is available for non-stream/final metadata as later stages need it.

`LocalBrainEngine` adapts the existing llama `InferenceEngine` to this contract without changing local loading or model-selection behavior. It demonstrates that llama can live behind the same boundary while the current runtime remains intact.

`CloudInferenceEngine` implements the same contract using an injected `CloudBrainClient`. `CloudBrainConfig` contains only provider-neutral connection/time-out configuration. There is deliberately no production HTTP implementation, provider SDK, API key, auth header, or real endpoint in Stage 1.

Target dependency direction:

```text
ARIA identity / conversation preparation
              |
              v
       AriaBrainEngine
        /           \
       v             v
LocalBrainEngine   CloudInferenceEngine
       |             |
   llama.cpp      CloudBrainClient
                     |
               provider adapter (future)
```

`ConversationBrain` must never import HTTP clients, provider SDKs, API-key handling, or provider model names.

## Target Cloud conversation flow

1. User input enters existing ARIA conversation handling.
2. `ConversationBrain` interprets continuity/intent/roleplay.
3. Memory, personality, mood/expression and `ConversationContext` prepare the ARIA prompt.
4. The prepared prompt becomes a `BrainRequest`.
5. `AriaBrainEngine` generates chunks, regardless of Local/Cloud implementation.
6. Existing `VisibleReplyFilter` and `ReplyQuality` validate visible output.
7. Existing emotion/expression logic, history, memory/manager recording and optional voice handle the final ARIA reply.

The Cloud provider therefore supplies inference capacity; it does not become ARIA's identity layer.

## Security boundaries

- No API keys or secrets in source, resources, commits, logs, prompts, tests or `CloudBrainConfig`.
- Mobile clients should not embed long-lived provider credentials. A later architecture should prefer an ARIA-controlled server/broker or short-lived credentials.
- Cloud transport must use TLS and explicit timeouts.
- Provider adapters must be replaceable and must not leak provider-specific response/state types into conversation code.
- Define in a later stage what memories/history are sent remotely; default to the minimum context required for a turn.
- Do not log raw private conversation/memory payloads by default.
- Local fallback, retries and cancellation must not duplicate user turns or corrupt `ChatHistory`.

## Migration strategy

1. **Stage 1 (this stage):** add contracts, Cloud seam, local adapter, tests and documentation. Preserve behavior and all local infrastructure.
2. Later: route generation orchestration through `AriaBrainEngine` while keeping `LocalBrainEngine` as the active implementation; verify parity on device.
3. Add a real Cloud transport behind `CloudBrainClient`, with credentials outside the app/provider-neutral layer.
4. Add an explicit runtime/config choice and Cloud-specific status/telemetry without changing identity components.
5. After Cloud parity and rollback testing, remove GGUF/llama text infrastructure only from the Cloud product path. Keep the protected local branch untouched.

## Risks detected

- `MainActivity` is large and currently owns both identity orchestration and local-engine lifecycle; a broad edit would have high regression risk.
- UI readiness (`modelLoaded`, send-button state, loading overlay) is directly tied to `InferenceEngine.State.ModelReady` and GGUF semantics.
- System-prompt handling is currently performed as part of local model loading. Cloud must not accidentally duplicate personality/context or alter role assignment.
- Streaming filtering/retry logic depends on chunk behavior; provider adapters must preserve cancellation and ordered chunks.
- Native Qwen3 TTS and llama.cpp use different GGML revisions. Removing or changing native libraries during the text migration could break voice even if Cloud inference works.
- Wake-word/foreground-service flows can initiate turns while Activity state changes; Cloud cancellation/reconnection must be designed around that lifecycle.
- Cloud privacy, authentication, cost/rate limits and offline behavior are intentionally unresolved in Stage 1 and must be explicit before production use.

## Stage 1 stop condition

Stage 1 is complete when the provider-neutral brain types and Cloud seam exist, the local adapter demonstrates compatibility, tests cover the new boundary, this migration document exists, and no local infrastructure or protected branch has been modified. No real Cloud provider is connected in this stage.
