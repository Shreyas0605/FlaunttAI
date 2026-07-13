# Fluent AI — English Helper Keyboard

An Android keyboard built for native Kannada speakers writing English. Two layers of assistance, designed to stay out of the way.

**Layer 1 — Instant offline fixes**
As you type, a suggestion strip automatically corrects capitals, apostrophes (don't, I'm), and common Kannada-first grammar patterns. No internet, no API key, no delay.

**Layer 2 — AI rewrites**
Tap the AI button on the keyboard and Gemini returns 2–3 corrected, natural rewrites of your full message. Tap one to replace the text in the field.

---

## Privacy

Layer 1 runs entirely on the device. Layer 2 sends the text being rewritten to Google's Gemini API — the app declares `INTERNET` permission for this purpose only. Your Gemini API key is stored locally on the device and never transmitted elsewhere.

---

## Getting the APK

### Option A — GitHub Actions (recommended, no local tools needed)

Push this project to a GitHub repository. Open the **Actions** tab, let the **Build APK** workflow run, then download the **Fluent-debug-apk** artifact and install `app-debug.apk` on your phone.

```bash
git init && git add . && git commit -m "initial commit"
git branch -M main
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```

### Option B — Android Studio

Open this folder via **File > Open**, allow Gradle to sync, then run the project on a connected device or emulator.

### Option C — Command line

Requires Android SDK and JDK 17.

```bash
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

---

## First-time setup on device

1. Open **Fluent AI**, tap **Enable Fluent AI Keyboard**, enable it in system settings, then tap **Switch to Fluent AI**.
2. Get a free Gemini key at [aistudio.google.com](https://aistudio.google.com) — sign in, choose **Get API key**, and create one (begins with `AIza…`).
3. Paste the key into the Fluent AI home screen and tap **Test key**. On success the key is saved locally.
4. Open any app, type a message, and tap the **AI** button on the keyboard to get rewrite options.

Layer 1 offline fixes work immediately, with or without a key.

---

## Features

- System-wide keyboard with large keys, auto-capitalisation, and haptic feedback
- Instant offline rule fixes — capital I, sentence capitalisation, apostrophes, subject-verb agreement, missing articles, "discuss about" to "discuss", "did you went" to "did you go", and more
- AI rewrites via Gemini: 2–3 corrected, natural options generated from your full message; tap one to fill the field
- API key tested and stored locally; clear error messages for no internet, invalid key, and rate limit conditions

---

## Model and free-tier limits

Uses `gemini-2.5-flash`. The free tier allows approximately 10–15 requests per minute and 500 per day, which is sufficient for personal use.

To switch models, change the `MODEL` constant in `app/src/main/java/com/fluent/keyboard/ai/Rewriter.kt`:

```kotlin
private const val MODEL = "gemini-2.5-flash"
```

---

## Known limitations

| Area | Current state | Planned |
|---|---|---|
| Number/symbol layer | Not implemented — numeric fields show the letter layout | v1.1 |
| AI response time | 1–3 seconds depending on network and model load | Streaming in v1.2 |
| API key storage | Plain SharedPreferences (acceptable for personal use) | Android Keystore in v1.2 |
| Suggestion placement | Appears at end of field only (standard texting use case) | Cursor-aware in v1.1 |

---

## Project structure

```
app/src/main/java/com/fluent/keyboard/
  MainActivity.kt              Onboarding and Gemini API key setup
  ime/FluentImeService.kt      Keyboard service, suggestion strip, AI panel
  ai/Rewriter.kt               Gemini REST client (HttpURLConnection, org.json)
  engine/                      Offline rule engine (T0 character rules, T1 token rules)
```

---

## Toolchain

| Component | Version |
|---|---|
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| JDK | 17 |
| minSdk | 26 (Android 8.0) |
| targetSdk | 34 |

No third-party networking libraries — built on Android's HttpURLConnection and org.json only.
