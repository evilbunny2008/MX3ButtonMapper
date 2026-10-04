# MX3 Button Mapper

An Android accessibility service for remapping hardware buttons on MX3
Air Mouse remote controls connected to Google TV devices — buttons that
fire the wrong keycode or don't map to anything useful by default.

## Features

- **Remap a button to a different key.** Useful when a remote's hardware
  sends the wrong keycode for what it's labelled — e.g. a "Channel Up"
  button that actually sends `PAGE_UP` instead of `CHANNEL_UP`, so nothing
  listens for it correctly.
- **Fire a key several times per press.** One button press can inject a
  short burst of the same keycode (e.g. three Volume Down presses at
  once), with a configurable delay between each so it reads as distinct
  steps rather than a single blur.
- **Launch an app directly from a button.** No remapping involved — just
  starts a chosen app straight from the button press.
- **No root required.** Uses Shizuku for the parts that need elevated
  privileges (synthetic key injection, and self-enabling the
  accessibility service), running at the same privilege level as
  `adb shell`. On rooted devices, if Shizuku isn't connected, key
  injection falls back to `su` (`input keyevent`) instead, which will
  prompt your root manager for superuser access.
- **Self-configuring.** On first launch, once Shizuku permission is
  granted, the app enables its own accessibility service automatically —
  no need to dig through `Settings → Accessibility` by hand.

## Current button mappings

Configured in `ButtonMapperService.kt`. Scancodes are hardware/driver
specific — the values below were captured from a specific MX3 remote and
Google TV remote, so confirm your own remote actually sends the same ones
before assuming they match (a button that looks identical on a different
remote can send a different scancode).

**Remapped to a different key:**

| Scancode | Sends keycode                                          |
|----------|--------------------------------------------------------|
| 108      | `KEYCODE_DPAD_DOWN`                                    |
| 105      | `KEYCODE_DPAD_LEFT`                                    |
| 106      | `KEYCODE_DPAD_RIGHT`                                   |
| 103      | `KEYCODE_DPAD_UP`                                      |
| 28       | `KEYCODE_DPAD_CENTER`                                  |
| 419      | 4001 (TCL) or `KEYCODE_GUIDE` (Blaupunkt) -- see below |
| 171      | `KEYCODE_EISU`                                         |
| 127      | `KEYCODE_MENU`                                         |

Scancode 419's target keycode depends on the "TV brand" setting in the
app itself (TCL or Blaupunkt) — different TVs have turned out to expect
different keycodes for this specific button. Everything else in this
table is unaffected by that setting.

**Channel Up/Down button, remapped differently depending on what's on screen:**

| Scancode | While the TV app is in the foreground | Everywhere else              |
|----------|---------------------------------------|------------------------------|
| 104      | `KEYCODE_CHANNEL_UP`                  | 5× `KEYCODE_DPAD_UP` burst   |
| 109      | `KEYCODE_CHANNEL_DOWN`                | 5× `KEYCODE_DPAD_DOWN` burst |

"The TV app" is also determined by the TV brand setting above (TCL:
`com.tcl.tv`; Blaupunkt: `com.mediatek.wwtv.tvcenter`) -- both values
confirmed via `adb shell dumpsys window | grep mCurrentFocus` while
live TV was actually on screen.

**Fires a key multiple times per press:**

| Scancode | Sends keycode         | Times |
|----------|-----------------------|-------|
| 60       | `KEYCODE_VOLUME_DOWN` | 3     |
| 155      | `KEYCODE_VOLUME_UP`   | 3     |

**Launches an app:**

| Scancode | Launches                           |
|----------|------------------------------------|
| 172      | MX3 Launcher                       |
| 418      | SmartTube (`app.smarttube.fdroid`) |
| 150      | TV Bro browser                     |

All three are FOSS — MX3 Launcher is this project's own sibling app,
`app.smarttube.fdroid` is specifically SmartTube's F-Droid build (a
separate, distinct package from its non-free Play Store build), and TV
Bro is open source. None of them are bundled with this app or required
as a build dependency either way — they're just package names this app
knows how to launch if present. If a mapped target isn't installed, the
mapping is simply a no-op (see the "if something isn't working"
troubleshooting note below).

A button not listed anywhere above passes through completely untouched —
its original, default behaviour still happens.

