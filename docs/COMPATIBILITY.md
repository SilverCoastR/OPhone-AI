# Compatibility

- Minimum Android version: Android 7.0 / API 24
- Compile and target SDK: 33
- Built-in browser and saved JavaScript programs: Android 9 / API 28 or newer
- Screen recording: Android 5.0 / API 21 or newer, subject to system consent
- Architecture: standard Android application APIs; no required OEM framework, package, account, or system property

Manufacturer-specific background restrictions can still affect any Android application. OPhone AI links to the system settings supplied by the device instead of hard-coding one vendor's settings activity.

Physical testing should cover multiple Android versions, WebView releases, screen sizes, languages, and manufacturers. Device identifiers and private test accounts do not belong in committed fixtures or documentation.
