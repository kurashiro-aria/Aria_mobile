# ARIA Cloud architecture — Stages 1–2

Branch: `aria-cloud-brain-prototype`

## Baseline and protection

The Cloud prototype was created from commit `6dd516c182e2e9295cd971d526f93eb9944a277a`, the Stage-1 baseline shared with `aria-alpha-0.2-local-ai`. The protected local branch and `main` must not receive Cloud commits or merges.

Stage 1 introduced the provider-neutral brain boundary. Stage 2 proves a complete ARIA conversation path without using a GGUF for response generation. No production HTTP client, provider SDK, API key or external LLM is present.

## Ownership classification

### A — ARIA identity: preserve

`AriaPersonality`, `ConversationBrain`, `ConversationManager`, `ConversationContext`, `AriaMemory`, emotions/mood, `ExpressionResolver`, `RoleplayInterpreter`, initiative policy, `ReplyQuality`, `VisibleReplyFilter`, `ChatHistory`, avatar behavior and useful voice behavior remain ARIA-owned. They are deliberately outside the inference implementation.

### B — local brain infrastructure: retained, removable later from Cloud product path

llama.cpp, `InferenceEngine`, GGUF discovery/import/selection/loading, local model persistence, llama JNI/native backends and local-model lifecycle remain in the repository. Stage 2 does not delete or weaken them.

Qwen B2 TTS is separate native voice infrastructure and is explicitly out of scope for this text-brain migration.

### C — shared/refactor boundary

`AriaBrainEngine`, `BrainRequest`, `BrainState`, `BrainPipeline` and final reply processing form the shared seam. Provider-specific transport must remain behind this boundary.

## Provider-neutral brain contract

`AriaBrainEngine` exposes `state`, streaming `generate(BrainRequest)` and `close()`. `BrainState` is limited to `Disconnected`, `Connecting`, `Ready`, `Generating` and `Error`.

`BrainRequest` contains simple values only: prepared prompt, maximum output tokens and an optional request identifier. It carries no Android object, GGUF handle, HTTP request, provider model type or credential.

`LocalBrainEngine` remains an adapter around llama `InferenceEngine`. `CloudInferenceEngine` remains the future transport seam around `CloudBrainClient`.

## Stage 2 Mock Cloud Brain

`MockCloudBrainEngine` implements the exact same `AriaBrainEngine` contract. It:

- uses no llama.cpp, GGUF, network, API or secret;
- accepts only `BrainRequest`;
- records the last request for tests without logging it;
- simulates latency;
- transitions `Disconnected -> Connecting -> Ready -> Generating -> Ready`;
- can deliberately produce `Generating -> Error` using the test marker `[MOCK_ERROR]`;
- can deliberately produce an empty stream with `[MOCK_EMPTY]`;
- returns to `Ready` after coroutine cancellation;
- deliberately returns deterministic text rather than pretending to be an AI.

The special markers are development/test behavior and are not a provider protocol.

## Stage 2 development runtime

The Cloud prototype branch has an explicit development launcher, `CloudMockActivity`, labelled **ARIA Cloud • Mock**. `MainActivity` and the complete local GGUF runtime remain in the APK but are not the launcher for this branch during Stage 2.

This makes the proof unambiguous: opening the Stage-2 Cloud build does not select, restore or load a GGUF before enabling conversation. Readiness comes from `AriaBrainEngine.state == BrainState.Ready`, not `InferenceEngine.State.ModelReady`.

This launcher switch is intentionally branch-specific and temporary. It is not presented as a production user setting.

## Proven conversation flow

The Stage-2 development path is:

```text
User
 -> ConversationBrain
 -> ConversationContext + AriaPersonality
 -> relevant AriaMemory
 -> mood / ExpressionResolver
 -> BrainPipeline -> BrainRequest
 -> AriaBrainEngine
 -> MockCloudBrainEngine
 -> VisibleReplyFilter / ReplyQuality
 -> ConversationManager
 -> ChatHistory
 -> emotion / avatar
 -> existing Android speech output when voice is enabled
```

