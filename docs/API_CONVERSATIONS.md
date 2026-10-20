# Local API conversations

Direct-API conversations are persisted in an app-private SQLite database (`api_conversations_v2.db`) as provider-neutral messages and lightweight metadata. Every message is kept and has a stable ID, so the chat page always shows the whole conversation. Earlier versions kept at most 80 messages per conversation, including progress lines; existing conversations are imported once from that store.

Only the context sent to a model is bounded (`ApiConversationStore.contextMessages`): progress lines are left out, older tool output is compacted and the oldest whole turns are dropped until the window fits 80 messages / 384 KB. The stored record is never changed by this.

Users can create, reopen and delete conversations, and act on single messages: copy any message; edit one of their own messages (it and everything after it are removed, then the new text is sent, like ChatGPT); delete one of their own messages together with the AI's reply to it, or delete a single answer. Deleted messages are not sent to any model again. Editing and deleting are unavailable while the AI is running.

Conversation content is independent from the selected provider, so switching providers does not make the local history unreadable. Credentials are stored separately through the model settings and are never written into the conversation file.

Tool progress is appended as structured status and rendered separately from ordinary assistant text. This keeps the model's reasoning, tool calls, events, and final response inspectable without filling the conversation with raw protocol JSON.
