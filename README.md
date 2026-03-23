# Sentinel AI — Real-Time Scam Detection for Android

<p align="center">
  <img src="app/src/main/res/drawable/ic_sentinel_logo.png" width="120" alt="Sentinel AI Logo"/>
</p>

<p align="center">
  <b>Protect yourself from phone scams — AI-powered, privacy-first, fully on-device.</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2010%2B-brightgreen?style=flat-square"/>
  <img src="https://img.shields.io/badge/Language-Kotlin-blueviolet?style=flat-square"/>
  <img src="https://img.shields.io/badge/AI-Whisper.cpp%20%7C%20Vosk-orange?style=flat-square"/>
  <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square"/>
</p>

---

## What is Sentinel AI?

Sentinel AI is an Android anti-scam guardian that listens to **the caller's voice** in real-time during VoIP calls (WhatsApp, LINE, Telegram, etc.), transcribes what they say using on-device or cloud Speech-to-Text, and instantly alerts you when scam patterns are detected — all without sending your private data anywhere.

> **Designed for Thailand** — keyword detection, STT, and UI are fully Thai-localized.

---

## Key Features

| Feature | Description |
|---------|-------------|
| **Call Audio STT** | Captures the remote party's voice via `AudioPlaybackCaptureConfiguration` (MediaProjection) — grant once, no popup per call |
| **Real-time Scam Detection** | Matches transcripts against a curated scam keyword database (`scammerkeyword.json`) |
| **Caller Overlay** | Shows caller risk level, report count, and carrier info over the call screen |
| **Blacklist Lookup** | Checks phone numbers against community-reported scam databases |
| **Link Scanner** | Analyzes URLs for phishing indicators using TLS/DNS/redirect analysis |
| **Number Checker** | Enriches phone numbers with risk scores, known scenarios, and region info |
| **Offline STT** | Vosk-based fully offline speech recognition — no internet required |
| **Cloud STT** | Self-hosted Whisper.cpp or Google Cloud STT for higher accuracy |
| **Voice Activity Detection** | SileroVAD filters silence so only real speech is sent for transcription |
| **Activity Log** | Full audit trail of all detected events with risk levels and scores |
| **Auto Call Screening** | Auto-rejects calls rated CRITICAL using `CallScreeningService` |
| **Privacy-first** | All audio processed ephemerally — no recordings stored or uploaded |

---

## How Call Audio Capture Works

```
┌─────────────────────────────────────────────────────────────┐
│                     Incoming VoIP Call                      │
│            (WhatsApp / LINE / Telegram / etc.)              │
└──────────────────────────┬──────────────────────────────────┘
                           │  Remote party audio stream
                           ▼
         AudioPlaybackCaptureConfiguration
         (USAGE_VOICE_COMMUNICATION via MediaProjection)
                           │
                           ▼  3-second chunks with 1s overlap
                    SileroVAD Filter
                  (skip silent chunks)
                           │
                           ▼
              RawSttClient → Whisper.cpp
         (https://voice.smarthomeus3r.space/stt)
                           │
                           ▼
              ScamKeywordMatcher
         (pattern matching on Thai text)
                           │
              ┌────────────┴─────────────┐
              ▼                          ▼
     OverlayController           GuardianEventStore
   (live transcript UI)         (event logging + risk)
```

### Why no popup on every call?

MediaProjection permission is requested **once during app setup** and the live `MediaProjection` object is kept alive in `MediaProjectionHolder` for the entire app session. When a call starts, `CallModeMonitor` starts `CallPlaybackCaptureService` directly — no screen-share dialog, no friction.

On **Android 14+** (single-use tokens), the `MediaProjection` object itself is cached and reused. The `AudioRecord` is recreated per call while the projection stays alive.

---

## Architecture

```
┌──────────────────────────────────────────────────┐
│                 SentinelGuardianService           │
│               (START_STICKY foreground)           │
│                                                  │
│   ┌──────────────────┐   ┌─────────────────────┐ │
│   │  CallModeMonitor  │   │ MediaProjectionHolder│ │
│   │ (PhoneState +     │   │  (live MP object)   │ │
│   │  playback/mic)    │   └─────────────────────┘ │
│   └────────┬─────────┘                            │
└────────────│─────────────────────────────────────┘
             │ call detected
             ▼
   CallPlaybackCaptureService  ──►  RawSttClient (Whisper.cpp)
   (foreground, MediaProjection)     ScamKeywordMatcher
             │ if MP unavailable          │
             ▼                            ▼
   Mic + SpeechTestController      OverlayController
   (Android SpeechRecognizer)      GuardianEventStore
```

### Core Services

| Service | Role |
|---------|------|
| `SentinelGuardianService` | Persistent foreground service; owns `CallModeMonitor` |
| `CallPlaybackCaptureService` | Captures call playback audio, runs STT pipeline |
| `IncomingCallOverlayService` | Shows caller info overlay on ring/answer |
| `SentinelCallScreeningService` | Auto-reject CRITICAL risk callers |
| `SentinelAccessibilityService` | Monitors chat apps for scam text |

### STT Backends

