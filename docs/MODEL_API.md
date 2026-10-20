# Model providers

## ChatGPT Web

The embedded ChatGPT page is paired with the local runtime. Tool protocol frames are exchanged through the page composer but hidden from the normal conversation view. The user signs in through the provider page; OPhone AI does not read or export account credentials.

## DeepSeek API

DeepSeek uses its OpenAI-compatible chat-completions endpoint. The API key is encrypted with Android Keystore. Requests, tool calls, cancellation, and local conversation history are handled by the native client.

## OpenAI API

Chat Completions with streaming and tool calls. The base URL is editable (HTTPS only), so OpenAI-compatible services can be used. The model ID is free text; "Choose a model" lists the models the key can use. Requests use `max_completion_tokens`, and `reasoning_effort` only when thinking is enabled. Tool results are sent as text, because Chat Completions tool messages do not carry images.

## Claude API

Anthropic Messages API (`ClaudeClient`). The local Chat Completions-shaped history is converted to Messages blocks: tool calls become `tool_use`, tool results become `tool_result` in a user turn (screenshots as image blocks), consecutive turns of one role are merged, and the system prompt goes to `system`. Extended thinking is not enabled.

Each API platform has its own encrypted key (`ModelApiStore` per `ModelProvider`), endpoint and model settings.

## Switching platforms

Provider selection is saved locally. ChatGPT Web and the API platforms never share work: entering one side cancels the other side's active tasks. The API platforms share the local conversations and hand work to each other:

- Leaving an API platform stops its model loop. Open tasks are not cancelled; they are paused (`waiting_user`) with a question asking whether the next platform should continue. Work resumes only after the user answers in the new platform.
- Before a conversation that another API platform answered is sent to the current platform for the first time, the chat page asks for consent, naming the company that will receive the history. The choice is stored per conversation and platform.
- The receiving platform gets the visible text of earlier turns. Another platform's reasoning traces are never forwarded, and its tool exchanges are converted to a plain-text summary (`DirectModelAgent.forProvider`), because tool-call formats and reasoning replay rules differ between providers. Tool call IDs are normalized for every provider.
- API keys never leave their own platform.

ChatGPT Web conversations live on chatgpt.com and are not imported into the API platforms.
