# Functions and local tools

The model discovers the tool list at runtime. Major groups are:

- **Task state:** create or resume tasks, save checkpoints, define success criteria, wait for events, verify results, finish, or cancel.
- **Diagnostics:** read structured task diagnostics, execution receipts, runtime health, and the app's own logcat when Android permits it.
- **Device inspection:** report capabilities, list or find installed apps, read the current UI tree, and take a screenshot.
- **UI actions:** tap, swipe, click text, enter text, press Android global actions, and launch apps. Each action waits for the screen to settle and returns an `effect`: added and removed visible text, package change, and Toast messages; `no_visible_change` tells the agent to re-inspect rather than repeat. `wait_for_ui` waits for text to appear or disappear, an app to reach the foreground, or the screen to become stable.
- **Control mask and takeover:** while the AI operates another app, a full-screen accessibility overlay blocks the user's touches and shows the current step with a **Take over** button. Taking over removes the mask, makes every phone-action tool return `user_takeover`, and tells the AI (as guidance on API platforms, through the page runtime on ChatGPT). **Hand back** resumes and tells the AI to inspect the screen again. The AI's own gestures pass through the mask; screenshots never show it.
- **Returning to the chat:** `return_to_chat` brings OPhone AI's conversation to the front as soon as the AI no longer needs other apps; the app also returns by itself when a task ends or asks the user.
- **Asking the user:** `ask_user` pauses the task with a question and optional quick answers. The user's next message answers it and the same task continues. When a task reaches its step limit (Settings → Task size limit) it pauses the same way and asks whether to continue.
- **Android system interface:** `android_intent` opens functionality directly through an allowlist of intents (links and app deep links, sharing text or a workspace file, composing SMS or email, dialing, calendar inserts, alarms and timers, camera, `android.settings.*` pages, explicit exported activities). It never places calls, sends messages or pays by itself: the target app shows its own confirmation. `android_resolve` describes what an app handles and its exported activities, or lists the apps that handle an intent. `android_info` returns battery, network, storage, display, audio, locale and power state. `android_control` sets volume, writes the clipboard and toggles the flashlight. Target apps are always checked against the app control policy.
- **Mini apps:** `miniapp_save`, `miniapp_open`, `miniapp_read`, `miniapp_list` and `miniapp_delete` let the agent build small single-file HTML tools with their own screen. A mini app has no network access; it calls phone tools through `OPhone.call(tool, args)`, limited to the tools it declared, after the user approves that list once (changing the list asks again). Installing apps, root, task control and memory writes are never available to mini apps. Each session runs as its own native task, and saved mini apps appear in the chat drawer.
- **Memory:** `memory_search`, `memory_save` and `memory_delete` keep preferences, facts, per-app playbooks and failure notes on the phone. The agent searches memory at the start of a task and records what worked or failed afterwards. Settings → AI memory shows every entry and lets the user delete it.
- **Package operations:** request installation or uninstallation when the user has enabled the high-risk capability; Android confirmation still applies.
- **Browser:** open and manage tabs, inspect structured page state, operate DOM targets, evaluate JavaScript, capture a page, and discover media.
- **Terminal and files:** run bounded `/system/bin/sh` jobs, send input, stop jobs, and work inside the app-private workspace.
- **Python:** run bundled CPython 3 code or workspace scripts as task-owned terminal jobs with `python_run`; `python3` is also on `PATH` inside ordinary terminal jobs.
- **Network:** make HTTP requests with size and timeout limits; sensitive headers are redacted.
- **Programs:** save and run reusable JavaScript helpers with JSON input and output.
- **Recording:** record the visible screen with Android consent or record a discoverable media stream directly.

Each mutating call is associated with a task and call ID. The kernel records a receipt so repeated delivery can be handled safely and queried later.