| Backend | Type | Notes |
|---------|------|-------|
| **Whisper.cpp** (self-hosted) | Cloud | `voice.smarthomeus3r.space/stt` · Thai (th) |
| **Vosk** | Offline | Bundled model in `assets/models/vosk-model.zip` |
| **Google Cloud STT** | Cloud | `th-TH`, requires `STT_API_KEY` |
| **Android SpeechRecognizer** | System | Mic-only fallback, requires active internet |

---

## Permissions

| Permission | Purpose |
|-----------|---------|
| `RECORD_AUDIO` | Mic fallback STT |
| `SYSTEM_ALERT_WINDOW` | Overlay alerts during calls |
| `READ_PHONE_STATE` | Detect incoming/outgoing calls |
| `READ_CALL_LOG` / `READ_SMS` | Scan recent activity for scam patterns |
| `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` | Capture remote-party call audio |
| `FOREGROUND_SERVICE_TYPE_MICROPHONE` | Mic-based STT service |
| `POST_NOTIFICATIONS` | Service and alert notifications |

---

## Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- Android device / emulator running **Android 10+** (API 29+)
- Self-hosted Whisper.cpp server **or** Google Cloud STT API key (optional — offline Vosk works out of the box)

### Build Configuration

Add to `local.properties` or `gradle.properties`:

```properties
WHISPER_CPP_API_KEY=your_key_here
STT_API_KEY=your_google_cloud_key_here   # optional
OPENAI_API_KEY=your_openai_key_here      # optional (pressure analysis)
```

These are injected via `BuildConfig` at compile time and never hardcoded.

### Build & Run

```bash
git clone https://github.com/noonzx2552/scammerdetect.git
cd scammerdetect
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### First-Time Setup

1. Open **Sentinel AI**
2. Grant all permissions on the setup screen — pay special attention to **Call Audio Capture** (the MediaProjection grant — needed for remote-party STT)
3. Tap **Continue** — the Guardian service starts automatically
4. Make or receive a call — the live transcript overlay appears on your screen

> **Tip:** If you skip the Call Audio Capture grant, Sentinel falls back to microphone STT (captures your own voice only). You can re-grant it anytime via the setup screen.

---

## Project Structure

```
app/src/main/java/com/sentinel/ai/
├── ai/
│   ├── CloudSttClient.kt          # Google Cloud STT
│   ├── OfflineStt.kt              # Vosk offline STT
│   ├── RawSttClient.kt            # Whisper.cpp (base64 PCM)
│   ├── SileroVad.kt               # Voice Activity Detection (ONNX)
│   ├── WhisperCppSttClient.kt     # Whisper.cpp (multipart)
│   └── WhisperEngine.kt           # STT facade (offline → cloud)
├── model/
│   ├── GuardianEvent.kt           # Event model
│   ├── GuardianEventStore.kt      # In-memory event store
│   └── RiskLevel.kt               # SAFE / WARNING / CRITICAL
├── security/
│   ├── LinkChecker.kt             # URL phishing analysis
│   └── ScamKeywordMatcher.kt      # Pattern matching engine
├── service/
│   ├── CallModeMonitor.kt         # Call state → capture orchestration
│   ├── CallPlaybackCaptureService.kt  # Remote-party voice STT
│   ├── IncomingCallOverlayService.kt  # Caller risk overlay
│   ├── InternalAudioCaptureService.kt # General audio capture
│   ├── SentinelAccessibilityService.kt
│   ├── SentinelCallScreeningService.kt
│   └── SentinelGuardianService.kt
├── ui/
│   ├── CallMediaProjectionActivity.kt
│   ├── CheckLinkActivity.kt
│   ├── CheckNumberActivity.kt
│   ├── DashboardActivity.kt
│   ├── HomeActivity.kt
│   ├── ProfileActivity.kt
│   ├── ScanOptionsActivity.kt
│   └── SetupActivity.kt
└── utils/
    ├── MediaProjectionHolder.kt   # Live MediaProjection singleton
    ├── MediaProjectionStore.kt    # Cached (resultCode, data) pair
    ├── OverlayController.kt       # WindowManager overlay
    ├── PlaybackCaptureController.kt
    └── SpeechTestController.kt    # Android SpeechRecognizer wrapper
```

---

## Scam Keyword Detection

Keywords are defined in `assets/scammerkeyword.json` — a structured list of Thai scam scenarios (call center fraud, investment scams, government impersonation, etc.) with associated trigger phrases. `ScamKeywordMatcher` loads this at runtime and scores transcripts against all patterns.

---

## Privacy

- **No audio is recorded or stored** — PCM buffers are processed and discarded
- **No personal data leaves the device** by default (Vosk mode is 100% offline)
- **STT server communication** is opt-in and uses encrypted HTTPS
- The Accessibility Service reads only on-screen text within allowed apps

---

## Contributing

Pull requests are welcome. Please open an issue first to discuss proposed changes.

1. Fork the repo
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Commit changes: `git commit -m "Add your feature"`
4. Push: `git push origin feature/your-feature`
5. Open a Pull Request

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

<p align="center">
  Built to protect people from phone scammers
</p>
