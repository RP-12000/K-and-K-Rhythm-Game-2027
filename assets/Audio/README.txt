K & K - result music slot (assets/Audio/)
=========================================

No result music ships with the game. This folder reserves the global result
track (DESIGN 6.7; ARCHITECTURE 6: AudioService "RESULT"). Owner docs in
Simplified Chinese: docs/ASSET_SLOTS.md (section 1.2) and
docs/CHART_FORMAT.md (section 12.2).

Lookup order
------------

The result screen plays the first file it finds:

  1. Charts/<song id>/result.ogg | .mp3 | .wav
                               The song's own result music, in its song
                               folder. It starts at the song's
                               "resultMusicStartMs" (manifest, ms, default 0).
  2. Audio/result.ogg | .mp3 | .wav
                               Global result music for every song without
                               its own file. Starts at 0.
  3. Introduction/Introduction.wav
                               Temporary fallback (the tutorial track).
                               Starts at 0.
  4. Silence.

For each name, .ogg is tried first, then .mp3, then .wav. The names are
lower case and exact.

resultMusicStartMs
------------------

Set it on the song's entry in assets/Charts/index.json, using the object form
of the entry:

  "songs": [
    { "id": "Song 1", "resultMusicStartMs": 15000 }
  ]

It applies only to that song's own result.* (step 1). The global file and the
fallback always start at 0.

Playback
--------

  * Loaded while the ASCENT transition covers the screen, started silent.
  * Fades in over 1.2 s, as the ASCENT opens, to musicVolume x 0.8.
  * Loops. Ducked briefly on a level-up.
  * CONTINUE fades it out over 450 ms; RETRY stops it at once.
  * Volume follows the device "Music" setting.

Related slots
-------------

  * Hit sounds (hit, hit_echo, hold_end, mine): assets/Sounds/README.txt.
  * Menu music: assets/background.ogg | .mp3 | .wav.
  * Gameplay background image: Charts/<song id>/bg.png (or .jpg / .jpeg),
    else assets/ChartVisualizer.png.
