# Testing

## Web runtime

Run:

```text
node --test tests/runtime.test.mjs
```

The suite covers protocol setup, task restoration, deduplication, hidden transport messages, event delivery and acknowledgement, cancellation, verification gates, recovery, presentation behavior, and the chat-page API used by the ChatGPT platform (queued sends, guidance on results, stop, transcript snapshots).

## Android build

Run the debug and instrumentation builds with the configured Android SDK:

```text
gradle assembleDebug assembleDebugAndroidTest
```

The instrumentation entry point is `ai.ophone.bridge.NativeAgentChecks`. Native checks exercise provider storage, bundled Python, task lifecycle, permissions and policy gates, events, programs, workbench calls, browser behavior, recording state, and root capability reporting.

## Device validation

Before a release, install the newly built APK on clean test devices and verify provider selection, local conversations, Accessibility onboarding, app launching, browser zoom, terminal cancellation, HTTP requests, both recording paths, task recovery, and task termination. Record only aggregate results in public documentation; keep serial numbers, accounts, screenshots, tokens, and private URLs outside the repository.
