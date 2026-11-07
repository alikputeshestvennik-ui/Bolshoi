# ChatGPT plan connection

The Settings → Connections → ChatGPT section starts the official browser OAuth flow.
Each device retains its own host ID and encrypted account credentials. This initial
implementation supports one saved account registration per installation. Signing
out clears tokens and attempts remote refresh-token revocation, retaining the
registration for signing in again.

Models are fetched from the signed-in account, then offered with a `chatgpt:` prefix
in Chat, Hands and Background selectors. Existing provider choices are not silently
replaced. Select the ChatGPT models for each role you want to use with your plan.
No API key is required. No paid-provider fallback is performed by this provider.
The local ruble estimate is zero for these requests; actual plan usage is tracked
by OpenAI. The server catalog, not hardcoded aliases, determines available models.

Transport uses Responses HTTP/SSE with `store: false` and `stream: true`, full
history, text and image inputs. A terminal `response.completed` is required.
Existing agent tools still execute in the Android application. Audio services
remain on their existing providers.

## Device acceptance checks

1. Sign in in the external browser; verify the displayed account and consent.
2. Refresh models, select a ChatGPT model for Chat and Hands, send a short message.
3. Run a read-only tool task and send an image; verify history and results.
4. Cancel login and deny plan consent: neither should authorize inference.
5. Sign out, then try the selected ChatGPT model: it must request sign-in.
6. Reconnect the same account and test after access-token renewal.
7. Verify 403/429 and interrupted streams display errors rather than success.

Official protocol references:
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations

The separate AGENT_VERIFY workflow builds debug APKs and runs unit tests without
release secrets or publishing releases. Device login needs the user's account;
compilation alone does not establish eligibility or successful plan inference.
