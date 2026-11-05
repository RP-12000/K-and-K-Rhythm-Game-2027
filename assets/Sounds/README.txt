K & K - reserved sound-effect slots (assets/Sounds/)
=====================================================

The game ships one sound here, hit.wav (Kelvin's own hit sound, 2026-10-26).
The other names are reserved so sounds can be dropped in later without code
changes (DESIGN 5.14, 5.15; ARCHITECTURE: AudioService "HIT (reserved)"). Owner docs in Simplified
Chinese: docs/ASSET_SLOTS.md (section 1.1).

Every slot is optional. When a file is missing the game stays silent for that
event, and nothing else changes.

Files
-----

  hit.ogg | hit.wav            Hit sound, played for RESONANCE and ECHO
                               judgements (never for LOST).
                               With the setting "Hit sound mode" = "Any zone
                               tap", it also plays for taps that judge no note.
                               If this file is absent, the "Hit sound" slider
                               in Settings shows "NO HIT SOUND INSTALLED" and
                               is disabled.

  hit_echo.ogg | hit_echo.wav  Optional. Played instead of hit.* for ECHO
                               judgements. If absent, ECHO uses hit.*.

  hold_end.ogg | hold_end.wav  Optional. Played when a STREAM (hold) note is
                               completed (its release seal).

  mine.ogg | mine.wav          Optional. Played when a MINE is touched
                               (DESIGN 5.15). It is reserved in the same way
                               and is silent when absent. A mine touch never
                               plays hit.*.

Rules
-----

  * Lookup: for each slot, <name>.wav is tried first, then <name>.ogg.
    The names are lower case and exact.
  * Phones (Android 8.0 and later): the hit sounds play through the game's
    own low-latency output, which reads only the .wav (16-bit PCM, any rate,
    mono or stereo). Each hit is heard at an exact moment there: a tap
    sounds as it lands, the hits of a chord sound once, and autoplay's
    hits land on their notes. Without a .wav the slot uses the
    older, slower SoundPool path.
  * Loading: hit, hit_echo, hold_end and mine are loaded together the first
    time a hit sound is needed, as libGDX Sound objects (fully decoded in
    memory).
    Each one is then primed with a single volume-0 play. This avoids the
    Android SoundPool delay on the first real play.
  * Timing: the sound is played inside the touch input handler, right after
    the judgement. It does not wait for the next frame.
  * Volume: the device "SFX volume" setting (sfxVolume, default 80 %). At 0 %
    nothing is played.
  * Format: keep each file short (under ~0.3 s) and mono. Use 44.1 kHz or
    48 kHz. Trim any leading silence, because silence there adds directly
    to the perceived latency. Use .wav (16-bit PCM) so phones can use
    their low-latency output.
  * Android: SoundPool limits a decoded sample to about 1 MB, so keep these
    files small.
  * The particle effects (assets/ParticleEffects/*.p) do not depend on these
    files.

Related slots
-------------

  * Result music (per song and global): assets/Audio/README.txt.
      Charts/<song id>/result.(ogg|mp3|wav), then Audio/result.*, then the
      temporary fallback Introduction/Introduction.wav.
  * Menu music: assets/background.(ogg|mp3|wav).
  * Gameplay background image: Charts/<song id>/bg.png (or .jpg / .jpeg) is
    drawn behind the notes at alpha .15 x the "Art opacity" setting; without
    it the global assets/ChartVisualizer.png is used. "Background art" off
    hides both.
  * Full list of slots (sounds, music, images, fonts, effects, songs,
    chapters): docs/ASSET_SLOTS.md.
