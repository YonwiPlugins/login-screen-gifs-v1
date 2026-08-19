# Login Screen GIFs

A RuneLite plugin that plays your own animated GIFs behind the Old School RuneScape login
screen, with a side panel for managing the whole library without leaving the client.

## Highlights

- **Any filename works.** Nothing has to be renamed to `login.gif` — the library is whatever
  `.gif` files are in the folder.
- **Upload from the side panel.** Drag GIFs onto the panel, pick them with a file chooser, or
  open the folder and drop them in. No file-fiddling elsewhere.
- **Moving previews.** Every GIF in the library animates in its own row, so you can tell them
  apart at a glance.
- **Every control in the panel.** Cycling, order, timer and sizing sit next to the library, and
  stay in step with the settings screen.
- **As many GIFs as you like.** The library is unbounded; cycle through as many as you can be
  bothered to collect.
- **No fallback FPS setting.** Frame timing is worked out from the GIF itself.
- **Plays GIFs Java itself cannot read.** See below.

## Its own GIF reader

The GIF reader in the JRE throws `ArrayIndexOutOfBoundsException: Index 4096 out of bounds for
length 4096` on a good number of perfectly valid GIFs. 4096 is the ceiling of the LZW
dictionary, and its decoder walks off the end of its own string table when a stream fills the
dictionary without first sending a clear code — which is exactly what the encoders behind Giphy,
Tenor and ffmpeg tend to produce.

The symptom is a GIF that shows as a still image, or does not appear at all. Of eight ordinary
GIFs tested here, one yielded no frames and another stopped after three and looped those
forever.

So this plugin parses the format itself, holding the dictionary at the ceiling rather than
growing past it. It reads the file as a stream, so a large GIF never sits on the heap, and it
needs no third-party dependency.

## Using it

Enable **Login Screen GIFs** in RuneLite, then open the panel from the sidebar and add some
GIFs. They are stored in:

```text
%USERPROFILE%\.runelite\login-screen-gifs
```

on Windows, or `~/.runelite/login-screen-gifs` on Linux and macOS. The folder is created for
you, and the plugin only ever reads from inside it.

Click any row in the panel to play that GIF, use **< Prev** and **Next >** to move through the
library by hand, or the **x** on a row to delete it.

## Cycling

**Cycle when** decides what moves the plugin on to the next GIF:

| Setting | Behaviour |
| --- | --- |
| Never (manual only) | Stays on the chosen GIF until you change it |
| Once per client start | Picks a new one each time RuneLite launches |
| Every login screen | Picks a new one each time the login screen appears |
| Every full GIF loop | Changes as soon as the current GIF finishes a loop |
| On a timer | Changes every *Timer length* seconds |

**Pick order** decides which GIF comes next: in order, random, or shuffle. Shuffle plays every
GIF in the library once before any of them repeat. Random never picks the GIF already showing.

The panel buttons and the optional **Next GIF hotkey** cycle by hand whatever this is set to.

## Appearance

- **Scaling** — fill and crop the edges, stretch to fit, or fit inside with letterboxing.
- **Login screen flames** — keep or hide the braziers the stock login screen draws.
- **Pause while you interact** — see below.

## The world switcher and the authenticator

The login screen serves its own mouse clicks and key input on the client thread. Anything
expensive done on that thread while the login screen is up competes directly with the input
handling, which is what makes the world switcher and the 2FA PIN entry feel unresponsive.

This plugin keeps that thread nearly idle:

- Decoding, scaling and pixel packing all happen on a background thread. The client thread only
  wraps an already-prepared array in a sprite.
- Frames are rendered at the size the window actually is, capped at 1280x720, rather than a
  fixed 1536x864 regardless of window size.
- The animation holds outright on the authenticator form.
- With **Pause while you interact** on, the current frame is held for half a second after any
  click or keystroke, so the client thread is free to serve the input.

The background stays on screen the whole time — only the animation pauses.

## Limits

GIFs above 128 MB, and frames that would decode to more than 64 MB of pixels, are skipped with
a warning rather than being allowed to stall the decoder. Files are checked by their GIF header
rather than their extension, so a renamed video will not be loaded.

## Building

```bash
./gradlew build
```

`./gradlew run` starts RuneLite with the plugin loaded for testing.

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
