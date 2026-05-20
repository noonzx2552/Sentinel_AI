# Sentinel AI

<p align="center">
  <img src="app/src/main/res/drawable/ic_sentinel_logo.png" width="120" alt="Sentinel AI Logo"/>
</p>

<p align="center">
  <b>Android anti-scam guardian สำหรับตรวจจับมิจฉาชีพจากสายโทรศัพท์ ข้อความ ลิงก์ เบอร์โทร และ QR code</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2010%2B-brightgreen?style=flat-square"/>
  <img src="https://img.shields.io/badge/Language-Kotlin-blueviolet?style=flat-square"/>
  <img src="https://img.shields.io/badge/AI-Whisper.cpp%20%7C%20Vosk%20%7C%20SileroVAD-orange?style=flat-square"/>
  <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square"/>
</p>

---

## ภาพรวมโปรเจกต์

Sentinel AI เป็นแอป Android ภาษา Kotlin ที่ออกแบบมาเพื่อช่วยป้องกันการหลอกลวงในชีวิตประจำวัน โดยเน้นบริบทของผู้ใช้ไทยเป็นหลัก ระบบรวมหลายวิธีตรวจจับไว้ในแอปเดียว ได้แก่ การฟังเสียงระหว่างสาย, ถอดเสียงเป็นข้อความ, ตรวจ keyword มิจฉาชีพ, ประเมินความเสี่ยงของข้อความในแอปแชต, ตรวจลิงก์ phishing, ตรวจเบอร์โทรจากข้อมูลภายในและ blacklist ภายนอก, สแกน QR code และแสดงคำเตือนแบบ overlay ทับหน้าจอขณะใช้งาน

แกนหลักของแอปคือ foreground service ที่ทำงานต่อเนื่องในพื้นหลัง เมื่อพบสายโทรศัพท์หรือกิจกรรมที่น่าสงสัย ระบบจะเก็บข้อมูลแบบชั่วคราว วิเคราะห์ความเสี่ยง แล้วแจ้งเตือนผู้ใช้ผ่าน overlay, activity log และหน้าจอสรุปผล โดยพยายามให้ข้อมูลส่วนตัวอยู่บนเครื่องมากที่สุด และใช้ cloud เฉพาะส่วนที่ตั้งค่าไว้ เช่น Whisper.cpp STT, Google STT, phone reputation API หรือ blacklist API

---

## ฟีเจอร์หลัก

| ฟีเจอร์ | รายละเอียด |
| --- | --- |
| Real-time call monitoring | ตรวจจับสถานะสายเข้า/สายออก แล้วเริ่มระบบเฝ้าระวังอัตโนมัติ |
| Playback audio capture | ใช้ `MediaProjection` + `AudioPlaybackCaptureConfiguration` เพื่อจับเสียงปลายสายจาก VoIP/call audio เมื่อได้รับสิทธิ์แล้ว |
| Mic fallback | หากจับเสียง playback ไม่ได้ จะ fallback ไปใช้ไมโครโฟนผ่าน Android SpeechRecognizer |
| Speech-to-Text | รองรับ self-hosted Whisper.cpp, Google Cloud STT, Vosk offline และ Android SpeechRecognizer fallback |
| Voice Activity Detection | ใช้ Silero VAD บน ONNX Runtime เพื่อตัดช่วงเงียบก่อนส่งไป STT |
| Scam keyword matching | ตรวจข้อความถอดเสียงเทียบกับ `scammerkeyword.json` ที่มี scenario และ variant ภาษาไทย |
| Caller overlay | แสดงข้อมูลผู้โทร ความเสี่ยง เหตุผล และ live transcript เหนือหน้าจอ |
| Critical alert | แจ้งเตือนระดับรุนแรงเมื่อพบ pattern อันตราย |
| Chat app monitoring | Accessibility Service อ่านข้อความจากแอปที่อนุญาต เช่น LINE, Messenger, Instagram, WhatsApp แล้วประเมินความเสี่ยง |
| Link checker | วิเคราะห์ URL จาก HTTPS/TLS, certificate, redirect, domain age, TLD, subdomain, phishing keyword, security headers และ content type |
| Number checker | ตรวจรูปแบบเบอร์, region, carrier, line type, prefix ไทย, external reputation และรายงาน blacklist |
| QR scanner | ใช้ CameraX + ML Kit Barcode Scanning อ่าน QR/barcode แล้วส่งผลไปตรวจต่อ |
| Call log scan | สแกนประวัติสายเพื่อหาเบอร์ที่อาจเสี่ยง |
| Activity log | เก็บเหตุการณ์ที่ตรวจพบพร้อม source, content, score, risk level และ timestamp |
| Onboarding/setup | หน้าจอขอ permission สำคัญ เช่น overlay, microphone, notification, phone state, accessibility, call audio capture |
| Localization | มี resource ภาษาไทยใน `values-th` และ base activity สำหรับปรับภาษา |

