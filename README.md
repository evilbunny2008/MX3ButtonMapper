# MX3 Button Mapper

An Android accessibility service for remapping hardware buttons on MX3-style
air-mouse remotes (and other remotes/keyboards) connected to Google TV devices —
 buttons that fire the wrong keycode or don't map to anything useful by default.

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
  `adb shell`.
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

| Scancode | Sends keycode                                     |
|----------|---------------------------------------------------|
| 108      | `KEYCODE_DPAD_DOWN`                               |
| 105      | `KEYCODE_DPAD_LEFT`                               |
| 106      | `KEYCODE_DPAD_RIGHT`                              |
| 103      | `KEYCODE_DPAD_UP`                                 |
| 28       | `KEYCODE_DPAD_CENTER`                             |
| 419      | 4001 (raw, TCL-specific -- see comment in source) |
| 171      | `KEYCODE_EISU`                                    |
| 127      | `KEYCODE_MENU`                                    |

**Channel Up/Down button, remapped differently depending on what's on screen:**

| Scancode | While the TV app is in the foreground | Everywhere else              |
|----------|---------------------------------------|------------------------------|
| 104      | `KEYCODE_CHANNEL_UP`                  | 5× `KEYCODE_DPAD_UP` burst   |
| 109      | `KEYCODE_CHANNEL_DOWN`                | 5× `KEYCODE_DPAD_DOWN` burst |

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

This project uses **[thedjchi's Shizuku fork](https://github.com/thedjchi/Shizuku)**
rather than the [original Shizuku](https://shizuku.rikka.app/) — it's
built specifically with Google TV in mind, including a proper "start on
boot" option that works without a computer, which the original doesn't
have. Grab the latest release from that fork's
[Releases page](https://github.com/thedjchi/Shizuku/releases) and install
it (you'll likely need to allow "Install unknown apps" for whatever
you're installing it with, or briefly disable Play Protect).

> If you already have the *original* Shizuku installed, uninstall it
> first — the two can't coexist, and if you'd previously paired the
> original over wireless debugging, un-pair that too.

### 2. Turn on Developer options and Wireless debugging

Most Google TV devices don't have a usable USB port for USB debugging, so
this fork is started over **wireless debugging** instead — no computer
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

1. Open Shizuku and tap **Pair** — a notification will appear that's used
   to complete pairing.
2. Go back into `Settings → Developer options → Wireless debugging`, and
   tap the **Wireless debugging** row itself (not just its toggle) to
   open its own sub-screen.
3. Tap **Pair device with pairing code**.
4. Enter the code shown in the pairing notification from step 1. You
   should see a success message once it's paired.
5. Back in the Shizuku app, tap **Start**.

Full walkthrough with more detail (and what to do if something doesn't
match what you're seeing) is in the fork's own
**[setup wiki](https://github.com/thedjchi/Shizuku/wiki/Setup)**.

### 4. Turn on "Start on boot" in Shizuku

Without this, you'd need to redo the Start step above every time the TV
restarts. In the Shizuku app's settings, turn on **Start on boot**. On
unrooted devices it needs a Wi-Fi connection to come back up after a
reboot before it can start itself again — this can take a couple of
minutes after the TV reconnects to Wi-Fi, so don't worry if button
remapping doesn't work instantly right after a restart.

### 5. Install and open MX3 Button Mapper

It'll prompt for Shizuku permission on first launch — accept it. Once
granted, the app enables its own accessibility service automatically, no
need to visit `Settings → Accessibility` yourself.

Press a mapped button to confirm it's working. If Shizuku isn't ready yet
when you press one, the button's original, unmapped behaviour happens
instead (nothing gets silently swallowed) and a notification prompts you
to finish setup.

### 6. (Optional) Set up the Shizuku auth token for more reliable starts

Shizuku's own "Start on boot" toggle can be unreliable on some devices.
As a more direct alternative, this app can send Shizuku's documented
start broadcast itself whenever the accessibility service starts:

1. In the Shizuku app, tap **View intents** (under "Control Shizuku
   with automation apps").
2. Copy the full `auth: XXXX` line shown there.
3. Open MX3 Button Mapper and paste that whole line into the "Shizuku
   start/stop intent auth token" field, then tap **Save**. The `auth:`
   prefix is stripped automatically — pasting the full line is fine.

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
