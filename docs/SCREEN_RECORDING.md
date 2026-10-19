# Screen and stream recording

OPhone AI exposes two general recording mechanisms.

## Screen recording

Android MediaProjection records the visible screen after the operating system displays its consent prompt. The caller may require expected text to be visible before capture starts, which helps prevent recording the wrong foreground content. Status, stop, duration limits, task ownership, and output-file verification are available through local tools.

## Direct stream recording

The workbench browser can discover FLV, HLS, or MP4 media requests and record a selected stream into the app workspace. It can also wait for a stream to become live within a bounded period. Signed URLs are treated as secrets and are redacted from stored events and user-visible diagnostics.

Both mechanisms are generic. They are not tied to a specific website, account, or application. The agent chooses a method based on available evidence and verifies that the output exists and has nonzero media content before completing the task.
