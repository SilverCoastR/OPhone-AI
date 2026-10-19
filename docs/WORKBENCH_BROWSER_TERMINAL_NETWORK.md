# Built-in browser, terminal, and network

## Browser

The browser runs inside OPhone AI with its own profile and cookies. It supports multiple tabs, navigation, back and forward, DOM snapshots, selector-based actions, JavaScript evaluation, screenshots, media discovery, pinch zoom, visible zoom controls, overview mode, and fit-to-width behavior.

Browser events are tied to the current task. The agent should use DOM and navigation state for routine verification and request a screenshot only when visual evidence is needed.

## Terminal

Terminal jobs execute `/system/bin/sh` under the application UID, with job limits, timeouts, streamed output, input, cancellation, and task ownership. The shell is useful for Android commands and file processing available to an ordinary app. Root execution is a separate opt-in path.

## Workspace

File operations are confined to an app-private workspace. Paths are normalized before access, and size limits apply to reads, writes, command output, and HTTP responses.

## HTTP

The HTTP tool supports standard methods, request headers, request bodies, redirects, timeouts, bounded response capture, and saving a response into the workspace. Authorization values and signed query parameters must be redacted from logs and events.
