package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;

/**
 * 某个深度的全部配色结果：海水渐变、强调色、微粒和光柱颜色，
 * 以及光柱和焦散强度、暗角、噪点、悬浮颗粒和发光微粒数量等参数。
 * 由 {@link DepthPalette#params(float, DepthParams)} 填写；背景持有一份，画面也可以各自持有，不要跨线程共用。
 *
 * <p>Plain mutable holder for everything the depth palette drives (DESIGN §1.2, §2.3).
 * Filled by {@link DepthPalette#params(float, DepthParams)}; owned by whoever needs it
 * (the backdrop keeps one, screens may keep their own). Never share an instance between
 * threads.
 */
public final class DepthParams {
    /** Depth in metres these values were computed for. */
    public float meters;
    /** Layer index 0..5 (L1..L6) that holds the depth. */
    public int layer;
    /** Blend factor 0..1 toward layer {@code layer + 1} (thermocline, last 25 % of a band). */
    public float blend;

    /** Water gradient top. */
    public final Color top = new Color();
    /** Water gradient middle. */
    public final Color mid = new Color();
    /** Water gradient bottom. */
    public final Color bottom = new Color();
    /** Layer accent (sonar rings, chapter names, rank colour). */
    public final Color accent = new Color();
    /** Second accent (L6 only uses cyan beside magenta; equals {@link #accent} elsewhere). */
    public final Color accent2 = new Color();
    /** Bio-mote colour. */
    public final Color mote = new Color();
    /** Second mote colour (L6 alternates magenta / cyan). */
    public final Color mote2 = new Color();
    /** Light-shaft / caustic colour. */
    public final Color shaft = new Color();

    /** Light-shaft strength 0..1. */
    public float shaftStrength;
    /** Caustic strength 0..1. */
    public float caustic;
    /** Vignette strength. */
    public float vignette;
    /** Grain strength. */
    public float grain;
    /** Ambient hero-glitch interval in seconds. */
    public float idleGlitchSec;
    /** Particulate count at High quality. */
    public float particulates;
    /** Bio-mote count. */
    public float bioMotes;
    /** 0 = all bubbles, 1 = all snow. */
    public float snowMix;
    /** 0..1 share of particulates drawn as blinking L6 pixels. */
    public float pixelMix;

    /** Copies every field of {@code o} into this holder. */
    public DepthParams set(DepthParams o) {
        meters = o.meters;
        layer = o.layer;
        blend = o.blend;
        top.set(o.top);
        mid.set(o.mid);
        bottom.set(o.bottom);
        accent.set(o.accent);
        accent2.set(o.accent2);
        mote.set(o.mote);
        mote2.set(o.mote2);
        shaft.set(o.shaft);
        shaftStrength = o.shaftStrength;
        caustic = o.caustic;
        vignette = o.vignette;
        grain = o.grain;
        idleGlitchSec = o.idleGlitchSec;
        particulates = o.particulates;
        bioMotes = o.bioMotes;
        snowMix = o.snowMix;
        pixelMix = o.pixelMix;
        return this;
    }
}
