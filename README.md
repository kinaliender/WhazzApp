# WhazzApp

A small Android app that opens WhatsApp Web (web.whatsapp.com) in a WebView and turns its desktop two-column layout into a phone layout.

- **Chat list** fills the screen on launch; tapping a chat shows its **messages full screen**.
- **Calls, Status, Channels, Communities, Settings and Profile** also open full screen. Their lists keep the narrow left rail so you can switch tabs; detail pages (contact info, call details, etc.) take the whole screen.
- **Keyboard-friendly chat screen**: the contact header stays at the top and the message box stays pinned just above the keyboard. The keyboard only opens when you tap a text field, not when WhatsApp focuses one by itself.
- **Back button** closes the open panel or menu, then returns from a chat or a detail page to its list, then to the Chats tab, and finally moves the app to the background (the session stays connected).
- **Runs in the background**: a foreground service keeps the sessions connected while the app is closed, so message notifications keep arriving. It starts automatically when the phone boots. Turn it off with **Stop** in the persistent notification or from the ⋮ menu.
- **Multiple accounts**: each account is a tab in the strip at the top. **+** adds an account; long-press a tab to rename or remove it. Every account runs in its own WebView profile (separate cookies and storage). The strip hides while a chat is open.
- **Language**: the app follows the system language: Turkish when the phone is set to Turkish, English otherwise. WhatsApp Web itself uses the phone's language.

## Install

Download `WhazzApp-<version>.apk` from the [Releases](https://github.com/kinaliender/WhazzApp/releases) page and open it on the phone (allow installing from this source when Android asks). Requires Android 10 or newer. A newer release installs over the old one and keeps your sessions.

## How it works

| Part | File | What it does |
|---|---|---|
| Screen | `app/src/main/java/com/ender/whazzapp/MainActivity.kt` | Account strip, showing the selected account's WebView, file picker, microphone/camera permission, back button, opening the right chat from a notification |
| Sessions | `.../SessionManager.kt` | One WebView per account (profile, desktop user agent, script injection). WebViews live independently of the Activity and are rebuilt if the renderer crashes |
| Background | `.../KeepAliveService.kt`, `.../BootReceiver.kt` | Foreground service (`specialUse`) and start on boot |
| Accounts | `.../AccountStore.kt` | Account list (SharedPreferences). The first account uses the WebView's default profile |
| Bridge / notifications | `.../JsBridge.kt`, `.../Notifications.kt` | `window.WhazzAndroid`: notifications (one channel per account), downloads, chat open/closed state |
| Layout script | `app/src/main/assets/mobile.js` | Starting from the `#side` and `#main` ids, finds the columns and side panels, tags them with `data-whazz`, and sets `<html class="whazz-chat">` while a chat is open |
| Layout style | `app/src/main/assets/mobile.css` | Based on those tags, shows one column full screen and hides the others |
| Texts | `app/src/main/res/values/strings.xml`, `values-tr/strings.xml` | English (default) and Turkish |

WhatsApp's class names change with every release, so the script does not rely on them; it only uses `#side`, `#main` and the DOM structure. If the layout breaks after a WhatsApp update, start with `findRow` / `tag` in `mobile.js`.

## Building

**Android Studio:** open the folder, let Gradle sync, connect the phone and press ▶ Run.

**Command line:** use JDK 17 (Gradle 8.10.2 does not run on newer JDKs):
- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (WebView remote debugging on)
- `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (the build attached to Releases)

(If your default Java is newer, point `JAVA_HOME` at a JDK 17 for this command.)

**Icons:** the logo is `icon/WhazzApp.png`. The launcher icon (`mipmap-*dpi/ic_launcher_foreground.png` on the `ic_launcher_background` color in `colors.xml`) and the status bar icon (`drawable-*dpi/ic_stat_whazzapp.png`, a white silhouette of the logo's speech bubble) are PNGs made from it.

## Logging in

If the app runs on the same phone as WhatsApp, you cannot scan the QR code. On the QR screen choose **"Log in with phone number"**, then in the WhatsApp app go to *Linked devices → Link a device → Link with phone number instead* and enter the 8-character code.

## Debugging

In debug builds the WebView can be inspected remotely: on your computer open `chrome://inspect` in Chrome → the page on the device → **inspect**. From there you can look at the DOM and try `mobile.css` changes live.

## Known limitations

- **Notifications** arrive as long as WhatsApp Web stays connected (the WebView has no push support; the foreground service keeps the connection alive). On first launch the app asks to be exempt from battery optimization. On Samsung phones, also make sure WhazzApp is not in any "sleeping apps" list under *Settings → Battery → Background usage limits*, and add it to "Never sleeping apps".
- **Memory**: each account is a separate WhatsApp Web page (a few hundred MB per account). Two or three accounts are fine.
- **Removing an account** only deletes its data on this device. To also remove it from the "Linked devices" list on your phone, choose *Log out* from the WhatsApp menu first. The first account (default profile) cannot be removed, only renamed.
- **Multiple accounts** require profile support in Android System WebView (available in current versions); without it the **+** button is hidden.
- **Voice and video calls** are not supported by WhatsApp Web in the browser.
- **File downloads** are captured from blob links and saved to the `Downloads` folder. If a media type does not download, look at the download section in `mobile.js`.

## Disclaimer

WhazzApp is an unofficial client and is not affiliated with, endorsed by, or connected to WhatsApp or Meta. WhatsApp's Terms of Service do not allow unofficial clients; the risk for personal use is low, but Meta could in theory restrict an account. Do not publish this app on the Play Store.
