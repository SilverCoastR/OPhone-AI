# Scheduled tasks

Settings → Scheduled tasks (also in the chat drawer) lists, creates and edits schedules (`ScheduleActivity`, `ScheduleEditActivity`). Each schedule has:

- **What:** a name and the prompt sent to the AI.
- **When:** once at a date and time; every N minutes, hours or days from a start time (at least 5 minutes); or every day at a time.
- **Platform:** ChatGPT web, DeepSeek, OpenAI or Claude, with an optional model and reasoning level. Empty means the platform's current setting.
- **Conversation:** a new one each run; one conversation that every run continues (created by the first run); or an existing conversation.

## Running

`ScheduleStore` keeps the schedules in app-private preferences and one alarm for the earliest due run. It uses an exact alarm when Android allows it, otherwise an inexact one that may be a few minutes late; the list offers the permission. Alarms are restored at app start, after a reboot and after an update. A run missed while the phone was off runs once when they are restored.

When the alarm fires, `ScheduleReceiver` starts `BridgeService`, which runs every due schedule through `ScheduleRunner`:

- **API platforms** run headless in the app process with `DirectModelAgent`. The schedule's model and reasoning level apply to that run only and do not change the platform settings. The target conversation becomes the open one, so the chat shows the run.
- **ChatGPT web** opens the ChatGPT screen with a one-time token. `ChatActivity` waits for the page, chooses the model and reasoning level on the page's own pickers when they differ, then sends the prompt. For a continued conversation it records the conversation id ChatGPT created. The screen is shown over the lock screen for the run. Without "display over other apps", Android 10+ does not let a background app open a screen, so a notification asks the user to tap instead.

A run is skipped when the target conversation is still busy with an earlier run. Each schedule shows its next run and its last result (started, sent, skipped, waiting, failed with a reason). Runs are also written to the diagnostics log.
