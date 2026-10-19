# Event feedback and programs

## Event feedback

Native subsystems publish task-scoped events for app foreground changes, tool completion, failures, terminal exit, HTTP completion, recordings, and runtime health. Events use monotonic IDs and explicit delivery acknowledgements. Unacknowledged events remain available after a disconnect.

This design lets the agent wait for meaningful state changes instead of taking repeated screenshots. The preferred evidence order is native completion event, structured state, Accessibility UI tree, then screenshot.

## Reusable programs

The agent can save small JavaScript utilities in an app-private workspace and execute them with JSON input. Programs run in a separate WebView process with a watchdog and JSON-only output. They do not receive the Android bridge or unrestricted network access.

Programs are useful for parsing text, transforming data, coordinating a fixed sequence of approved commands, and preserving a reusable solution discovered during a task. Python, Node.js, Git, and an APK compiler are not bundled; the terminal reports any executables that the Android environment already provides.
