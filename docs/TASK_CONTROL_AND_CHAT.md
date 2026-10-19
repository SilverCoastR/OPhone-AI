# Task control and conversations

## Task lifecycle

A task moves through running, waiting, attention, cancelled, failed, or completed states. Task records survive provider page reloads and app process restarts. Cancellation stops native work, terminal jobs, stream recordings, pending event waits, and active model requests where the provider transport supports cancellation.

The model should set measurable success criteria early, checkpoint after meaningful progress, wait for events instead of polling continuously, and call the verification tool before completion.

## Conversations

API conversations are stored locally with provider-neutral messages. Users can create, rename, copy, and delete conversations. Changing provider does not remove the history. A provider credential is stored separately from conversation content.

Protocol frames and tool JSON are presented in a collapsible execution-details view. Normal chat remains readable while tool state is still available for diagnosis.