---

## Tech Stack

| ส่วน | เทคโนโลยี |
| --- | --- |
| Platform | Android 10+ หรือ API 29+ |
| Language | Kotlin, Java 17 target |
| UI | Android Views, XML layouts, ViewBinding, Material Components, AppCompat |
| Async | Kotlin Coroutines |
| HTTP | OkHttp |
| STT | Whisper.cpp API, Google Cloud STT, Vosk Android, Android SpeechRecognizer |
| VAD | Silero VAD ผ่าน ONNX Runtime Android |
| Phone parsing | `libphonenumber-android` |
| Database | Room สำหรับ `GuardianEvent` |
| Camera/QR | CameraX + Google ML Kit Barcode Scanning |
| Build | Gradle Android plugin, Kotlin kapt |

---

## โครงสร้างโปรเจกต์

```text
.
├── app/                              # Android application module หลัก
│   ├── build.gradle                  # Android config, dependencies, BuildConfig keys
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   # permissions, activities, services, receivers
│       │   ├── assets/               # scam keyword, VAD model, STT model zip
│       │   ├── java/com/sentinel/ai/
│       │   │   ├── ai/               # STT, NLP, VAD, risk scoring
│       │   │   ├── model/            # GuardianEvent, RiskLevel, Room database
│       │   │   ├── receiver/         # boot, incoming call, outgoing call receivers
│       │   │   ├── security/         # link/number/domain/cert/blacklist/keyword checker
│       │   │   ├── service/          # foreground services, call capture, accessibility
│       │   │   ├── ui/               # activities, bottom nav, screens, adapters
│       │   │   └── utils/            # permissions, overlay, prefs, media projection, helpers
│       │   └── res/                  # layouts, drawables, strings, themes
│       ├── debug/                    # debug assets และ debug scam model
│       └── androidTest/              # instrumentation tests
├── blacklistseller_bypassapi/         # Python helper สำหรับค้น Blacklistseller แบบ bypass Turnstile
├── gradle/                            # Gradle wrapper
├── scammerkeyword.json                # keyword database ที่ root
├── tiny_wangchan_student_safe.zip     # model artifact ภายนอกที่เก็บไว้ใน repo
├── tokenizers-0.36.0.jar              # tokenizer jar artifact
└── vosk.aar                           # local Vosk AAR placeholder/artifact
```

---

## Package สำคัญใน Android App

### `com.sentinel.ai.ai`

ส่วนนี้ดูแล AI/NLP/STT pipeline:

| ไฟล์ | หน้าที่ |
| --- | --- |
| `RawSttClient.kt` | ส่ง PCM 16-bit ไปยัง Whisper.cpp endpoint สำหรับถอดเสียง |
| `WhisperCppSttClient.kt` | client อีกแบบสำหรับ Whisper.cpp แบบ multipart |
| `CloudSttClient.kt` | client สำหรับ Google Cloud Speech-to-Text |
| `OfflineStt.kt` | ใช้ Vosk model แบบ offline |
| `SileroVad.kt` | โหลด `silero_vad.onnx` แล้วตรวจว่าช่วงเสียงมี speech หรือไม่ |
| `RiskScoring.kt` | ประเมิน score จาก keyword, pressure, interruption และ logic conflict |
| `NLPInference.kt` | จัด intent คร่าว ๆ เช่น safe, phishing, threat, reward |
| `ScamModel.kt` | ชั้นโมเดลสำหรับการประเมิน scam เพิ่มเติม |

### `com.sentinel.ai.security`

ส่วนตรวจความปลอดภัยของ input:

