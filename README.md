# PRIME P6

**PRIME P6** is an open-source Android action assistant that can chat, accept voice commands, and operate the user's own Android phone through the standard Accessibility Service.

PRIME is the assistant identity. **P6** is the PRIME product-model identity shown to the user. AI inference is performed by an eligible model made available through the user's authorized ChatGPT plan.

## What PRIME P6 does

- Chat directly inside the Android app
- Voice command → Android speech recognition → PRIME
- Offline Persian voice replies with an embedded sherpa-onnx/Piper engine
- **Continue with ChatGPT** for eligible ChatGPT Plus / Pro plan usage
- No OpenAI API key required for the supported open-source plan-sharing flow
- Native Android UI-tree reading through Accessibility Service
- Tap, long press, swipe, scroll, type and replace text
- Open installed apps by friendly name
- Open web URLs
- Press Back, Home, Recents, Notifications and Quick Settings
- Confirm consequential final actions such as sending, publishing, deleting, purchasing or changing security/account settings
- Optional local/LAN MCP Device Bridge for external MCP-compatible agents
- Optional private remote MCP bridge so a user-authorized ChatGPT plugin can reach PRIME without exposing the phone to the public internet
- No root required

## Privacy model

PRIME is designed local-first:

- ChatGPT OAuth tokens are encrypted with the Android Keystore before being stored.
- Access and refresh tokens are never written to source code or app logs.
- Phone actions execute locally on the Android device.
- The optional Device Bridge is off until the user starts it.
- Device Bridge commands require a per-install 256-bit bearer token stored through Android Keystore.
- The Accessibility Service is off until the user enables it.
- PRIME does not need an OpenAI API key.

The ChatGPT-plan OAuth flow does **not** give PRIME access to the user's previous ChatGPT conversations, memories, or ChatGPT files.

## Sign in with ChatGPT

This project implements OpenAI's open-source/local **Sign in with ChatGPT** plan-usage flow:

1. PRIME creates a stable local agent-host identifier.
2. The app starts an HTTP loopback callback on `127.0.0.1`.
3. It opens the system browser with OAuth Authorization Code + PKCE.
4. First registration starts with `dynamic_agent_client` and `agent_name_hint=PRIME`.
5. The issued `oaiapp_...` client ID is retained for later sign-ins.
6. PRIME validates the returned OpenID Connect ID token against OpenAI JWKS.
7. Credentials are encrypted locally.
8. Eligible requests are sent to the public Responses API with `store:false` and `stream:true`.
9. PRIME lists the models available to the connected ChatGPT account and selects an available engine.

Official documentation:

- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference

## Voice

PRIME 6.0.10 speaks Persian independently of Google/Samsung Text-to-Speech. On first opening Voice or testing speech in settings, it downloads the **fa_IR ganji medium** Piper voice pack (64 MiB), verifies its pinned SHA-256 and safely installs the model, tokens and eSpeak language data in app-private storage. Downloads show progress, can be cancelled and can be retried. Allow 260 MB free space during installation.

After installation, synthesis and playback run on the phone without a speech API key or a TTS network request. ChatGPT responses still require internet and an eligible connected account. Android `SpeechRecognizer` provides speech input and may require internet. Its language defaults to **fa-IR**, independently of the phone language; settings can switch input to English and adjust speech speed.

The voice overlay displays the complete response, can be minimized to reveal the app below, and lets the user interrupt playback with the microphone button. Only a completed API response is spoken. The microphone resumes after the final audio samples have actually played. Closing Voice cancels the current agent job.

Settings → **صدای فارسی · تنظیمات و آزمایش** downloads the pack when needed and plays a Persian test phrase. No external TTS engine or separate voice app is required.

Upstream model documentation: https://k2-fsa.github.io/sherpa/onnx/tts/all/Persian/vits-piper-fa_IR-ganji-medium.html

## Android phone actions

The Android action engine is based on the original DroidPilot Accessibility architecture and exposes native actions without screenshot OCR:

- `get_ui_tree`
- `find_element`
- `click_element`
- `tap`
- `long_press`
- `swipe`
- `scroll`
- `type_text`
- `set_text`
- `press_key`
- `wait_for_element`
- `open_app`
- `get_focused`
- screenshot support for the optional bridge

PRIME's internal agent loop refreshes the current UI state between actions and does not claim completion until the observed/action results support it.

Version 6.0.9 routes simple launches such as «برو گوگل», «برو روبیکا» and arbitrary installed app names directly to Android on every turn. They do not depend on a model reply, network quota or earlier conversation failures. Text chat and Voice share one live app resolver and phone controller. Launch results are checked against the foreground app when Accessibility is available; an unverified launch is reported as a request rather than completed work.

Compound requests and follow-ups use the phone planner with a compact, valid screen snapshot and relevant installed app names. Old generic capability disclaimers are excluded from action history. A new generic disclaimer gets one runtime correction; real disconnected permissions, missing apps, protected steps and observed action failures remain explicit.


## Build the Android app

Requirements:

- Android 11+ / API 30+
- JDK 17
- Android SDK 34

