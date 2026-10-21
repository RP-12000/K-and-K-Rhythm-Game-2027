package org.kelvinizer.profile;

/**
 * 本机设置（{@code profiles/device.json}）：音量、各音频输出的延迟、画面延迟、粒子画质、背景细节、界面缩放和边距等。
 * 它们描述的是设备而不是潜水员，所以本机的所有存档共用这一份。
 *
 * <p>Device-scoped settings stored in {@code profiles/device.json} (DESIGN §9.2, Appendix A.12):
 * they describe the hardware (speaker latency, screen) rather than the diver, so they are
 * shared by every profile on the device. Rows backed by these fields carry the SHARED / 公用 tag (DEVICE / 本机
 * until 2026-10-18 04:48).
 *
 * <p>The effective audio offset is the active output's value ({@link #audioOffsetMs()}).
 * Changes must be followed by {@code ProfileStore.markDirty()}.
 */
public class DeviceSettings {
    /** {@link #activeOutput}: built-in speaker. */
    public static final int OUTPUT_SPEAKER = 0;
    /** {@link #activeOutput}: wired headphones. */
    public static final int OUTPUT_WIRED = 1;
    /** {@link #activeOutput}: Bluetooth. */
    public static final int OUTPUT_BT = 2;
    /** Display names of the outputs, by index. */
    public static final String[] OUTPUT_NAMES = {"SPEAKER", "WIRED", "BT"};

    /** {@link #particleQuality}: off. */
    public static final int PARTICLES_OFF = 0;
    /** {@link #particleQuality}: low. */
    public static final int PARTICLES_LOW = 1;
    /** {@link #particleQuality}: high (default). */
    public static final int PARTICLES_HIGH = 2;

    /** {@link #backgroundDetail}: low. */
    public static final int DETAIL_LOW = 0;
    /** {@link #backgroundDetail}: high (default). */
    public static final int DETAIL_HIGH = 1;

    /** Audio offset limits in ms. */
    public static final int AUDIO_OFFSET_MIN = -300, AUDIO_OFFSET_MAX = 300;
    /** Visual offset limits in ms. */
    public static final int VISUAL_OFFSET_MIN = -150, VISUAL_OFFSET_MAX = 150;

    /** Schema version of device.json. */
    public int schemaVersion = 1;
    /** Music volume 0..1 (menu, preview, result, songs). */
    public float musicVolume = 0.8f;
    /** Hit-sound volume 0..1. */
    public float sfxVolume = 0.8f;
    /** UI sound volume 0..1 (P2, hidden until UI SFX exist). */
    public float uiVolume = 0.6f;
    /** Per-output audio offsets in ms (+ = the player hears audio late). */
    public Outputs outputs = new Outputs();
    /** Active output: {@link #OUTPUT_SPEAKER}, {@link #OUTPUT_WIRED}, {@link #OUTPUT_BT}. */
    public int activeOutput = OUTPUT_SPEAKER;
    /**
     * Visual offset in ms (−150..+150). Unused since 2026-10-18 (the setting was removed; gameplay
     * passes 0); kept so device.json files round-trip.
     */
    public int visualOffsetMs = 0;
    /** Particle quality: {@link #PARTICLES_OFF}, {@link #PARTICLES_LOW}, {@link #PARTICLES_HIGH}. */
    public int particleQuality = PARTICLES_HIGH;
    /** Backdrop detail: {@link #DETAIL_LOW} or {@link #DETAIL_HIGH}. */
    public int backgroundDetail = DETAIL_HIGH;
    /** UI scale: 0 = Auto, else 0.9..1.3. */
    public float uiScale = 0f;
    /** Extra edge padding in world units, 0..48. */
    public int edgePadding = 0;
    /** Show the FPS counter. */
    public boolean showFps = false;
    /** Particles were automatically dropped to Low (announced once). */
    public boolean particlesAutoDropped = false;
    /** Backdrop was automatically dropped to Low (announced once). */
    public boolean backdropAutoDropped = false;
    /**
     * Measured music start latency in ms (0 = not measured yet): a song's music is started this
     * much early so it sounds on time (SongClock, learned from each song start).
     */
    public float musicStartLatencyMs = 0f;
    /**
     * Measured music resume latency in ms (0 = not measured yet): how long a paused song takes to sound again once
     * put back at the paused time; the resume countdown starts its music this much early (SongClock, learned from
     * each resume).
     */
    public float musicResumeLatencyMs = 0f;

    /** Audio offsets per output, in ms. */
    public static class Outputs {
        /** Built-in speaker offset. */
        public int speaker = 0;
        /** Wired headphones offset. */
        public int wired = 0;
        /** Bluetooth offset. */
        public int bt = 0;

        /** Creates zero offsets. */
        public Outputs() {
        }

        /** @return the offset of output {@code index}. */
        public int get(int index) {
            switch (index) {
                case OUTPUT_WIRED: return wired;
                case OUTPUT_BT: return bt;
                default: return speaker;
            }
        }

        /** Sets the offset of output {@code index}. */
        public void set(int index, int ms) {
            switch (index) {
                case OUTPUT_WIRED: wired = ms; break;
                case OUTPUT_BT: bt = ms; break;
                default: speaker = ms; break;
            }
        }
    }

    /** Creates default device settings. */
    public DeviceSettings() {
    }

    /** @return the effective audio offset (the active output's value) in ms. */
    public int audioOffsetMs() {
        return outputs.get(activeOutput);
    }

    /** Sets the active output's audio offset, clamped to ±300 ms. */
    public void setAudioOffsetMs(int ms) {
        outputs.set(activeOutput, Settings.clamp(ms, AUDIO_OFFSET_MIN, AUDIO_OFFSET_MAX));
    }

    /** @return display name of the active output. */
    public String activeOutputName() {
        return OUTPUT_NAMES[Settings.clamp(activeOutput, 0, 2)];
    }

    /** Clamps every value into its valid range (after loading untrusted JSON). */
    public void sanitize() {
        if (outputs == null) outputs = new Outputs();
        musicVolume = Settings.clamp(musicVolume, 0f, 1f);
        sfxVolume = Settings.clamp(sfxVolume, 0f, 1f);
        uiVolume = Settings.clamp(uiVolume, 0f, 1f);
        musicStartLatencyMs = Float.isNaN(musicStartLatencyMs) ? 0f : Settings.clamp(musicStartLatencyMs, 0f, 400f);
        musicResumeLatencyMs = Float.isNaN(musicResumeLatencyMs) ? 0f : Settings.clamp(musicResumeLatencyMs, 0f, 400f);
        outputs.speaker = Settings.clamp(outputs.speaker, AUDIO_OFFSET_MIN, AUDIO_OFFSET_MAX);
        outputs.wired = Settings.clamp(outputs.wired, AUDIO_OFFSET_MIN, AUDIO_OFFSET_MAX);
        outputs.bt = Settings.clamp(outputs.bt, AUDIO_OFFSET_MIN, AUDIO_OFFSET_MAX);
        activeOutput = Settings.clamp(activeOutput, 0, 2);
        visualOffsetMs = Settings.clamp(visualOffsetMs, VISUAL_OFFSET_MIN, VISUAL_OFFSET_MAX);
        particleQuality = Settings.clamp(particleQuality, 0, 2);
        backgroundDetail = Settings.clamp(backgroundDetail, 0, 1);
        if (uiScale != 0f) uiScale = Settings.clamp(uiScale, 0.9f, 1.3f);
        edgePadding = Settings.clamp(edgePadding, 0, 48);
    }
}
