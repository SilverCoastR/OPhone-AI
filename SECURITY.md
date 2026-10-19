# Security policy

## Reporting a vulnerability

Please open a GitHub security advisory for vulnerabilities that could expose credentials, cross an app-control boundary, execute commands without user authorization, or disclose private task data. Avoid placing secrets, device identifiers, or exploit details in a public issue.

## Trust boundaries

- Provider credentials are encrypted with Android Keystore and are never included in tool results, logs, exports, or backups.
- The Accessibility service, downloads, package changes, overlay, and root shell are controlled by separate user-facing switches.
- App automation is restricted by a local per-package policy.
- The built-in browser uses a profile separate from the ChatGPT Web session.
- Shell and program execution use the app UID. Root requires an explicit switch and an existing `su` implementation.
- Audit and diagnostic records stay in app-private storage unless the user explicitly exports a file.
- Signed media URLs, authorization headers, cookies, and API keys must be redacted before persistence or display.

OPhone AI is an automation runtime. Users should review enabled capabilities and revoke permissions that are no longer needed.