## Setup

### 1. Install Shizuku

This project uses **[my Shizuku fork]([https://github.com/thedjchi/Shizuku](https://github.com/evilbunny2008/Shizuku))**
rather than the [original Shizuku](https://shizuku.rikka.app/) — it's
built specifically with Google TV in mind, and includes a method to ensure
it starts on TCL TVs that automatically disable starting on boot, which
the original doesn't have. Grab the latest release from 
[the latest releases page](https://github.com/evilbunny2008/Shizuku/releases/latest) and install
it (you'll likely need to allow "Install unknown apps" for whatever
you're installing it with, or briefly disable Play Protect).

If you already have another Shizuku installed and if you'd previously
paired the over wireless debugging, un-pair that then uninstall it
first — as the two can't coexist.

### 2. Turn on Developer options and Wireless debugging

This fork is started over **wireless debugging** — no computer
required after the one-time setup below.

1. On the TV: `Settings → About` (sometimes under `System`), then click
   **Build number** about 7 times until it says Developer options are
   enabled.
2. Go to `Settings → Developer options` (now visible, usually on the main
   Settings page or under `System`).
3. Turn on **USB debugging** and **Wireless debugging**. If prompted to
   allow wireless debugging on the current network, allow it.

(Exact menu names/locations can vary a little by device — Google's own
[developer options guide](https://developer.android.com/studio/debug/dev-options#enable)
has more detail and screenshots if your TV's menus look different.)

### 3. Pair Shizuku over wireless debugging

1. You need to allow Shizuku to be an accessibility service, so it can
   automatically grab the PIN to make setting it up easier. Open the
   **Accessibility** screen if showing in the quick menu, or open the
   settings app and scroll down to the **Accessibility** menu item.
2. Scroll to the bottom of the **Accessibility** screen where you will
   find Shizuku listed and enable it.
4. Open Shizuku and tap **Pairing** — a dialog will appear asking if
   you want to open **Developer options**, click on that button.
6. The enable both **USB debugging** and **Wireless debugging**
7. Then tap **Pair device with pairing code**.
8. If the **Allow wireless debugging on this network?** click **Allow**,
   then click **Pair device with pairing code** again.

All going well Shizuku will now be paired, otherwise try repeating the
above steps again.

### 4. Install and open MX3 Button Mapper

It'll prompt for Shizuku permission on first launch — accept it. Once
granted, the app enables its own accessibility service automatically, no
need to visit `Settings → Accessibility` yourself.

Press a mapped button to confirm it's working. If Shizuku isn't ready yet
when you press one, the button's original, unmapped behaviour happens
instead (nothing gets silently swallowed) and a notification prompts you
to finish setup.

### 5. (Optional) Set up the Shizuku auth token for more reliable starts

Shizuku's own "Start on boot" toggle can be unreliable on some devices.
As a more direct alternative, this app can send Shizuku's documented
start broadcast itself whenever the accessibility service starts:

1. In the Shizuku app, tap **View intents** (under "Control Shizuku
   with automation apps"), then tap its **Copy** button to copy the
   `auth: XXXX` line to the clipboard.
2. Open MX3 Button Mapper and tap **Paste** next to the "Shizuku
   start/stop intent auth token" field, then tap **Save**. The `auth:`
   prefix is stripped automatically — pasting the full copied line is
   fine.

This is stored in this app's own local settings, not committed
anywhere — it's a real per-install credential.

## If something isn't working

- **A button does nothing / does its old behaviour instead of the new
  one** — Shizuku probably isn't running. Open Shizuku and check its
  status says "Running." If it doesn't, see the fork's
  [Troubleshooting page](https://github.com/thedjchi/Shizuku/wiki/Troubleshooting).
- **Worked before a TV restart, not after** — check "Start on boot" is
  actually enabled in Shizuku (step 4 above), and give it a couple of
  minutes after the TV reconnects to Wi-Fi.
- **A button you want to remap isn't in the tables above, or does the
  wrong thing** — this app's mappings are configured directly in its
  source code (`ButtonMapperService.kt`), not through an in-app settings
  screen. Different physical remotes can send different scancodes even
  for buttons that look identical, so the exact mapping table above may
  not match your specific remote.

## License

Unlicense (public domain). See `LICENSE`.
