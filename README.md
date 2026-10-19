# OPhone AI

OPhone AI is an open-source Android agent runtime that lets an AI model inspect the phone, operate apps, use a private browser, run bounded shell jobs, make HTTP requests, save small JavaScript utilities, and verify task outcomes.

The app currently supports ChatGPT through an embedded web session and DeepSeek through its OpenAI-compatible API. Conversations are stored locally and remain available when the user switches providers.

## What it includes

- Android UI automation through an opt-in Accessibility service
- Structured task state, checkpoints, event feedback, execution receipts, and cancellation
- A built-in browser with tabs, DOM actions, JavaScript evaluation, screenshots, media discovery, zoom controls, and a separate cookie profile
- A sandboxed workspace, `/system/bin/sh` jobs, HTTP requests, downloads, and reusable JavaScript utilities
- Screen recording through Android MediaProjection and direct recording of discoverable live media streams
- Per-app control policy, local audit log, capability switches, and an optional root shell gate
- Local conversation history with create, delete, copy, and provider-independent reading

OPhone AI uses standard Android APIs and has no required dependency on a phone manufacturer or vendor framework.

## Build

Requirements:

- JDK 8 or newer compatible with Android Gradle Plugin 3.4
- Android SDK Platform 33 and Build Tools 33.0.2
- Gradle 5.1.1
- Node.js 18 or newer for the web-runtime tests

```text
gradle assembleDebug
node --test tests/runtime.test.mjs
```

The application ID is `ai.ophone.bridge`. The debug APK is generated under `app/build/outputs/apk/debug/`.

## First run

1. Choose ChatGPT Web or DeepSeek API as the control provider.
2. Open **Settings** and enable only the capabilities needed for the task.
3. Grant Accessibility access for UI automation.
4. For DeepSeek, store an API key in the encrypted provider settings.
5. Start a conversation and describe the result you want.

Android still mediates sensitive system actions such as screen-capture consent and app installation. Root execution remains disabled unless the user enables it and a working `su` implementation is already present.

## Documentation

See [docs/README.md](docs/README.md) for architecture, tools, security boundaries, testing, and extension points. A Chinese introduction is available in [README.zh-CN.md](README.zh-CN.md).

## License

Apache License 2.0.
