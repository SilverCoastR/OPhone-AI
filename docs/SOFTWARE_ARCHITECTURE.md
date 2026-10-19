# Software architecture

OPhone AI is a single Android application with three execution surfaces.

## Control layer

`PlatformActivity` selects the provider. `ChatActivity` hosts ChatGPT Web and injects the OPhone AI runtime after the provider page is ready. `ApiChatActivity` connects directly to DeepSeek. Both paths use the same local conversation and task concepts.

## Agent runtime

`ophone-ai-runtime.js` parses structured model calls, submits them to the native bridge, hides protocol messages from the visible conversation, persists checkpoints, resumes interrupted work, and forwards concise events to the model. The protocol identifier is `ophone-ai-local-tools-v1`.

## Native tool kernel

`LocalToolKernel` publishes capability metadata and dispatches phone tools. It applies task scope, risk policy, per-app policy, audit logging, execution receipts, and outcome events before returning a result.

Long-lived task data is stored in app-private files by `AgentStore`, `TaskController`, `ExecutionJournal`, and `AgentEvents`. A model can query status after page reloads or process interruptions.

## Workbench process

The built-in browser, shell jobs, HTTP client, workspace, and direct stream recorder run in the private `:workbench` process. Calls are restricted to the application UID. Workbench events are persisted to an outbox until the main process acknowledges them.

## Program process

Saved JavaScript utilities run in the private `:program` process with JSON input and output, a short watchdog, no Android bridge, and no direct network access. Programs can compose approved workspace commands without receiving arbitrary application internals.

## Android integration

`DeviceAutomationService` provides UI snapshots and gestures. `ScreenCaptureService` implements MediaProjection recording. `BridgeService` keeps the local runtime available. All components are private except the launcher and the Android-required Accessibility service declaration.