| ไฟล์ | หน้าที่ |
| --- | --- |
| `ScamKeywordMatcher.kt` | โหลด `assets/scammerkeyword.json` แล้ว match transcript กับ canonical/variant keyword |
| `LinkChecker.kt` | วิเคราะห์ URL แบบละเอียด เช่น HTTPS, TLS, certificate, redirect, domain age, phishing content |
| `NumberChecker.kt` | วิเคราะห์เบอร์โทรด้วย libphonenumber, external API และ blacklist report |
| `DomainAnalyzer.kt` | อ่านข้อมูลโดเมน เช่น registration date |
| `CertProbeClient.kt` | probe certificate/TLS เมื่อ OkHttp handshake ไม่พอ |
| `BlacklistSellerClient.kt` | เรียก blacklist API เพื่อหา report ของเบอร์ |

### `com.sentinel.ai.service`

service คือแกน background/runtime ของแอป:

| Service | หน้าที่ |
| --- | --- |
| `SentinelGuardianService` | foreground service หลัก เริ่ม `CallModeMonitor` และอยู่แบบ `START_STICKY` |
| `CallModeMonitor` | ฟังสถานะสายจาก `TelephonyManager` แล้วเลือก playback capture หรือ mic fallback |
| `CallPlaybackCaptureService` | จับเสียง playback ระหว่างสาย แบ่งเสียงเป็น chunk 3 วินาที overlap 1 วินาที ส่งเข้า VAD/STT/keyword matcher |
| `IncomingCallOverlayService` | แสดงข้อมูลผู้โทรตอนมีสายเข้า |
| `SentinelCallScreeningService` | ใช้ Android CallScreeningService สำหรับสายที่มีความเสี่ยงสูง |
| `SentinelAccessibilityService` | ตรวจข้อความบนหน้าจอในแอปที่อนุญาต แล้วแสดง warning/critical overlay |
| `InternalAudioCaptureService` | service สำหรับจับเสียงภายในทั่วไป |
| `InternalAudioPressureService` / `PressureSpeechEngine` | วิเคราะห์รูปแบบคำพูดกดดันหรือเร่งรัด |

### `com.sentinel.ai.ui`

หน้าจอหลักของแอป:

| Activity/Component | หน้าที่ |
| --- | --- |
| `SplashActivity` | จุดเข้าแอป |
| `IntroActivity` | onboarding/intro |
| `PrivacyActivity` | อธิบาย privacy และแนวทางใช้ข้อมูล |
| `SetupActivity` | ขอ permission และตั้งค่าระบบป้องกัน |
| `HomeActivity` | หน้าแรกและสถานะการป้องกัน |
| `CheckLinkActivity` | ตรวจ URL และแสดง score/issues |
| `CheckNumberActivity` | ตรวจเบอร์โทรและ blacklist reports |
| `CallLogScanActivity` | สแกน call log |
| `ActivityLogActivity` | แสดง event log และล้างประวัติ |
| `ScanOptionsActivity` | รวมตัวเลือกการสแกน |
| `QrScannerActivity` | สแกน QR/barcode ด้วย CameraX + ML Kit |
| `ProfileActivity` | ตั้งค่าโปรไฟล์ ภาษา ธีม และข้อมูลผู้ใช้ |
| `DashboardActivity` | dashboard/summary view |
| `CriticalAlertActivity` | หน้าจอแจ้งเตือนภัยรุนแรง |
| `navigation/*` | bottom navigation state/controller/view |

### `com.sentinel.ai.utils`

utility สำคัญ:

| ไฟล์ | หน้าที่ |
| --- | --- |
| `OverlayController.kt` / `OverlayManager.kt` | สร้างและควบคุม overlay ด้วย WindowManager |
| `OverlayGatekeeper.kt` | คุมเงื่อนไขการแสดง overlay |
| `MediaProjectionHolder.kt` | เก็บ `MediaProjection` object ใน memory เพื่อ reuse |
| `MediaProjectionStore.kt` | cache `resultCode` และ `Intent` ของ MediaProjection |
| `PermissionUtils.kt` | ตรวจ permission ต่าง ๆ |
| `ProtectionPrefs.kt`, `ProfilePrefs.kt`, `OnboardingPrefs.kt` | SharedPreferences สำหรับสถานะผู้ใช้และการตั้งค่า |
| `KnownNumberRepository.kt` | lookup/heuristic เบอร์ที่รู้จัก |
| `ContactLookup.kt` | หา contact name จากเครื่อง |
| `LanguageManager.kt` | จัดการภาษา |
| `NotificationHelper.kt` | notification channel และ foreground notification |
| `MicCaptureManager.kt`, `PlaybackCaptureController.kt` | helper สำหรับ audio capture |

---

## Flow การทำงานหลัก

### 1. เปิดแอปครั้งแรก

