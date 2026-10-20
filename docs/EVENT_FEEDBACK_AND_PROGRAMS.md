# Event feedback and programs

## Event feedback

Native subsystems publish task-scoped events for app foreground changes, tool completion, failures, terminal exit, HTTP completion, recordings, and runtime health. Events use monotonic IDs and explicit delivery acknowledgements. Unacknowledged events remain available after a disconnect.

This design lets the agent wait for meaningful state changes instead of taking repeated screenshots. The preferred evidence order is native completion event, structured state, Accessibility UI tree, then screenshot.

## Status reports

Scripts and utilities report their own progress at key points. A terminal or Python job prints lines of the form `@@ophone:report {"stage":…,"message":…,"progress":0..1,"level":"info|warn|error"}`; Python scripts use the bundled helper (`from ophone import report, step`). Reports are kept in the job receipt (`reports`, `report_count`, `last_report_at`, `idle_ms`) and published as `terminal.report` events, immediately for warnings, errors, stage changes and completion, otherwise at most every two seconds per job. Saved JavaScript programs receive a `report(stage, message, progress, level)` function and return their reports with the result. The agent is instructed to add reports to every tool it writes and to investigate a job whose `idle_ms` keeps growing without new reports.

Toast messages from allowed apps are captured by the Accessibility service, returned in action effects and available as `ui.toast` events.

## Reusable programs

The agent can save small JavaScript utilities in an app-private workspace and execute them with JSON input. Programs run in a separate WebView process with a watchdog and JSON-only output. They do not receive the Android bridge or unrestricted network access.

Programs are useful for parsing text, transforming data, coordinating a fixed sequence of approved commands, and preserving a reusable solution discovered during a task. CPython 3 is bundled for terminal and `python_run` jobs (see [Built-in browser, terminal, and network](WORKBENCH_BROWSER_TERMINAL_NETWORK.md)). Node.js, Git, and an APK compiler are not bundled; the terminal reports any executables that the Android environment already provides.
