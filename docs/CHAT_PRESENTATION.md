# Chat presentation

Tool transport messages are machine-readable, while the conversation should remain readable to a person. The injected runtime identifies setup, ready, call, result, and event frames and moves them into a collapsible execution-details panel.

The panel shows current task status, tool progress, recovery state, and items that need attention. It starts collapsed and uses accessible controls. A user discussing protocol text as ordinary prose does not cause that message to be hidden.

The fixture in `tests/chat-presentation.html` exercises presentation behavior against `ophone-ai-runtime.js` without invoking phone actions.
