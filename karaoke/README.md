# SmartTube Karaoke

Sing along to any YouTube video on the TV. Phones become wireless microphones over Wi-Fi, the TV mixes
the voices on top of the video sound, adds echo/reverb, can lower the original singer and change the key.

```
 Phone (SmartTube Mic app)                         TV (SmartTube)
 ┌──────────────────────────┐   UDP, 5 ms frames   ┌──────────────────────────────────────────────┐
 │ AudioRecord 48 kHz mono  │ ───────────────────▶ │ KaraokeServer ─▶ jitter buffer per mic        │
 │ (VOICE_PERFORMANCE)      │ ◀─────────────────── │   (NSD advertised)       │                    │
 │ gain / mute              │   WELCOME, RTT       │ TV mic (USB / remote) ───┤                    │
 └──────────────────────────┘                      │                     KaraokeMixer              │
                                                   │   noise gate ─▶ sum ─▶ volume ─▶ reverb/echo  │
                                                   │   ─▶ limiter ─▶ low latency AudioTrack ──┐    │
                                                   │                                         ▼    │
                                                   │ ExoPlayer: video audio ─▶ vocal remover ─▶   │
                                                   │            key shift (Sonic) ─▶ AudioTrack ─▶ system mixer ─▶ speakers
                                                   └──────────────────────────────────────────────┘
```

## Using it

1. On the TV, play a song in SmartTube and press the new **mic button** in the player controls.
   Karaoke mode turns on and the Karaoke menu opens. A long press toggles karaoke on/off without the menu.
