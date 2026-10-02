# PRIME P6

**PRIME P6** is an open-source Android action assistant that can chat, accept voice commands, and operate the user's own Android phone through the standard Accessibility Service.

PRIME is the assistant identity. **P6** is the PRIME product-model identity shown to the user. AI inference is performed by an eligible model made available through the user's authorized ChatGPT plan.

## What PRIME P6 does

- Chat directly inside the Android app
- Voice command → Android speech recognition → PRIME
- Voice reply through Android Text-to-Speech
- **Continue with ChatGPT** for eligible ChatGPT Plus / Pro plan usage
- No OpenAI API key required for the supported open-source plan-sharing flow
- Native Android UI-tree reading through Accessibility Service
- Tap, long press, swipe, scroll, type and replace text
- Open installed apps by friendly name
- Open web URLs
- Press Back, Home, Recents, Notifications and Quick Settings
- Confirm consequential final actions such as sending, publishing, deleting, purchasing or changing security/account settings
- Optional local/LAN MCP Device Bridge for external MCP-compatible agents
- No root required

## Privacy model

PRIME is designed local-first:

- ChatGPT OAuth tokens are encrypted with the Android Keystore before being stored.
- Access and refresh tokens are never written to source code or app logs.
- Phone actions execute locally on the Android device.
- The optional Device Bridge is off until the user starts it.
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

The current ChatGPT-plan sharing flow does not use direct audio input. PRIME therefore uses Android's built-in `SpeechRecognizer` to turn speech into text, sends the text request through the authorized ChatGPT-plan flow, and reads the final answer with Android `TextToSpeech`.

No separate speech API key is required.

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

## Build the Android app

Requirements:

- Android 11+ / API 30+
- JDK 17
- Android SDK 34

```bash
cd android
./gradlew assembleDebug
```

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
npm run build
npm start
```

The MCP server is named `prime-p6`. The Android app's bridge listens on port `8765` by default after the user explicitly starts it.

The built-in PRIME chat/voice path does **not** require the MCP bridge.

## Architecture

```text
                    ┌──────────────────────────┐
                    │       PRIME P6 APK       │
                    │                          │
Voice ─ Speech STT ─┤  Chat / Agent loop       │
Text ───────────────┤  ChatGPT-plan OAuth      │
                    │          │               │
                    │          ▼               │
                    │  Android Action Engine   │
                    │          │               │
                    │  Accessibility Service   │
                    └──────────┼───────────────┘
                               ▼
                  Telegram / Chrome / Settings / Apps

Optional:
External MCP client ↔ PRIME P6 MCP ↔ WebSocket ↔ Android Action Engine
```

## Identity

When asked its name, the product answers:

> I am PRIME.

When asked its model:

> P6.

P6 is the PRIME product identity, not a claim that OpenAI exposes a foundation model named P6.

## CI

GitHub Actions builds both:

- Android debug APK artifact: **PRIME-P6-APK**
- PRIME P6 MCP TypeScript server

## License and upstream

This repository remains under the MIT License. PRIME P6 is derived from the open-source DroidPilot project by Youichi Uda. The original MIT copyright notice is preserved in `LICENSE`.

See `NOTICE.md` for attribution.
