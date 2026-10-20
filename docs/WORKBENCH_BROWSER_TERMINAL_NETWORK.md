# Built-in browser, terminal, and network

## Browser

The browser runs inside OPhone AI with its own profile and cookies. It supports multiple tabs, navigation, back and forward, DOM snapshots, selector-based actions, JavaScript evaluation, screenshots, media discovery, pinch zoom, visible zoom controls, overview mode, and fit-to-width behavior.

Browser events are tied to the current task. The agent should use DOM and navigation state for routine verification and request a screenshot only when visual evidence is needed.

## Terminal

Terminal jobs execute `/system/bin/sh` under the application UID, with job limits, timeouts, streamed output, input, cancellation, and task ownership. The shell is useful for Android commands and file processing available to an ordinary app. Root execution is a separate opt-in path.

## Python

The APK bundles the official python.org CPython Android release (currently 3.14) for `arm64-v8a` and `x86_64`. The `python_run` tool executes inline code or a workspace `.py` file as an ordinary terminal job, so it shares job limits, timeouts, task ownership, `terminal_status`, `terminal_stop`, `terminal.completed`/`terminal.failed` events, and the `terminal_job` verifier. Non-root terminal jobs also find `python3` on `PATH`.

The executable launcher is installed as `libophone_python.so` in the native library directory because ordinary apps may only execute files from there. The standard library is extracted to app-private storage on first use and replaced when the bundled release changes. Python's OpenSSL uses a PEM bundle exported from the Android system trust store. Third-party pure-Python packages can be added with `python3 -m ensurepip` and `python3 -m pip install --user`; they are stored under the workspace `.python` directory. Native-extension packages need Android wheels and are not bundled. 32-bit-only devices install the app but report Python as unavailable.

## Workspace

File operations are confined to an app-private workspace. Paths are normalized before access, and size limits apply to reads, writes, command output, and HTTP responses.

## HTTP

The HTTP tool supports standard methods, request headers, request bodies, redirects, timeouts, bounded response capture, and saving a response into the workspace. Authorization values and signed query parameters must be redacted from logs and events.

## Browser configuration (`browser_config`)

The AI can control the built-in browser per tab. `action=get` reads the current settings; otherwise any of these can be changed, and the page reloads to apply them unless `reload=false`:

- identity: `user_agent` preset `default` (Android WebView), `android_chrome` (WebView marker removed), `desktop_chrome`, `desktop_edge`, `mac_safari`, `iphone_safari`, `ipad_safari`, or `custom` with `custom_user_agent`; `desktop_mode` for a wide desktop layout (desktop Chrome identity unless a preset is given);
- behaviour: `javascript`, `images`, `text_zoom` (50–300), `dom_storage`, `popups`, `media_autoplay`, `force_dark`;
- storage: `cookies`, `third_party_cookies`, `clear_cookies`, `clear_cache`;
- requests: `accept_language` and up to 12 extra `headers` sent with the tab's navigations (`Cookie`, `Authorization`, `Host` and connection headers are refused).

The browser profile stays separate from the ChatGPT page. The tool description asks the AI to use identities for compatibility (desktop sites, mobile layouts, language), not to impersonate a person or get around a site's access controls.
