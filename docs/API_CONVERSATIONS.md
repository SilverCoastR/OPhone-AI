# Local API conversations

Direct-API conversations are persisted in app-private storage as provider-neutral messages and lightweight metadata. Users can create a conversation, reopen it, copy individual messages, and delete the conversation.

Conversation content is independent from the selected provider, so switching providers does not make the local history unreadable. Credentials are stored separately through the model settings and are never written into the conversation file.

Tool progress is appended as structured status and rendered separately from ordinary assistant text. This keeps the model's reasoning, tool calls, events, and final response inspectable without filling the conversation with raw protocol JSON.
