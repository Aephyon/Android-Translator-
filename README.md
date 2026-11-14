# Chat Translator

An Android app that shows WhatsApp messages in **any language** translated into **your chosen language**, right inside the chat.

It does not modify WhatsApp (so no risk of a ban). It runs next to it as an accessibility service: it reads the
messages on screen, detects their language, and lays a translated copy over each foreign-language bubble.
Translation runs entirely on the phone with Google ML Kit (~59 languages). Messages are never sent anywhere; the
internet is only used to download language packs (~30 MB each, once).

Works with WhatsApp and WhatsApp Business. The service only receives events from those two apps.

## Install

1. Copy `app-release.apk` to your phone and open it. Allow "Install unknown apps" for your browser/file manager if asked.
2. Open **Chat Translator** and pick the language to translate into. Its language pack downloads.
3. Tap **Open Accessibility settings** → **Chat Translator** → turn it on.
   - If it is greyed out or says *Restricted setting*: open App info for Chat Translator → ⋮ (top right) →
     **Allow restricted settings**, then try again. Android does this for every app installed from an APK.
4. Open a WhatsApp chat. Foreign-language messages are shown translated inside their own bubble, marked by
   a thin green bar and a small code for the original language (e.g. `ES`).

The round green translate button at the side of the screen switches between translation and original
(it turns white when showing originals). Drag it up or down to move it.

Translations take the colour of the bubble they sit on, measured from the screen (Android 11+), so they
blend in with light mode, dark mode and WhatsApp's own theme setting. Text shrinks slightly when a
translation is longer than the original, so it stays inside the bubble.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

## Testing on an emulator

`testing/fake-whatsapp` is a stand-in app with WhatsApp's package name and message view ids, showing a
chat in Spanish, French, German and English. Build the translator with x86_64 libraries and install both:

```sh
./gradlew assembleRelease -PwithX86
(cd testing && ./gradlew :fake-whatsapp:assembleDebug)
adb install app/build/outputs/apk/release/app-release.apk
adb install testing/fake-whatsapp/build/outputs/apk/debug/fake-whatsapp-debug.apk
adb shell settings put secure enabled_accessibility_services app.chattranslator/app.chattranslator.TranslatorService
adb shell am start -n com.whatsapp/.ChatActivity
```

Add `--ez light true` to the last command to keep the chat light while the phone is in dark mode
(simulates WhatsApp's own theme setting).

Verified this way on Android 14: translation into English and Dutch, scrolling, the toggle button, hiding
when leaving the chat, and colours in light mode, dark mode and mixed themes. Not yet verified against the
real WhatsApp app.

## How it works

| File | Role |
| --- | --- |
| `TranslatorService.kt` | Accessibility service; places the translations, toggle button, bubble-colour sampling |
| `TranslationLabel.kt` | How a translation looks: bubble colour, accent bar, language code, text fitting |
| `MessageScanner.kt` | Finds message bubbles (`message_text` / `caption` view ids, with a fallback heuristic) |
| `TranslationEngine.kt` | ML Kit language identification + on-device translation, with a cache |
| `MessageFilter.kt` | Skips timestamps, emoji-only messages, bare links |
| `MainActivity.kt` | Setup screen: target language, permissions, on/off |

## Known limitations

- It relies on WhatsApp's screen layout. A WhatsApp update can break message detection; the fallback
  heuristic usually keeps it working, but may also translate things like contact names.
- A translation much longer than the original is shown in slightly smaller text (down to 12sp).
- On Android 8–10 bubble colours can't be measured; WhatsApp's default colours for the phone's theme are used.
- Very short messages ("ok", "jaja") are often not recognised as a language and stay untranslated.