```bash
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The first online build downloads the pinned sherpa-onnx 1.13.8 AAR (48 MiB) and verifies its SHA-256. Later builds reuse `android/voice-runtime/`; this generated dependency is not committed to git.

Debug APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

After installation:

1. Open **PRIME**
2. Tap **Continue with ChatGPT**
3. Complete consent in the system browser
4. Tap **Enable** and enable PRIME in Android Accessibility Settings
5. Type a command or tap the microphone

Examples:

```text
تلگرام رو باز کن
برو گوگل
حالا برو روبیکا
برو داخل تلگرام و چت علی رو باز کن
به علی بنویس «ساعت ۸ می‌رسم»
کروم رو باز کن و برو speed.cloudflare.com
تنظیمات رو باز کن و صفحه وای‌فای رو پیدا کن
```

For a consequential final action, PRIME may ask for confirmation. Reply with a confirmation such as `بله` / `آره` / `تایید` to continue.

## Optional PRIME Device Bridge / MCP

The original external automation bridge is preserved as an advanced feature and branded PRIME P6.

```bash
cd mcp-server
npm ci
npm test
npm start
```

The MCP server is named `prime-p6`. The Android app's bridge listens on port `8765` by default after the user explicitly starts it.

The bridge now requires authentication for every connection. In PRIME, open the advanced Device Bridge section and copy the generated **Device Bridge token**. Pass that value as the `authToken` argument to the MCP `connect` tool. The token is generated with 256 bits of entropy, stored encrypted through Android Keystore, and is never written to application logs or source control.

The built-in PRIME chat/voice path does **not** require the MCP bridge.

### Private remote ChatGPT bridge

PRIME 6.0.10 also keeps the original LAN bridge intact while adding an optional
outbound-only remote mode for ChatGPT plugins. The phone registers over HTTPS
using its existing Keystore-backed Device Bridge proof, receives a device-bound
signed MCP URL, stores the returned credential in Android Keystore, and then
maintains a foreground `wss://` connection to the relay.

No inbound phone port, public phone IP, or router port-forward is required.

1. Open PRIME → **پل اتصال دستگاه · پیشرفته**.
2. Tap **وصل کردن PRIME به ChatGPT**.
3. Copy **آدرس خصوصی افزونه ChatGPT**.
4. In ChatGPT Developer mode, create a custom MCP server and paste that private
   HTTPS MCP URL.
5. Keep PRIME Accessibility enabled. The remote connection runs in its own
   foreground service while the user works in ChatGPT or another app.

Each private MCP URL is cryptographically bound to that PRIME installation.
The existing local `stdio` MCP server and LAN Device Bridge remain available
and unchanged.

## Architecture

```text
                    ┌──────────────────────────────┐
                    │         PRIME P6 APK         │
                    │                              │
Voice ─ Speech STT ─┤  Chat / Agent Orchestrator   │
Text ───────────────┤            │                 │
                    │      Context Manager         │
                    │  budget · clipping · recent  │
                    │            │                 │
                    │            ▼                 │
                    │      AI Provider boundary    │
                    │            │                 │
                    │            ▼                 │
                    │   OpenAI Responses Provider  │
                    │   + ChatGPT-plan OAuth       │
                    │                              │
                    │      Tool Runtime / Policy   │
                    │  registry · risk · confirm   │
                    │            │                 │
                    │  Android Action Engine       │
                    │            │                 │
                    │  Accessibility Service       │
                    └────────────┼─────────────────┘
                                 ▼
                    Telegram / Chrome / Settings / Apps

Optional:
External MCP client ↔ PRIME P6 MCP ↔ authenticated WebSocket ↔ Android Action Engine
```

`PrimeAgent` owns conversation and Android-action orchestration. Provider-specific
authentication, model discovery, retries, HTTP transport and Responses SSE parsing
live behind the `AiProvider` boundary so additional providers can be implemented
without coupling them to the agent loop.

Phone actions pass through `PrimeToolRegistry` and `PrimeToolRuntime` before they
reach `PhoneController`. The runtime rejects unregistered commands, assigns a
minimum `ToolRisk`, elevates semantic send/delete/payment-style clicks, and blocks
sensitive or destructive actions until the user has confirmed the task.

`PrimeContextManager` owns only the bounded in-memory provider window; the full
conversation remains in `PrimeChatStore`. It limits request history by message
count and a conservative token estimate, clips oversized messages while preserving
both ends, and keeps recent context for long-running chats.

## Identity

When asked its name, the product answers:

> I am PRIME.

When asked its model:

> P6.

P6 is the PRIME product identity, not a claim that OpenAI exposes a foundation model named P6.

## CI

GitHub Actions builds both:

- Android unit tests, lint and debug APK artifact: **PRIME-P6-APK**
- Android quality reports: **PRIME-quality-reports**
- PRIME P6 MCP TypeScript build and socket integration tests
- A native Persian model synthesis smoke test

See [TESTING.md](TESTING.md) for the physical-device acceptance checks.

## License and upstream

This repository remains under the MIT License. PRIME P6 is derived from the open-source DroidPilot project by Youichi Uda. The original MIT copyright notice is preserved in `LICENSE`.

See `NOTICE.md` for attribution.
