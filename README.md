# Chat Translator

An Android app that shows WhatsApp messages in **any language** translated into **your chosen language**, right inside the chat.

Two translation engines:

- **On the phone** (default): Google ML Kit, private and offline, basic quality.
- **Claude** (optional, your own Anthropic API key): much better translations of slang, nicknames and
  references, because Claude sees the surrounding conversation and translates directly between the two
  languages. Foreign-language messages (and the messages around them, for context) are sent to
  Anthropic. If Claude can't be reached or rejects the key, the app falls back to on-phone translation.
  Messages already in your language are never sent.

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
4. Open a WhatsApp chat. Foreign-language messages are shown translated inside their own bubble, lined up
   with the original text and marked by a thin green bar.

### Learn what they said

Tap the round green translate button at the side of the screen to open the **Learn** panel. It lists
every foreign-language message on screen as a learning card:

- the language (e.g. *Spanish*),
- the original message in large type,
- what it means in your language underneath,
- a 🔊 button that reads the original aloud with a native voice (uses the phone's text-to-speech;
  the voice for a language may download the first time).

### Reply in their language

At the bottom of the Learn panel, **Reply in** (e.g. *Spanish*, picked automatically from the language
used most in the chat; tap it to choose another) lets you type in your own language and see the
translation as you type. **Insert** puts the translation in WhatsApp's message box, after anything you
already typed there. Sending stays up to you.

The panel also has the **Show translations in the chat** switch: turn it off to read the chat in the
original languages and use the panel when you get stuck. The button turns white while it's off.
Drag the button up or down to move it.

While you scroll, translations disappear so nothing slides around; they fade back in once the chat
has been still for about half a second. From the moment a finger touches the screen the app checks
message positions 25 times a second, so they vanish as soon as the chat starts moving (taps and typing
don't move anything, so they leave translations alone). Translations also disappear the moment you
leave WhatsApp (Home, Back, switching apps): while WhatsApp is open the app is told when another app
comes to the front, without reading anything from that app.

Translations take the colour of the bubble they sit on, measured from the screen (Android 11+), so they
blend in with light mode, dark mode and WhatsApp's own theme setting. Text shrinks slightly when a
translation is longer than the original, so it stays inside the bubble.

### Claude translation

In the app, under **Translation quality**, choose **Claude**, paste an API key from
[console.anthropic.com](https://console.anthropic.com/settings/keys) and tap **Save & test**.

- Model: `claude-haiku-5-5` (the cheapest Claude model) at low effort with thinking off.
- Messages on screen are collected for 250 ms and translated in one request (up to 25 at a time), with up
  to 30 other messages from the chat as context; answers come back as structured JSON.
- Cost is billed to your Anthropic account: about $0.0001 per screen of new messages (a real two-message
  request used 470 input + 95 output tokens, so roughly 10,000 screens per dollar; each message is
  translated once and then cached). Replies typed in the Learn panel use Claude too.
- The key is stored in the app's private storage, excluded from backups.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

## Testing on an emulator

`testing/fake-whatsapp` is a stand-in app with WhatsApp's package name and message view ids, showing a
chat in Spanish, French, German and English in a RecyclerView (rows are recycled while scrolling, like
WhatsApp). Build the translator with x86_64 libraries and install both:

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

Verified this way on Android 14: translation into English and Dutch, scrolling, hiding when leaving the
chat, colours in light mode, dark mode and mixed themes, the Learn panel (cards, read-aloud, chat switch),
scrolling (slow drags, flings, back-to-back flings: translations only return after the list is still),
leaving with the Home button, and replying (typing, live translation, inserting into the message box). Not yet verified against the
real WhatsApp app.

## Compatibility with WhatsApp

Checked against the official WhatsApp APK, version 2.26.45.71 (November 2026): message text in chats is a
`com.whatsapp.ui.coreui.base.TextEmojiLabel` with view id `message_text`, used across 35 chat-row layouts,
and media captions use `caption`. Those are the ids `MessageScanner` looks for. A running WhatsApp could
not be tested because it needs a registered phone number.

## How it works

| File | Role |
| --- | --- |
| `TranslatorService.kt` | Accessibility service; places the translations, toggle button, bubble-colour sampling |
| `TranslationLabel.kt` | How a translation looks: bubble colour, accent bar, language code, text fitting |
| `LearnPanel.kt` | The Learn sheet: original/meaning cards, read-aloud, chat on/off switch |
| `Speaker.kt` | Reads originals aloud with the phone's text-to-speech |
| `MessageScanner.kt` | Finds message bubbles (`message_text` / `caption` view ids, with a fallback heuristic) |
| `TranslationEngine.kt` | Language identification, Claude batching with on-device fallback, cache |
| `ClaudeTranslator.kt` | Claude requests (Anthropic Java SDK), prompts, JSON schema and parsing |
| `MessageFilter.kt` | Skips timestamps, emoji-only messages, bare links |
| `MainActivity.kt` | Setup screen: target language, permissions, on/off |

## Known limitations

- It relies on WhatsApp's screen layout. A WhatsApp update can break message detection; the fallback
  heuristic usually keeps it working, but may also translate things like contact names.
- A translation much longer than the original is shown in slightly smaller text (down to 12sp).
- On Android 8–10 bubble colours can't be measured; WhatsApp's default colours for the phone's theme are used.
- Very short messages ("ok", "jaja") are often not recognised as a language and stay untranslated.
