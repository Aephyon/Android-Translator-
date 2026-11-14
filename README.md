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
4. Open a WhatsApp chat. Foreign-language messages get a pale box with the translation, labelled with the
   original language (e.g. `ES`).

The round 🌐 button at the side of the screen switches between translation and original. Drag it to move it.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

## How it works

| File | Role |
| --- | --- |
| `TranslatorService.kt` | Accessibility service; draws the overlay and the 🌐 toggle |
| `MessageScanner.kt` | Finds message bubbles (`message_text` / `caption` view ids, with a fallback heuristic) |
| `TranslationEngine.kt` | ML Kit language identification + on-device translation, with a cache |
| `MessageFilter.kt` | Skips timestamps, emoji-only messages, bare links |
| `MainActivity.kt` | Setup screen: target language, permissions, on/off |

## Known limitations

- It relies on WhatsApp's screen layout. A WhatsApp update can break message detection; the fallback
  heuristic usually keeps it working, but may also translate things like contact names.
- A translation longer than the original can overlap the next message slightly.
- Very short messages ("ok", "jaja") are often not recognised as a language and stay untranslated.
