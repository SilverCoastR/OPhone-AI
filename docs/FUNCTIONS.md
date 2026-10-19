# Functions and local tools

The model discovers the tool list at runtime. Major groups are:

- **Task state:** create or resume tasks, save checkpoints, define success criteria, wait for events, verify results, finish, or cancel.
- **Diagnostics:** read structured task diagnostics, execution receipts, runtime health, and the app's own logcat when Android permits it.
- **Device inspection:** report capabilities, list or find installed apps, read the current UI tree, and take a screenshot.
- **UI actions:** tap, swipe, click text, enter text, press Android global actions, and launch apps.
- **Package operations:** request installation or uninstallation when the user has enabled the high-risk capability; Android confirmation still applies.
- **Browser:** open and manage tabs, inspect structured page state, operate DOM targets, evaluate JavaScript, capture a page, and discover media.
- **Terminal and files:** run bounded `/system/bin/sh` jobs, send input, stop jobs, and work inside the app-private workspace.
- **Network:** make HTTP requests with size and timeout limits; sensitive headers are redacted.
- **Programs:** save and run reusable JavaScript helpers with JSON input and output.
- **Recording:** record the visible screen with Android consent or record a discoverable media stream directly.

Each mutating call is associated with a task and call ID. The kernel records a receipt so repeated delivery can be handled safely and queried later.