```text
SplashActivity
  -> IntroActivity / PrivacyActivity
  -> SetupActivity
  -> ขอ permission: mic, overlay, notification, phone state, accessibility, media projection
  -> เริ่ม SentinelGuardianService
  -> HomeActivity
```

`SetupActivity` เป็นจุดสำคัญ เพราะ permission หลายตัวของ Android ต้องให้ผู้ใช้เปิดเอง เช่น overlay, accessibility และ MediaProjection สำหรับ call audio capture

### 2. ตรวจสายโทรศัพท์/VoIP แบบ real-time

```text
SentinelGuardianService
  -> CallModeMonitor.listen()
  -> พบ CALL_STATE_RINGING / CALL_STATE_OFFHOOK
  -> แสดง caller overlay
  -> ถ้ามี MediaProjection:
       CallPlaybackCaptureService.startHeld()
       AudioRecord + AudioPlaybackCaptureConfiguration
       3s PCM chunk + 1s overlap
       SileroVad
       RawSttClient / Whisper.cpp
       ScamKeywordMatcher
       OverlayController + GuardianEventStore
     ถ้าไม่มีหรือ fail:
       SpeechTestController / Android SpeechRecognizer
       ScamKeywordMatcher
       OverlayController + GuardianEventStore
```

จุดเด่นคือระบบพยายาม reuse `MediaProjection` จาก `MediaProjectionHolder` เพื่อไม่ให้ผู้ใช้ต้องกดยืนยัน screen/audio capture ทุกครั้ง โดยเฉพาะ Android 14+ ที่ token มีข้อจำกัดแบบ single-use มากขึ้น

### 3. ตรวจข้อความในแอปแชต

```text
SentinelAccessibilityService
  -> อ่าน AccessibilityEvent จาก package ที่อนุญาต
  -> รวมข้อความบน event
  -> RiskScoring.score()
  -> SAFE / WARNING / CRITICAL
  -> GuardianEventStore.addEvent()
  -> OverlayController.showWarning() หรือ showCritical()
```

แอปที่ถูก monitor ในโค้ดปัจจุบันคือ LINE, Messenger, Facebook, Instagram และ WhatsApp

### 4. ตรวจลิงก์

```text
CheckLinkActivity
  -> LinkChecker.check(input)
  -> normalize URL
  -> DNS/IP lookup
  -> HTTPS/TLS/certificate inspection
  -> redirect analysis
  -> RDAP/domain age/country lookup
  -> phishing heuristics
  -> score 0-100
  -> SAFE / CAUTION / DANGER
```

ตัวอย่าง heuristic ที่ `LinkChecker` ใช้ ได้แก่ domain ใหม่, TLD เสี่ยง, URL shortener, IP literal, punycode, subdomain ลึกผิดปกติ, path/login credential harvesting, query parameter เสี่ยง, missing security headers และ content type ที่เหมือนไฟล์ download อันตราย

### 5. ตรวจเบอร์โทร

```text
CheckNumberActivity
  -> NumberChecker.check(input)
  -> normalize/parse ด้วย libphonenumber
  -> วิเคราะห์ region/type/prefix
  -> external phone validation API
  -> BlacklistSellerClient / blacklist API
  -> score 0-100
  -> SAFE / CAUTION / DANGER
```

สำหรับเบอร์ไทย ระบบมี heuristic เพิ่มเติม เช่น prefix บางกลุ่ม, mock test number ใน debug mode และข้อมูล report จาก blacklist API ที่ parse เป็นจำนวนรายงาน รายละเอียด และยอดเสียหายรวมถ้ามี

### 6. สแกน QR

```text
QrScannerActivity
  -> ขอ CAMERA permission
  -> CameraX Preview + ImageAnalysis
  -> ML Kit BarcodeScanner
  -> ส่ง rawValue กลับไปหน้าที่เรียก
```

---

## Risk Level และ Event Model

เหตุการณ์ที่ระบบตรวจพบใช้ model หลักคือ `GuardianEvent`

```kotlin
data class GuardianEvent(
    val source: String,
    val content: String,
    val score: Int,
    val riskLevel: RiskLevel,
    val timestamp: Long = System.currentTimeMillis(),
    val id: Int = 0
)
```

`RiskLevel` มี 3 ระดับ:

| Level | ความหมาย |
| --- | --- |
| `SAFE` | ยังไม่พบสัญญาณอันตราย |
| `WARNING` | มีพฤติกรรมหรือข้อความน่าสงสัย |
| `CRITICAL` | พบ keyword/scenario หรือ pattern ที่ควรเตือนทันที |