`ConversationBrain` still interprets intent, continuity and roleplay. `ConversationContext` still assembles recent history and relevant memories. `BrainPipeline` wraps that prepared ARIA context in the same direct-response personality instruction used by the local path. The Mock never owns personality, memory, emotions, history or reply policy.

A generated answer is recorded through `ChatHistory` and `ConversationManager`, processed by `AriaEmotion`, reflected by the avatar and can reach the existing `LocalSpeechOutput`. Voice B2 itself is not changed.

## Instrumentation

The Stage-2 launcher measures in memory only:

- BrainRequest preparation time;
- delay until generation starts;
- total generation time;
- final provider-neutral state;
- exception class when generation fails.

Raw user prompts, memories and future credentials are not logged by this instrumentation.

## Local dependencies still present

The original `MainActivity` still contains local-only lifecycle code for:

- acquiring `AiChat.getInferenceEngine`;
- `InferenceEngine.State.ModelReady` and other llama states;
- GGUF import/inspection/selection/restoration;
- `loadModel`, `cleanUp` and `setSystemPrompt`;
- tensor/model-loading progress UI;
- local performance metrics and active GGUF naming.

Those dependencies remain intentionally untouched so Stage 2 does not destabilize the proven local implementation. The Stage-2 Cloud launcher bypasses them rather than deleting them.

The repository and Gradle build still include the `:llama` module and native libraries. Therefore Stage 2 proves **runtime generation without GGUF**, not yet an APK physically stripped of llama/GGUF infrastructure.

## Tests added for Stage 2

`MockCloudBrainEngineTest` covers deterministic response/request capture, `Ready -> Generating -> Ready`, simulated error, cancellation, empty response, readiness without llama `ModelReady`, provider-neutral request preparation and generation without a GGUF path.

Existing tests are retained unchanged.

## Security boundaries

- No API keys or secrets in source, resources, commits, logs, prompts or tests.
- The Mock performs no network access.
- Future provider credentials must not be embedded in the mobile client as long-lived secrets.
- Provider response/state types must never leak into `ConversationBrain`.
- Remote context policy must minimize history/memory disclosure.
- Retries/cancellation must not duplicate turns or corrupt history.

## Stage 3 replacement points — not implemented here

Stage 3 should replace the Mock transport with a real provider-neutral remote client while preserving `AriaBrainEngine`, `BrainRequest` and the ARIA-owned preparation/finalization pipeline. It must also decide authentication, privacy/minimum-context policy, timeout/retry/cancellation semantics and Cloud lifecycle UI.

Before removing llama/GGUF from a Cloud artifact, parity must be verified and Qwen B2's separate native GGML requirements must be protected.

## Risks

- There are temporarily two Activities: the preserved local `MainActivity` and the Stage-2 `CloudMockActivity`. Shared orchestration should later be extracted so both front ends cannot drift.
- The original `MainActivity` remains highly coupled to llama lifecycle and GGUF UI. This is known debt, not hidden by the Mock proof.
- Mock output cannot validate conversational quality, tokenization or real network streaming behavior.
- A real provider may stream differently; `VisibleReplyFilter`, cancellation and retry behavior require parity testing.
- Wake-word/foreground-service integration still assumes parts of the local lifecycle and needs deliberate Cloud integration later.
- Qwen B2 and llama use native GGML components with different concerns; removing native text dependencies without checking voice can break TTS.

## Stage 2 stop condition

Stage 2 stops when the explicit Mock Cloud launcher can run the ARIA-owned preparation and finalization pipeline through `AriaBrainEngine` without loading a GGUF for response generation, with state/error/cancellation tests and documentation in place. No HTTP provider is connected, llama/GGUF are not removed, and Stage 3 is not started.
