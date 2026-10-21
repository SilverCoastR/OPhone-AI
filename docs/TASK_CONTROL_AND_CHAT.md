# Task control and conversations

## Task lifecycle

A task moves through running, waiting, waiting for the user, attention, cancelled, failed, or completed states. A task waiting for the user (after `ask_user` or at its step limit) is not resumed automatically and is not superseded by the next message: that message is recorded as the answer and the same task continues. Task records survive provider page reloads and app process restarts. Cancellation stops native work, terminal jobs, stream recordings, pending event waits, and active model requests where the provider transport supports cancellation.

The model should set measurable success criteria early, checkpoint after meaningful progress, wait for events instead of polling continuously, and call the verification tool before completion.

## Conversations

API conversations are stored locally with provider-neutral messages. Users can create, rename, copy, and delete conversations. Changing provider does not remove the history. A provider credential is stored separately from conversation content.

Protocol frames and tool JSON are presented in a collapsible execution-details view. Normal chat remains readable while tool state is still available for diagnosis.

## Status while OPhone AI is in the background

`TaskProgress` follows every tool call of a task (`LocalToolKernel.call`) and every task save. While a task runs and another app is in front, a small status pill floats at the top of the screen ("OPhone AI is opening an app · step 3 · 0:42"). It is an accessibility overlay, so it needs no extra permission; it is never touchable and is hidden while a screenshot is taken. The ongoing notification shows the same line.

When a task finishes, fails, is blocked or asks the user a question, the app returns to that task's conversation about 1.5 seconds later: the API chat with the conversation selected, or the ChatGPT conversation (without reloading it if it is already open). Cancelled tasks do not trigger a return, and nothing happens when OPhone AI is already on screen. On the ChatGPT platform the same return happens when the page runtime gives up (setup failed, recovery exhausted or a result could not be delivered) while the user is in another app; back on screen, the runtime retries once.

OPhone AI counts as on screen when one of its activities is resumed (tracked with activity lifecycle callbacks, so it works without the accessibility service). The return starts the activity from the accessibility service when it is bound, because a bound accessibility service may start activities from the background. Otherwise Android, or MIUI's "pop up windows while running in background" switch, may block the start, so a heads-up notification ("tap to return to the chat") follows if the app is still not on screen after 1.2 seconds; it is removed as soon as OPhone AI is shown.

## Feedback privacy

Feedback contains the app version, a UTC time, the user's text and, optionally, OPhone AI's own logs. Device model and identifiers, system version, language, region, time zone, IP addresses and location fields or coordinates are removed from the logs before the package is created (`FeedbackActivity.scrub`). Reports go to the address in `BuildConfig.FEEDBACK_EMAIL` through the user's email app as plain text in the message body (no attachment); logs longer than about 150,000 characters keep their beginning and end.

## When OPhone AI comes to the front by itself

OPhone AI comes back to the front on its own only to bring the user back from an app the AI was operating for that task. `TaskProgress.foregroundBlock` checks every case below; when any check fails, a notification that opens the conversation is posted instead:

- OPhone AI is not already on screen, and the user has not pressed Take over.
- The screen is on and unlocked.
- The AI acted outside OPhone AI for this task in this session (`noteAiAction` after each phone action) within the last 15 minutes.
- The app on screen is still the one the AI was operating. If the user switched to another app, OPhone AI stays away.

This applies to the following cases:
- **A task ends or asks a question.** Only tasks that worked in this session count; a task saved again after a restart never brings anything up.
- **The `return_to_chat` tool.** It answers with `returning:false` and the reason.
- **The ChatGPT page runtime gives up while the user is away.**
- **`browser_open`.** The built-in browser comes forward only while the user is in OPhone AI or the AI holds the screen; otherwise the page loads in the background (`browser_shown:false`).
- **A scheduled live-stream watch.** The browser comes forward once per job, and only on an unlocked, lit screen.
- **A scheduled ChatGPT run.** It opens directly when the screen is off or locked (shown over the lock screen) or the user is already in OPhone AI. While the user is using another app it posts a notification instead.

Each decision is logged as `foreground_return`, `foreground_return_skipped` or `browser_kept_in_background`, with the reason, in the diagnostics included in feedback logs.