ใน runtime ปัจจุบันมีทั้ง `GuardianEventStore` แบบ in-memory LiveData และ `GuardianDatabase` แบบ Room สำหรับเก็บ event ลงฐานข้อมูล ซึ่งเหมาะต่อการต่อยอดให้ activity log persistent มากขึ้น

---

## Assets และ Model

| Path | รายละเอียด |
| --- | --- |
| `app/src/main/assets/scammerkeyword.json` | ฐาน keyword/scenario ภาษาไทยที่ `ScamKeywordMatcher` ใช้ |
| `scammerkeyword.json` | สำเนา keyword database ที่ root |
| `app/src/main/assets/silero_vad.onnx` | ONNX model สำหรับ voice activity detection |
| `app/src/main/assets/models/vosk-model.zip` | Vosk model สำหรับ offline STT |
| `app/src/main/assets/models/README.txt` | คำแนะนำการวาง Vosk model zip |
| `app/src/debug/assets/models/scam_model.zip` | debug scam model |
| `tiny_wangchan_student_safe.zip` | artifact โมเดลภาษาไทยที่อยู่ระดับ root |
| `tokenizers-0.36.0.jar` | tokenizer jar สำหรับ debug dependency |

---

## Permissions ที่ใช้

| Permission | ใช้เพื่อ |
| --- | --- |
| `RECORD_AUDIO` | mic fallback และ speech recognition |
| `SYSTEM_ALERT_WINDOW` | แสดง overlay เตือนบนหน้าจอ |
| `INTERNET` / `ACCESS_NETWORK_STATE` | เรียก STT/API/lookup ภายนอก |
| `FOREGROUND_SERVICE` | ให้ guardian/capture service ทำงานในพื้นหลัง |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | จับเสียง playback ผ่าน MediaProjection |
| `FOREGROUND_SERVICE_MICROPHONE` | service ที่ใช้ไมโครโฟน |
| `POST_NOTIFICATIONS` | notification ของ foreground service และ alerts |
| `READ_PHONE_STATE` | ตรวจสถานะสายเข้า/สายออก |
| `READ_CALL_LOG` | สแกนประวัติสาย |
| `READ_SMS` | เตรียมไว้สำหรับสแกนข้อความ SMS |
| `CALL_PHONE` / `ANSWER_PHONE_CALLS` | call-related actions และ call screening |
| `READ_CONTACTS` | แสดงชื่อผู้ติดต่อใน overlay |
| `PROCESS_OUTGOING_CALLS` | รับ event สายออกใน Android รุ่นเก่า |
| `RECEIVE_BOOT_COMPLETED` | restart service หลังเปิดเครื่อง |
| `CAMERA` | QR/barcode scanner |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ลดโอกาส service ถูกระบบปิด |
| Accessibility Service | อ่านข้อความในแอปที่อนุญาตเพื่อประเมิน scam risk |

---

## Build Configuration

โปรเจกต์อ่านค่า API key ผ่าน Gradle properties แล้ว inject เข้า `BuildConfig`

```properties
STT_API_KEY=your_google_cloud_stt_key
OPENAI_API_KEY=your_openai_key
WHISPER_CPP_API_KEY=your_whisper_cpp_key
IPINFO_TOKEN=your_ipinfo_token
PHONE_REP_API_KEY=your_phone_reputation_key
PHONE_REP_API_KEY_FALLBACK=your_fallback_phone_reputation_key
CAPSOLVER_API_KEY=your_capsolver_key
BLS_USER=your_blacklistseller_username
BLS_PASS=your_blacklistseller_password
BLACKLIST_API_KEY=your_blacklist_api_key
BLACKLIST_API_URL=https://api.thammasorn.dev/api/search
BLACKLIST_API_FULL_URL=https://api.thammasorn.dev/api/search/full
```

สามารถใส่ไว้ใน `local.properties`, `gradle.properties` หรือส่งผ่าน command line เช่น:

```bash
./gradlew assembleDebug -PSTT_API_KEY=... -PWHISPER_CPP_API_KEY=...
```

หมายเหตุด้านความปลอดภัย: ใน `app/build.gradle` ปัจจุบันมี default key/credential หลายตัวสำหรับ dev/testing ควร rotate key จริงก่อนเผยแพร่ และควรย้าย secret ทั้งหมดไปอยู่ใน local/private config หรือ CI secret แทนการ commit ลง repo

