# Workout Instructor-Inator (Android)

Plays workout scripts as spoken instructions. Speech briefly pauses Spotify
(transient audio focus) and releases it after each utterance; beeps mix over
the music without pausing it.

## Script format
Same as the desktop tool: alternating instruction / `M:SS, keywords` lines,
`#` comments. Supported keywords: `countdown`, `announce_time`, `beepreps`,
`delaybeep N`. Not supported in v1: Sets (`@`), preferences (`$`),
`until_end_of_song`.

## Speech
gTTS (Google Translate endpoint) is fetched and cached per phrase during the
"Preparing" step (needs internet on the first run of each script; unofficial
endpoint — if it breaks or rate-limits, phrases fall back to the device TTS
engine automatically). Hebrew text is detected and synthesized as Hebrew.

## Sounds
`app/src/main/res/raw/`: `delay_indicator.wav` (delaybeep start),
`delay_end.wav` (delay finished), `rep_count.wav` (rep beeps) — replace the
files (same names) to change sounds.

## Build
Push to GitHub; Actions builds `app-debug.apk` and publishes it at
`releases/download/latest/app-debug.apk`.