2. On an Android phone on the same Wi-Fi, open **SmartTube Mic**. The TV shows up automatically
   (or type the address shown in the TV's Karaoke menu, e.g. `192.168.1.20:47900`).
3. Tap the big mic button. The first time, the phone asks for the 6-digit **PIN** shown on the TV
   (in the "Karaoke on" message and in Karaoke menu → Connect a microphone). The phone remembers it.
4. Sing. Up to 4 phones can sing at once (duets, groups).

Karaoke mode stays on between videos, so phones don't reconnect for every song. It turns off from the
menu, with a long press on the mic button, or when SmartTube exits.

### Karaoke menu (TV)

| Option | What it does |
|---|---|
| Karaoke mode | Opens the mic port and advertises the TV on the LAN |
| Connect a microphone | Instructions and the TV address |
| Connected mics | Each mic with its buffer delay and packet loss |
| Mic volume | 0–300% for all mics |
| Voice effect | Off, Room reverb, Hall reverb, Karaoke echo |
| Vocal remover | Off / 50 / 75 / 100%. Removes centre-panned vocals between ~150 Hz and 7 kHz and keeps bass, drums and stereo instruments. Works on most studio mixes, less well on live or mono recordings |
| Key (semitones) | −6…+6, pitch shift without changing tempo |
| Music volume | The video's volume, to balance it against the voices |
| Mic latency | Low 25 ms / Normal 50 ms / Stable 100 ms of jitter buffering per mic |
| Mic options | Noise gate (mutes mics between phrases); use the TV's own mic; require a PIN for phone mics (on by default) |
| New PIN | Makes a new PIN and disconnects the phones paired with the old one |
| Find a karaoke version | Searches YouTube for "<song title> karaoke" |

Vocal remover and key change apply only while karaoke mode is on, so normal videos never lose their vocals.
Keep SmartTube open on the TV while singing: Android may pause apps in the background, and mics then stop
until SmartTube is back on screen.

"Use the TV's own mic" is remembered and starts with karaoke mode. Android mutes it while SmartTube is in the
background, so it works while the player is on screen; phone mics keep working either way.
The player button can be hidden in Settings → Player → Player buttons → Karaoke.

## Microphone options

| Mic | Quality | Delay | Notes |
|---|---|---|---|
| **SmartTube Mic app** (Android phone) | 48 kHz, no voice-call processing | ~40–100 ms total | Recommended. Continuous, any number of songs, up to 4 phones |
| **USB mic or USB wireless (UHF) mic receiver** plugged into the TV box | Best | Lowest | Turn on Mic options → "Use the TV's own mic". Works on boxes with USB audio input (e.g. Nvidia Shield, most Android TV boxes) |
| **Google TV app / remote mic** | Speech quality | High | Also via "Use the TV's own mic". Most TVs only stream it while the mic button is held and send it to Google Assistant first, so it suits short tests rather than whole songs. Behaviour depends on the TV |
| **Bluetooth karaoke mic with built-in speaker** | Good | n/a | Needs no app support: the mic mixes your voice into its own speaker. Pair it as the TV's audio output (video sound may lag slightly) |
| iPhone | — | — | Not supported yet. Browsers only allow the mic on HTTPS pages, so a web mic needs the TV to serve HTTPS with a self-signed certificate. Possible future addition |

### Reducing delay

The voice you hear is delayed by phone capture + Wi-Fi + jitter buffer + TV audio output.

- Put the phone on **5 GHz Wi-Fi**; connect the TV by Ethernet if you can.
- Use **Mic latency → Low** when the Wi-Fi is good. Watch "loss" in Connected mics; switch back to Normal if it climbs.
- Turn on the TV's **Game mode** / low latency audio and avoid Bluetooth speakers or soundbars that add delay.
- Hold the phone close to your mouth and away from the TV speakers to avoid feedback (howling). Keep the noise gate on.

## Building

```bash
./gradlew :smarttubetv:assembleStfdroidDebug   # TV app with karaoke
./gradlew :karaokemic:assembleDebug            # phone app -> karaoke/micapp/build/outputs/apk/debug/SmartTubeMic_1.0_debug.apk
./gradlew :karaokecore:test                    # protocol, jitter buffer and DSP unit tests
```

Use JDK 17. On an Apple Silicon Mac without Rosetta, the existing `exoplayer-library-sabr` module fails with
`protoc-3.17.3-osx-aarch_64.exe: Bad CPU type in executable`. Either install Rosetta
(`softwareupdate --install-rosetta`) or add `-I karaoke/apple-silicon-protoc.init.gradle` to the commands above.

## Code map

| Path | What |
|---|---|
| `karaoke/core` | Pure Java, shared by both apps, JVM unit tested: `KaraokeProtocol`, `KaraokePairing`, `PairingGuard`, `JitterBuffer`, `VocalRemover`, `VoiceReverb`, `NoiseGate`, `Limiter`, `Biquad`, `KaraokeSearch` |
| `common/.../common/karaoke` | TV side: `KaraokeManager` (public API), `KaraokeServer` (UDP + NSD), `KaraokeMixer` (AudioTrack), `LocalMicCapture`, `MicSource`, `VocalRemoverAudioProcessor` (ExoPlayer) |
| `common/.../controllers/KaraokeController.java` | Player button and Karaoke menu |
| `common/.../prefs/KaraokeData.java` | Saved karaoke settings |
| `smarttubetv/.../actions/KaraokeAction.java` | Player button |
| `karaoke/micapp` | SmartTube Mic phone app: `MainActivity`, `MicStreamService` (foreground, works with the screen off), `MicStreamer`, `TvFinder` (NSD) |

Small hooks into existing code: `PlaybackPresenter` registers the controller, `CustomOverridesRenderersFactory`
adds the vocal remover to ExoPlayer's audio processors, `PlayerTweaksData`/`PlayerSettingsPresenter`/`VideoPlayerGlue`
add the button, `ViewManager.properlyFinishTheApp` stops karaoke on exit.

## Protocol

UDP port 47900 (falls back to a random port if busy; NSD advertises the real one). NSD service type `_stkaraoke._udp`.
Header is 20 bytes, big-endian: magic `STKM`, version, type, flags, reserved, session id, sequence, sender clock (ms).

| Type | Direction | Payload |
|---|---|---|
| HELLO (1) | mic → TV, every second | sample rate (int), frame size (short), channels (byte), name, PIN proof (0 or 32 bytes) |
| WELCOME (2) | TV → mic, header echoes the mic's session id | echoed sender clock (for round trip time), status, mic slot, TV name, pairing challenge (long) |
| AUDIO (3) | mic → TV | mono 16 bit little-endian PCM, 48 kHz, 240 samples (5 ms) |
| BYE (4) | mic → TV | none |

Statuses: OK, FULL, UNSUPPORTED, PIN_REQUIRED, PIN_WRONG, PIN_LOCKED. Protocol version 2.

Pairing: a new mic's first HELLO gets PIN_REQUIRED and a challenge derived from a random per-run TV secret,
the mic's session id and its IP address. The next HELLO carries HMAC-SHA256(PIN, challenge + session id);
the PIN itself never goes over the network. 5 wrong PINs from one address lock it out for a minute, and
20 wrong PINs within a minute pause new pairings for everyone for a minute (connected mics keep working).

A mic that sends nothing for 4 s is dropped. Lost packets become silence, late ones are discarded, and the jitter
buffer corrects clock drift between phone and TV by ±1 sample per frame.

## Privacy and security

- The TV listens for mics only while karaoke mode is on, and only devices on the same local network can reach it.
- Phones need the TV's 6-digit PIN (on by default, can be turned off in Mic options). Wrong guesses are rate
  limited (see Protocol), the TV shows a message for every mic that connects or enters a wrong PIN, and at most 4
  can join. A mic's audio/disconnect packets are accepted only from the device that paired, and the TV echoes the
  mic's random session id so the phone ignores stray replies.
- Limit: someone who can capture the Wi-Fi traffic of a pairing could brute force the PIN offline. If that's a
  concern, use New PIN after guests leave.
- Audio is sent unencrypted over the LAN and is never recorded or stored.
- The phone shows a "Mic is live" notification while streaming.

## Testing done

- `:karaokecore:test`: 33 unit tests (protocol round trips, jitter buffer loss/late/restart/drift/burst, vocal
  remover removes centred 1 kHz and keeps bass and stereo content, reverb tails decay without NaN, limiter ceiling,
  noise gate, karaoke search queries, PIN proof/challenge, wrong-PIN rate limiting).
- On an Android 16 emulator (phone image) with both apps installed: player button and Karaoke menu, NSD discovery,
  phone → TV streaming (Connected mics: buffer 40 ms, loss 0.0%), mixer AudioTrack playing alongside the video,
  vocal remover 100% + key +2 with playback continuing, karaoke off/on with automatic reconnect, phone Stop
  (BYE → "Mic disconnected"), TV mic capture via `AudioRecord`.
- Re-tested after review fixes (session id echo, address checks, failure handling): connect, stats, phone Stop.
- PIN pairing on the emulator: PIN in the "Karaoke on" message, phone prompt, wrong PIN rejected (phone message +
  TV message), right PIN connects, phone reconnects without asking (saved PIN), New PIN on the TV drops the phone
  and it asks again.
- Not tested: real TV hardware, real singing (the emulator mic is silent), audio quality and delay on real Wi-Fi,
  and whether a given TV routes the Google TV app mic to other apps.
