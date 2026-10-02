# PRIME 6.0.9 verification

## Automated checks

```bash
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
cd ../mcp-server
npm ci
npm test
```

Android unit tests also exercise repeated app switches, restored history containing false capability denials, local failures followed by successful commands, complete compound tasks, bounded capability correction, cancellation and protected handovers through a local HTTP/SSE fixture. App resolution and compact screen snapshots are tested independently.

Android unit tests cover SSE framing and terminal events, Persian whitespace and refusals, incomplete/error replies, precise app-launch routing, Persian confirmation normalization, complete speech chunking, and archive extraction with path/link restrictions.

MCP integration tests use real local WebSocket connections to check Persian round trips, concurrent connects, disconnects, invalid replies, timeouts, refused connections and host validation.

For the same pinned voice model and native engine on Linux:

```bash
python3 -m pip install sherpa-onnx==1.13.8
python3 scripts/verify_persian_voice.py
```

This verifies nonempty, finite, audible PCM from the Persian model. It does not replace Android audio and microphone testing.

## Physical Android phone acceptance

These checks require a phone and its own ChatGPT sign-in. No live account credentials or phone session are available in CI.

1. Install the APK, connect ChatGPT and enable Accessibility. With the phone language set to English and no Persian system TTS voice, open PRIME voice settings. Download the voice pack and play the Persian test phrase.
2. Cancel the first download, reopen settings and retry. Confirm that only a complete, verified pack becomes ready. Test download failure and insufficient free storage.
3. Ask «سلام، امروز چه کاری می‌تونی انجام بدی؟». Check that the real ChatGPT reply appears in the voice transcript and is spoken in Persian once. Ask a long question and verify the whole reply is read.
4. Without restarting Voice, run «برو تلگرام» → «برو گوگل» → «برو روبیکا» → «حالا برو تلگرام» → «برگرد» several times. Try an arbitrary installed app by its launcher label, then a nonexistent app and another valid launch. Check the visible app against each launch acknowledgment. Say «برو داخل تلگرام و چت علی رو باز کن». Confirm that PRIME continues beyond merely launching Telegram. Say «بنویس سلام» and then cancel/confirm the final send when asked.
5. Ask for a Google search. Verify the UI action results against the actual screen. Test Notifications and Quick Settings as automation targets.
6. Interrupt a spoken reply with the microphone, minimize/expand the overlay, type a multiline instruction, and close Voice during a long operation. Verify that no late action runs after closing it.
7. Mute/unmute media, connect a Bluetooth headset, receive a phone call, revoke microphone/overlay permissions, rotate PRIME, and reopen it. Verify readable error feedback and no repeated playback or duplicate listening.
8. Disconnect VPN/internet during a response. Verify that partial text is not claimed as completion or replayed automatically as a new answer. Restore the connection and retry.
9. Start a long text request and try switching/deleting chats; verify it cannot place a reply in the wrong conversation. Sign out with Voice active and verify the session stops.
10. Build both debug and release variants before a public release. The debug APK is a testing build; production signing credentials are intentionally not part of this repository.
