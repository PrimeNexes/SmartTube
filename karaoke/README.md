# SmartTube Karaoke

Sing along to any YouTube video on the TV. Phones become wireless microphones over Wi-Fi, the TV mixes
the voices on top of the video sound, adds echo/reverb, can lower the original singer and change the key.
Each phone can auto-tune its singer.

```
 Phone (SmartTube Mic app)                         TV (SmartTube)
 ┌──────────────────────────┐   UDP, 5 ms frames   ┌──────────────────────────────────────────────┐
 │ AudioRecord 48 kHz mono  │ ───────────────────▶ │ KaraokeServer ─▶ jitter buffer per mic        │
 │ (VOICE_PERFORMANCE)      │ ◀─────────────────── │   (NSD advertised)       │                    │
 │ leveler, auto-tune, gain │   WELCOME, RTT       │ TV mic (USB / remote) ───┤                    │
 └──────────────────────────┘                      │                     KaraokeMixer              │
                                                   │   gate ─▶ auto level ─▶ sum ─▶ volume ─▶ EQ  │
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
4. Sing. Up to 4 phones can sing at once (duets, groups). Optional: turn on **Auto-tune** on the phone.

Karaoke mode stays on between videos, so phones don't reconnect for every song. It turns off from the
menu, with a long press on the mic button, or when SmartTube exits.

### Karaoke menu (TV)

| Option | What it does |
|---|---|
| Karaoke mode | Opens the mic port and advertises the TV on the LAN |
| Connect a microphone · PIN 123 456 | The phone PIN at a glance; opens instructions and the TV address |
| Connected mics: N | Opens each mic with its buffer delay and packet loss (always up to date) |
| Mic volume | 0–300% for all mics |
| Voice effect | Off, Room reverb, Hall reverb, Karaoke echo |
| Vocal remover | Off / 50 / 75 / 100%. Removes centre-panned vocals between ~150 Hz and 7 kHz and keeps bass, drums and stereo instruments. Works on most studio mixes, less well on live or mono recordings |
| Key (semitones) | −6…+6, pitch shift without changing tempo; stays right when the playback speed changes |
| Music volume (while singing) | 0–100% of the video's volume while karaoke is on, to balance it against the voices. The normal volume comes back when karaoke turns off |
| Mic latency | Low 25 ms / Normal 50 ms / Stable 100 ms of jitter buffering per mic |
| Mic options | Auto mic level (evens out quiet and loud phones and singers, on by default); lower the music while someone sings (on by default); noise gate (mutes mics between phrases); use the TV's own mic; require a PIN for phone mics (on by default) |
| New PIN | Makes a new PIN, shows it and disconnects the phones paired with the old one. Those phones say "The TV has a new PIN" and ask for it |
| Find a karaoke version | Searches YouTube for "<song title> karaoke" |

Vocal remover and key change apply only while karaoke mode is on, so normal videos never lose their vocals.
The menu marks them "not with a Dolby audio track" when SmartTube passes Dolby audio straight to the TV or
soundbar, and the key "off with tunneled playback" (tunneling doesn't allow pitch shifting).

Voices play only while SmartTube is on screen: when another app is in front, the mixer pauses and phones stay
connected, so singing goes on when you come back. Android may also pause SmartTube itself in the background.

"Use the TV's own mic" is remembered and starts with karaoke mode. SmartTube lets go of the mic while it's in the
background or its voice search is open, so voice search and other apps can use it.
The player button can be hidden in Settings → Player → Player buttons → Karaoke.

## Voice first

The voice should be clearly louder than the music and easy to understand. Phone mics and YouTube music make that
hard: the low latency capture path the phone app uses (VOICE_PERFORMANCE) is recorded very quietly by spec (about
-36 dBFS RMS for a loud voice; on a Pixel 6 normal singing came in around -45 dBFS), while YouTube music is mastered
loud (about -14 LUFS). Before this was tuned, singing ended up some 13 dB under the music, and the TV's noise gate
cut in and out on it.

- Phone (`VoiceLeveler`, before sending): high pass at 90 Hz (handling noise, pops), then the voice is brought to
  -18 dBFS RMS. It learns only while someone sings (voice is well above a tracked floor of room and TV sound), starts
  at +18 dB on the quiet capture paths and goes up to +30 dB. Between phrases what the phone hears (the TV, and the
  singer's own voice from the speakers) is turned down 15 dB. A limiter keeps loud notes from clipping.
- TV: the noise gate opens at -40 dBFS and auto level aims at -15 dBFS RMS (up to +24 dB for mics that don't level
  themselves). The voice gets an EQ (cut below 100 Hz, -2 dB at 300 Hz against mud, +3.5 dB at 3 kHz presence) and is
  played in stereo (some TVs play mono tracks 3 dB quieter).
- Music steps back by 6 dB while someone sings (`MusicDucker`): down in ~0.1 s, stays down through breaths and short
  gaps (1.5 s), back up over ~1 s. "Lower the music while someone sings" in Mic options turns it off.
- Lost packets and network hiccups no longer drop to silence (a click every lost 5 ms packet): the jitter buffer
  continues the voice by repeating its last pitch period for 10 ms, fades it out over 50 ms and blends back into the
  real audio.

Simulated with Pixel-like levels (singing at -45 dBFS RMS, the TV heard 20 dB lower, 2% packet loss, music at
-14 dBFS): before, the voice reached the speakers at -27.6 dBFS, 13.6 dB under the music; now at -17.3 dBFS,
2.7 dB over the music while singing, with the TV sound between lines 22 dB under the voice until the gate closes.

Tips: sing into the bottom edge of the phone (where the mic is), about a hand's width away, and keep the phone away
from the TV speakers. For even more voice, raise Mic volume on the TV (up to 300%).

## Auto-tune (phone)

The SmartTube Mic app can pull the singer's voice to the nearest note before sending it to the TV. Each singer
picks their own setting, and it can change while singing.

| Setting | What it does |
|---|---|
| Off | The voice as it is (default) |
| Natural | Corrects the average pitch of each note over ~80 ms: off notes come in tune, vibrato and slides stay |
| Strong | Corrects within ~25 ms: firmer, still glides between notes |
| Robot | Instant: every note is flat and jumps to the next one, the well-known robotic effect |
| Key | Any note (nearest semitone, works for every song), or a major/minor key so only its notes are used |

While singing, the card shows the note being sung and how far off it is ("You sing A3 −12¢").

How it works (all in `karaoke/core`, on the phone's audio thread, no extra network traffic):
- `PitchDetector`: YIN on a 4x decimated copy every 5 ms frame, refined at 48 kHz. 75 Hz–1 kHz, bass to soprano,
  accurate to a fraction of a cent on steady tones. Silence, noise, consonants and breaths count as no pitch and
  are left alone.
- `AutoTune`: the nearest note of the key, with a little hysteresis so a voice sung between two notes doesn't flip
  between them, and a smoothed correction (the retune speed above).
- `PitchShifter`: a delay line read slightly faster or slower than it's written. When the delay drifts out of range,
  the read position jumps by exactly one pitch period with a 5 ms crossfade, which is inaudible because the voice
  repeats every period. Added delay is ~2–6 ms on average (up to ~12 ms for deep bass voices); overlap-add methods
  (PSOLA) would need two periods, ~17–25 ms for male voices. Formants move with the pitch, by 3% for a half semitone
  correction, which is hard to hear.

Cost: ~27 µs per 5 ms frame on a laptop; on the emulator the app used ~2% more CPU with Robot on.
Limits: one voice per phone; the key isn't detected from the song (pick it, or use Any note); very fast pitch
changes (speech, runs) are corrected a few ms late, so Robot mode on speech is less exact than on held notes.

## Microphone options

| Mic | Quality | Delay | Notes |
|---|---|---|---|
| **SmartTube Mic app** (Android phone) | 48 kHz, no voice-call processing | ~40–100 ms total | Recommended. Continuous, any number of songs, up to 4 phones, optional auto-tune |
| **USB mic or USB wireless (UHF) mic receiver** plugged into the TV box | Best | Lowest | Turn on Mic options → "Use the TV's own mic". Works on boxes with USB audio input (e.g. Nvidia Shield, most Android TV boxes) |
| **Google TV app / remote mic** | Speech quality | High | Also via "Use the TV's own mic". Most TVs only stream it while the mic button is held and send it to Google Assistant first, so it suits short tests rather than whole songs. Behaviour depends on the TV |
| **Bluetooth karaoke mic with built-in speaker** | Good | n/a | Needs no app support: the mic mixes your voice into its own speaker. Pair it as the TV's audio output (video sound may lag slightly) |
| iPhone | — | — | Not supported yet. Browsers only allow the mic on HTTPS pages, so a web mic needs the TV to serve HTTPS with a self-signed certificate. Possible future addition |

### Reducing delay

The voice you hear is delayed by phone capture + Wi-Fi + jitter buffer + TV audio output.

- Put the phone on **5 GHz Wi-Fi**; connect the TV by Ethernet if you can.
- Use **Mic latency → Low** when the Wi-Fi is good. Watch "loss" in Connected mics; switch back to Normal if it climbs.
- Turn on the TV's **Game mode** / low latency audio and avoid Bluetooth speakers or soundbars that add delay.
- Sing into the bottom edge of the phone (the mic) from about a hand's width, away from the TV speakers, to avoid
  feedback (howling). Keep the noise gate on.

### Good to know

- Turning a soundbar or HDMI device on/off changes the TV's audio output. Karaoke reopens its output by itself
  (up to 3 times a minute) and only gives up, with a message, if the TV has no usable output.
- The phone remembers the TV by name and follows it to a new IP address after a router restart, only when the old
  address is gone and exactly one TV has that name.
- Vocal remover and key change work on the decoded audio. If SmartTube plays a Dolby (AC-3/E-AC-3) track straight
  through to the TV or soundbar, they have no effect; pick a regular audio track in the player's quality menu.
- Karaoke off and on again (or a SmartTube restart) doesn't ask phones for the PIN again: they answer the new
  challenge with their saved PIN.
- Mic volume and auto mic level stack: auto level brings every singer to about the loudness of the music, mic
  volume then sets the balance.

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
| `karaoke/core` | Pure Java, shared by both apps, JVM unit tested: `KaraokeProtocol`, `KaraokePairing`, `PinChecker`, `PairingGuard`, `JitterBuffer`, `VocalRemover`, `AutoLevel`, `VoiceReverb`, `NoiseGate`, `Limiter`, `Biquad`, `KaraokeSearch`, `PitchDetector`, `PitchShifter`, `AutoTune`, `VoiceLeveler`, `MusicDucker` |
| `common/.../common/karaoke` | TV side: `KaraokeManager` (public API), `KaraokeServer` (UDP + NSD), `KaraokeMixer` (AudioTrack), `LocalMicCapture`, `MicSource`, `VocalRemoverAudioProcessor` (ExoPlayer) |
| `common/.../controllers/KaraokeController.java` | Player button and Karaoke menu |
| `common/.../prefs/KaraokeData.java` | Saved karaoke settings |
| `smarttubetv/.../actions/KaraokeAction.java` | Player button |
| `karaoke/micapp` | SmartTube Mic phone app: `MainActivity`, `MicStreamService` (foreground, works with the screen off), `MicStreamer` (capture, auto-tune, send), `TvFinder` (NSD) |

Small hooks into existing code: `PlaybackPresenter` registers the controller, `CustomOverridesRenderersFactory`
adds the vocal remover to ExoPlayer's audio processors, `PlayerTweaksData`/`PlayerSettingsPresenter`/`VideoPlayerGlue`
add the button, `ViewManager.properlyFinishTheApp` stops karaoke on exit.

## Protocol

UDP port 47900 (falls back to a random port if busy; NSD advertises the real one). NSD service type `_stkaraoke._udp`.
Header is 20 bytes, big-endian: magic `STKM`, version, type, flags, reserved, session id, sequence, sender clock (ms).

| Type | Direction | Payload |
|---|---|---|
| HELLO (1) | mic → TV, every second | sample rate (int), frame size (short), channels (byte), name, PIN proof (0 or 32 bytes), then with a proof the challenge it answers (long) |
| WELCOME (2) | TV → mic, header echoes the mic's session id | echoed sender clock (for round trip time), status, mic slot, TV name, pairing challenge (long, 0 for connected mics), TV id (long) |
| AUDIO (3) | mic → TV | mono 16 bit little-endian PCM, 48 kHz, 240 samples (5 ms) |
| BYE (4) | mic → TV | none |

Statuses: OK, FULL, UNSUPPORTED, PIN_REQUIRED, PIN_WRONG, PIN_LOCKED. Protocol version 3.

Pairing: a new mic's first HELLO gets PIN_REQUIRED and a challenge derived from a random per-run TV secret,
the mic's session id and its IP address. The next HELLO carries HMAC-SHA256(PIN, challenge + session id) and
echoes the challenge; the PIN itself never goes over the network. A proof for another challenge (the TV restarted,
the phone got a new IP) gets a new PIN_REQUIRED instead of counting as a wrong PIN.

Wrong PINs: 5 from one address lock it out for a minute, and 20 within a minute pause new pairings for everyone for
a minute (connected mics keep working). A proof of the PIN from before New PIN is answered PIN_WRONG without counting
or a message on the TV: that phone paired before and doesn't know the new PIN yet. Every other wrong proof counts,
so a phone that New PIN disconnected can't guess the new PIN faster than anyone else.

The phone saves PINs per TV id, a random id the TV makes once and sends in WELCOME, so TVs with the same name don't
share PINs.

A mic that sends nothing for 4 s is dropped. Lost packets and underruns are concealed (the voice continues for
up to 60 ms, see Voice first), late packets are discarded, and the jitter buffer corrects clock drift between phone
and TV by ±1 sample per frame.

## Privacy and security

- The TV listens for mics only while karaoke mode is on, and only devices on the same local network can reach it.
- Phones need the TV's 6-digit PIN (on by default, can be turned off in Mic options). Wrong guesses are rate
  limited (see Protocol), the TV shows a message for every mic that connects or enters a wrong PIN, and at most 4
  can join. A mic's audio/disconnect packets are accepted only from the device that paired, and the TV echoes the
  mic's random session id so the phone ignores stray replies.
- New PIN disconnects every phone; use it after guests leave. A disconnected phone gets no extra guesses.
- Limits: the PIN keeps other devices on the network from joining, it isn't strong authentication.
  Someone who can capture the Wi-Fi traffic of a pairing could brute force the PIN offline, and a device on the
  network that pretends to be the TV (same name and TV id) could get a phone's saved-PIN proof and do the same.
  Closing that needs a password-authenticated key exchange (PAKE) instead of HMAC.
- Anyone on the network can send a few wrong PINs to pause new pairings for a minute (connected mics keep working).
- Audio is sent unencrypted over the LAN and is never recorded or stored.
- The phone shows a notification while the mic is on, with Mute / Stop buttons, so it can be controlled with the
  screen off. Its title says "Mic is live" only while the TV gets the voice; otherwise "Mic is muted", "PIN needed"
  or "Not connected", with the details below.

## Testing done

- `:karaokecore:test`: 93 unit tests (protocol round trips and truncated packets, jitter buffer
  loss/late/restart/drift/burst and cross-thread target changes, vocal remover removes centred 300 Hz/1 kHz/3 kHz,
  keeps bass, cymbals and stereo content, has no dip at the crossovers, bypasses bit-exact and cleans the centre
  channel of 5.1, auto level, reverb tails decay without NaN, limiter ceiling, noise gate, karaoke search queries,
  PIN proof/challenge, wrong-PIN rate limiting, old-PIN rule after New PIN, whole pairing handshake, and for
  auto-tune: pitch of tones from 82 Hz to 988 Hz within 3 cents, no octave error on voice-like tones or a weak
  fundamental, glides followed, noise and silence have no pitch, the shifter is a pure delay at ratio 1, shifts by
  exact semitones, doesn't click at splices even with a wrong period and keeps its delay in range, Robot snaps to the
  nearest note, keys pick the right note, Natural keeps vibrato and Robot flattens it, no flapping between two notes,
  noise passes unchanged, switching on and off mid-song doesn't click, Off is bit exact; and for voice first: a quiet
  phone mic comes up to the target and a loud one down, the TV sound between phrases ends up 35 dB under the voice,
  TV sound alone doesn't raise the gain, steady singing doesn't pump, rumble is removed, the music ducker's timing,
  peaking EQ and high pass response, a lost packet is concealed within 3% of the real audio, long losses fade to
  silence, underruns continue the voice and blend back without clicks).
- Auto-tune on generated audio (macOS `say`, a singing voice detuned by up to 45 cents): voiced frames within
  10 cents of a note went from 25% to 67% (Natural), 81% (Strong) and 91% (Robot, 97% on held notes, mean 3 cents
  off). On the emulator: the Auto-tune card, mode and key menus (saved), streaming to the TV with Robot on.
- On an Android 16 emulator (phone image) with both apps installed: player button and Karaoke menu, NSD discovery,
  phone → TV streaming (Connected mics: buffer 40 ms, loss 0.0%), mixer AudioTrack playing alongside the video,
  vocal remover 100% + key +2 with playback continuing, karaoke off/on with automatic reconnect, phone Stop
  (BYE → "Mic disconnected"), TV mic capture via `AudioRecord`.
- Re-tested after review fixes (session id echo, address checks, failure handling): connect, stats, phone Stop.
- PIN pairing on the emulator: PIN in the "Karaoke on" message, phone prompt, wrong PIN rejected (phone message +
  TV message), right PIN connects, phone reconnects without asking (saved PIN), New PIN on the TV drops the phone
  and it asks again.
- After the review round (emulator): refined menu with the PIN as "123 456", karaoke off/on reconnects phones
  without a PIN prompt, notification Mute/Unmute and titles, TV mic opening in the background thread, output
  buffer growing on underruns, New PIN (TV message, no false "Wrong PIN" alarm, phone says "The TV has a new PIN",
  a typed wrong PIN is counted and shown on the TV, the right one reconnects).
- Not tested: real TV hardware, real singing (the emulator mic is silent), audio quality and delay on real Wi-Fi,
  and whether a given TV routes the Google TV app mic to other apps.