---

## วิธี Build และ Run

### Prerequisites

- Android Studio รุ่นใหม่ที่รองรับ compileSdk 34
- JDK 17
- Android device หรือ emulator API 29+
- Gradle wrapper ที่มากับโปรเจกต์
- ถ้าต้องใช้ call playback capture จริง ควรทดสอบบนเครื่องจริง เพราะ emulator/บางแอปอาจไม่ปล่อยเสียงให้ capture

### Build debug APK

```bash
./gradlew assembleDebug
```

บน Windows สามารถใช้:

```powershell
.\gradlew.bat assembleDebug
```

APK จะอยู่ที่:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### ติดตั้งลงเครื่อง

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## การใช้งานหลังติดตั้ง

1. เปิด Sentinel AI
2. ผ่านหน้า intro/privacy
3. เข้า setup แล้วให้ permission ที่จำเป็น
4. เปิด overlay permission
5. เปิด accessibility service หากต้องการตรวจข้อความในแอปแชต
6. ให้ MediaProjection permission หากต้องการตรวจเสียงปลายสายแบบ playback capture
7. เปิดหน้า Home เพื่อดูสถานะ guardian
8. ทดสอบตรวจลิงก์ เบอร์โทร QR หรือโทรเข้า/โทรออกเพื่อดู overlay

---

## Python Blacklistseller Helper

โฟลเดอร์ `blacklistseller_bypassapi/` มีสคริปต์ `api_blacklistseller.py` สำหรับค้นข้อมูลจาก Blacklistseller โดยใช้ `curl_cffi`, `requests`, `BeautifulSoup` และ CapSolver Turnstile token

หน้าที่โดยรวม:

- login เข้า Blacklistseller
- refresh CSRF token
- ขอ token จาก CapSolver
- submit เบอร์โทร
- parse ตารางผลลัพธ์เป็น index, seller_info และ amount

สคริปต์นี้เป็น helper/dev tool แยกจาก Android app และมี secret hardcoded อยู่ ควรใช้ด้วยความระมัดระวังและย้าย credential ไป environment variables หากจะนำไปใช้จริง

---

## Testing

โปรเจกต์มี instrumentation test เบื้องต้นที่:

```text
app/src/androidTest/java/com/sentinel/ai/security/NumberCheckerTest.kt
```

รัน test ได้ด้วย:

```bash
./gradlew test
./gradlew connectedAndroidTest
```

`connectedAndroidTest` ต้องมี device/emulator เชื่อมอยู่

---

## ข้อจำกัดและสิ่งที่ควรรู้

- Audio playback capture ขึ้นกับ policy ของ Android และแอปต้นทาง บางแอปอาจไม่อนุญาตให้ capture เสียง
- Android 14+ มีข้อจำกัดเรื่อง MediaProjection token จึงต้องพึ่งการเก็บ `MediaProjection` object ระหว่าง session
- Mic fallback อาจจับเสียงฝั่งผู้ใช้มากกว่าปลายสาย และความแม่นยำขึ้นกับ Android SpeechRecognizer/สภาพแวดล้อม
- Link/number checker ที่เรียก API ภายนอกต้องมี internet และ key ที่ valid
- Accessibility Service ควรจำกัดเฉพาะแอปที่ต้องการ monitor เพื่อลดผลกระทบด้าน privacy
- Event store ใน memory จะหายเมื่อ process ถูก kill หากยังไม่ได้ persist ผ่าน Room ในทุก flow

---

## Privacy Design

แนวคิดของโปรเจกต์คือ privacy-first:

- วิเคราะห์ keyword และ risk scoring หลายส่วนบนเครื่อง
- audio buffer ถูกใช้ชั่วคราวใน pipeline แล้วทิ้ง
- offline STT ผ่าน Vosk ใช้ได้โดยไม่ต้องส่งเสียงออกนอกเครื่อง
- cloud STT/API lookup จะเกิดเมื่อเปิดใช้หรือมีค่า config ที่เกี่ยวข้อง
- Accessibility Service อ่านเฉพาะ package ที่กำหนดในโค้ด
- Overlay และ activity log แสดงเพื่อให้ผู้ใช้เห็นเหตุผลของคำเตือน

---

## License

MIT License ดูรายละเอียดที่ [LICENSE](LICENSE)

---

<p align="center">
  Built to help people recognize scams before damage is done.
</p>
