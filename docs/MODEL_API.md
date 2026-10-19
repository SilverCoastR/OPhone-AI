# Model providers

## ChatGPT Web

The embedded ChatGPT page is paired with the local runtime. Tool protocol frames are exchanged through the page composer but hidden from the normal conversation view. The user signs in through the provider page; OPhone AI does not read or export account credentials.

## DeepSeek API

DeepSeek uses its OpenAI-compatible chat-completions endpoint. The API key is encrypted with Android Keystore. Requests, tool calls, cancellation, and local conversation history are handled by the native client.

Provider selection is saved locally. Exiting a provider cancels its active task and returns to the provider chooser. The current release enables ChatGPT Web and DeepSeek; additional provider adapters can implement the same task and tool interface.
