# Fluent — English Helper Keyboard (Gemini-powered)

An Android keyboard for a native Kannada speaker writing English. Two layers of help:

1. **Instant offline fixes** — as you type, a green suggestion fixes capitals, apostrophes
   (don't, I'm), and common Kannada-first grammar slips. No internet, no key, instant.
2. **AI rewrites (✨)** — tap the sparkle and Google's **Gemini** returns 2–3 corrected,
   natural rewrites of your whole message. Tap one to replace the text.

## Privacy note
Layer 1 runs on the phone. Layer 2 (✨) sends the text you're rewriting to Google's Gemini
API. The app requests `INTERNET` for that. Your Gemini API key is stored only on the phone.

---

## Get the APK — three ways

### Option A — GitHub Actions (no tools to install)
Push this project to a GitHub repo → open the **Actions** tab → the **Build APK** workflow
runs → download the **Fluent-debug-apk** artifact → install `app-debug.apk` on the phone.

```bash
git init && git add . && git commit -m "Fluent Gemini"
git branch -M main
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```

### Option B — Android Studio
**File → Open** this folder → let it sync → **Run** on a phone or emulator.

### Option C — Command line (Android SDK + JDK 17)
```bash
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

---

## One-time setup on the phone
1. Open **Fluent** → **Enable Fluent Keyboard** → turn it on → **Switch to Fluent**.
2. Get a free Gemini key: go to **aistudio.google.com**, sign in, **Get API key**, create one
   (starts with `AIza…`).
3. In Fluent, paste the key and tap **Test & save**. On success it's stored.
4. In any app, type a message and tap **✨** on the keyboard → pick one of the 3 rewrites.

The green instant fixes work immediately, with or without a key.

---

## What works
- System-wide keyboard, large keys, auto-capitalisation, haptics.
- Instant offline rule fixes (capital I / sentence caps, apostrophes, he/she/it agreement,
  missing "the", "discuss about" → "discuss", "did you went" → "did you go", and more).
- ✨ Gemini rewrites: type anything → 2–3 corrected, natural options → tap to fill the field.
- API key tested and stored locally; friendly errors for no-internet, bad key, and rate limits.

## Model / free tier
- Uses `gemini-2.5-flash` (stable, free-tier eligible). Free tier is ~10–15 requests/minute
  and ~500/day — ample for one person texting.
- To use a newer model, change one line: `MODEL` in `app/src/main/java/com/Fluent/keyboard/ai/Rewriter.kt`
  (e.g. `"gemini-3.5-flash"`), if it's free for your account.

## Honest limitations (next steps)
- **Letters only.** No number/symbol (`?123`) layer yet — numeric fields show letters. Easy next add.
- ✨ needs internet and takes ~1–3 seconds per rewrite (network + model).
- The API key is stored in plain SharedPreferences (fine for a personal app; could be encrypted later).
- Suggestions appear while typing at the **end** of the field (the normal texting case).

## Structure
```
app/src/main/java/com/Fluent/keyboard/
  MainActivity.kt              onboarding + Gemini API key setup
  ime/FluentImeService.kt       keyboard + green suggestions + ✨ rewrite panel
  ai/Rewriter.kt               Gemini REST client (HttpURLConnection + org.json)
  engine/                      offline rule engine (Tier 0 + Tier 1)
```

Toolchain: Gradle 8.7 · AGP 8.5.2 · Kotlin 1.9.24 · JDK 17 · minSdk 26 · targetSdk 34.
No third-party networking library — built-in HTTP + JSON only.
