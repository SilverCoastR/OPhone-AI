# Compatibility

- Minimum Android version: Android 7.0 / API 24
- Compile and target SDK: 33
- Built-in browser and saved JavaScript programs: Android 9 / API 28 or newer
- Bundled Python: 64-bit devices (`arm64-v8a` or `x86_64`), Android 7.0 / API 24 or newer
- Screen recording: Android 5.0 / API 21 or newer, subject to system consent
- Architecture: standard Android application APIs; no required OEM framework, package, account, or system property

Manufacturer-specific background restrictions can still affect any Android application. OPhone AI links to the system settings supplied by the device instead of hard-coding one vendor's settings activity.

Physical testing should cover multiple Android versions, WebView releases, screen sizes, languages, and manufacturers. Device identifiers and private test accounts do not belong in committed fixtures or documentation.

## Display cutouts and landscape

Every activity (via `OPhoneAIApplication` lifecycle callbacks) is laid out over the whole screen including the display cutout (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` on Android 11+, `SHORT_EDGES` on 9–10). In landscape the content uses the cutout side instead of a black strip; the cutout itself is not avoided, by the product owner's choice.

All screens run full screen in portrait and landscape: status and navigation bars are hidden (`WindowInsetsController` on Android 11+, immersive-sticky flags before), re-applied on every resume; a swipe from the edge shows them briefly. The window does not fit system windows (`setDecorFitsSystemWindows(false)` on Android 11+, layout-fullscreen flags before), and the content root replaces the framework padding with the keyboard height only (none for screens that pan), so no strip is reserved for the bars or the cutout in either orientation. `Window.getInsetsController()` is not used: it throws on Android 11 before the decor exists.
