package org.kelvinizer.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.Viewport;

/**
 * 所有画面共用的程序海水背景：着色器画出海水渐变、光柱和焦散，
 * 再叠上气泡、海雪、发光微粒、声呐波纹和偶尔的故障横条，颜色随深度变化。
 * 由 Main 持有，切换画面时海水连续不断；帧太慢时自动降到不用着色器的低画质（LOW）。
 *
 * <p>The shared procedural ocean background (DESIGN §2): one fragment-shader pass (no FBO) with
 * an optional CAUSTICS variant, a LOW path without shaders, an optional image hook and jacket
 * wash, pooled particulates (bubbles / marine snow / L6 pixels on three parallax planes),
 * bio-motes, the {@link SonarField}, and ambient tear bars. Owned by Main and shared by every
 * screen so the water is continuous across screens.
 *
 * <p><b>Draw calls</b> (DESIGN §2.9): on HIGH the image hook and the wash are sampled by the water
 * shader itself (an {@code IMAGE} variant with two extra samplers on texture units 1 and 2), so
 * the backdrop costs one shader draw plus the atlas draws of the dim scrim and particulates (one
 * more on menus for the additive block). On LOW, or when the IMAGE variant fails to compile,
 * the image and the wash are drawn as their own textured quads.
 *
 * <p>Usage per frame: configure (setDepth, setFocus, setCameraMotion, ...) and call
 * {@link #render(Batch, Viewport, float)} first in the screen's draw. The backdrop is drawn in
 * the screen frame of the given viewport (pass the screen viewport even on the map, and feed the
 * map camera through {@link #setCameraMotion}).
 *
 * <p>Batch state: if the batch is not drawing, render() sets the viewport's projection, begins
 * and ends it; if it is drawing, the caller's projection is used and the batch stays open. The
 * blend function is restored to normal alpha blending.
 */
public final class Backdrop implements Disposable {

    /** Screen family: menus (full motion) or the calmer gameplay variant (§2.7). */
    public enum Mode { MENU, GAMEPLAY }

    /** Background detail (§2.4): HIGH = shader, LOW = sprite path. */
    public enum Detail { LOW, HIGH }

    private static final int MAX_P = 160;
    /** Gameplay snow alpha cap (DESIGN §2.7). */
    private static final float GAMEPLAY_SNOW_ALPHA = 0.20f;
    private static final int MAX_MOTES = 32;
    private static final float[] PLANE = {0.3f, 0.6f, 1.0f};
    private static final float MARGIN = 60f;
    private static final float CAUSTIC_MAX_DEPTH = 300f;
    /** Shaft noise x per world unit and the resting slant per unit of (1 − v) (DESIGN §2.4). */
    private static final float SHAFT_SCALE = 0.0011f, SHAFT_SLANT = 0.35f;

    private final Draw draw;
    private final SonarField sonar;
    private ShaderProgram shader;
    private ShaderProgram shaderCaustics;
    private ShaderProgram shaderImage;
    private ShaderProgram shaderImageCaustics;
    private boolean shaderOk;
    /** True when the IMAGE variants compiled (image hook and wash folded into the shader pass). */
    private boolean imageShaderOk;
    /** True while the image and wash of the current frame were drawn by the shader pass. */
    private boolean folded;
    private String shaderLog = "";

    // state ---------------------------------------------------------------------------------
    private final DepthParams params = new DepthParams();
    private float depth, targetDepth;
    private Mode mode = Mode.MENU;
    private Detail detail = Detail.HIGH;
    private float intensity = 1f;
    private float shaftScale = 1f;
    private boolean causticsAllowed;
    private float dim;
    private float particleScale = 1f;
    private boolean reduceMotion;
    private float time;

    private Texture image;
    private float imageAlpha = 0.25f, imageParallax;
    private float touchParX, touchParY;
    private Texture wash;
    private float washAlpha;
    /** The song's 16:9 framing of the jacket under the wash ({x, y, w, h} fractions, y from the top), or null. */
    private float[] washBox;
    /** Cover-cropped rect of the image / wash computed by {@link #imageRect} / {@link #washRect}. */
    private float rectX, rectY, rectW, rectH;

    private float shaftTilt, shaftTiltTarget;

    private float camVx, camVy, parX, parY, virtVx, virtVy, virtX, virtY;
    private float pulse;
    private float lastBeatPhase = 1f;
    private int combo, lastCombo;

    // particulates (struct of arrays, normalised wrap space 0..1) ------------------------------
    private final float[] pX = new float[MAX_P], pY = new float[MAX_P], pSize = new float[MAX_P];
    private final float[] pSpeed = new float[MAX_P], pPhase = new float[MAX_P], pSway = new float[MAX_P];
    private final float[] pPeriod = new float[MAX_P], pRoll = new float[MAX_P], pRoll2 = new float[MAX_P];
    private final float[] pFade = new float[MAX_P];
    private final int[] pPlane = new int[MAX_P];

    // bio-motes --------------------------------------------------------------------------------
    private final float[] mHx = new float[MAX_MOTES], mHy = new float[MAX_MOTES], mSize = new float[MAX_MOTES];
    private final float[] mPh1 = new float[MAX_MOTES], mPh2 = new float[MAX_MOTES], mP1 = new float[MAX_MOTES];
    private final float[] mP2 = new float[MAX_MOTES], mAmp = new float[MAX_MOTES], mFlare = new float[MAX_MOTES];
    private final float[] mOx = new float[MAX_MOTES], mOy = new float[MAX_MOTES], mVx = new float[MAX_MOTES];
    private final float[] mVy = new float[MAX_MOTES], mFade = new float[MAX_MOTES], mPulse = new float[MAX_MOTES];
    private float moteDim = 1f;

    // tear bars --------------------------------------------------------------------------------
    private float nextTear = 3f, tearStart = -10f, tearDur;
    private int tearSeed;

    // perf probe / fallback --------------------------------------------------------------------
    private boolean autoFallback = true;
    private Runnable onAutoLow;
    private Viewport lastViewport;
    private final FrameProbe probe = new FrameProbe();

    // view rect of the current render ---------------------------------------------------------
    private float vx0, vy0, vw, vh;

    // shader uniform locations (indices: see UNIFORMS)
    private final int[] locBase = new int[UNIFORMS.length];
    private final int[] locCau = new int[UNIFORMS.length];
    private final int[] locImg = new int[UNIFORMS.length];
    private final int[] locImgCau = new int[UNIFORMS.length];

    private final Color tint = new Color();
    private final Color tmp = new Color();

    /** Builds the backdrop, compiling both shader variants once (falls back to LOW on failure). */
    public Backdrop(Draw draw) {
        this.draw = draw;
        this.sonar = new SonarField(draw);
        compileShaders();
        seedParticles();
        seedMotes();
        DepthPalette.params(0f, params);
    }

    // ================================================================== configuration
    /** Animates the palette toward {@code meters} (τ = 0.6 s on the depth value). */
    public void setDepth(float meters) {
        targetDepth = Math.max(0f, meters);
    }

    /** Jumps to {@code meters} immediately. */
    public void setDepthImmediate(float meters) {
        targetDepth = depth = Math.max(0f, meters);
        DepthPalette.params(depth, params);
    }

    /** Current (animated) depth in metres. */
    public float depth() {
        return depth;
    }

    /** Target depth in metres. */
    public float targetDepth() {
        return targetDepth;
    }

    /** Optional image hook (null = none), drawn cover-cropped at the current image alpha. */
    public void setImage(Texture tex) {
        image = tex;
    }

    /** Image hook with its alpha (DESIGN §2.8 values × Art opacity). */
    public void setImage(Texture tex, float alpha) {
        image = tex;
        imageAlpha = alpha;
    }

    /** Fraction of the camera pan applied to the image (map 0.15; menus 0 = touch parallax). */
    public void setImageParallax(float factor) {
        imageParallax = factor;
    }

    /** Gameplay jacket wash (a tiny texture drawn cover-cropped), null = none. */
    public void setWash(Texture tex, float alpha) {
        setWash(tex, alpha, null);
    }

    /** As above, the song's 16:9 framing ({x, y, w, h} fractions, y from the top; null = the centre) covering. */
    public void setWash(Texture tex, float alpha, float[] box) {
        wash = tex;
        washAlpha = alpha;
        washBox = box;
    }

    /** Motion intensity 0..1 (scales particle speed, sway and shaft drift). */
    public void setIntensity(float motion01) {
        intensity = MathUtils.clamp(motion01, 0f, 1f);
    }

    /** Menu or gameplay variant. */
    public void setMode(Mode m) {
        mode = m == null ? Mode.MENU : m;
    }

    /** Current mode. */
    public Mode mode() {
        return mode;
    }

    /** Background detail; HIGH is ignored when the shader failed to compile. */
    public void setDetail(Detail d) {
        detail = d == null ? Detail.HIGH : d;
        probe.clearFailures();
        resetProbe();
    }

    /** Effective detail. */
    public Detail detail() {
        return shaderOk ? detail : Detail.LOW;
    }

    /** True when the shader compiled. */
    public boolean isShaderAvailable() {
        return shaderOk;
    }

    /** Shader compile log (empty when fine). */
    public String shaderLog() {
        return shaderLog;
    }

    /** Allows the CAUSTICS variant (Welcome, map) — used only while depth &lt; 300 m. */
    public void setCaustics(boolean allowed) {
        causticsAllowed = allowed;
    }

    /** Multiplier on the layer shaft strength (Profile/Settings use 0.6). */
    public void setShaftScale(float k) {
        shaftScale = Math.max(0f, k);
    }

    /**
     * Tilts the light shafts by {@code degrees} (counter-clockwise, 0 = their resting slant), eased
     * with τ 0.15 s. Reduce motion forces 0. Welcome sets ±4° while the player drags (DESIGN §7).
     */
    public void setShaftTilt(float degrees) {
        shaftTiltTarget = degrees;
    }

    /** Current (eased) shaft tilt in degrees. */
    public float shaftTilt() {
        return shaftTilt;
    }

    /** Gameplay background dim scrim alpha ({@code #02040F @ dim}). */
    public void setDim(float alpha) {
        dim = MathUtils.clamp(alpha, 0f, 1f);
    }

    /** Particle count multiplier (Low particle quality = 0.5, off = 0). */
    public void setParticleScale(float k) {
        particleScale = MathUtils.clamp(k, 0f, 1f);
    }

    /** Reduce motion: no parallax, no snow streaking. */
    public void setReduceMotion(boolean value) {
        reduceMotion = value;
    }

    /** Heartbeat source at a point (2.4 s, 900, α .18, layer accent). */
    public void setFocus(float x, float y) {
        if (sonar.hasHeartbeat()) sonar.moveHeartbeat(x, y);
        else sonar.setHeartbeat(x, y, Anim.HEARTBEAT, 900f, 0.18f, params.accent);
    }

    /** Removes the heartbeat source. */
    public void clearFocus() {
        sonar.clearHeartbeat();
    }

    /**
     * Camera motion for parallax and streaks: velocity (world units/s) and camera position.
     * The water uses 0.5 of the pan, particulate planes 0.3/0.6/1.0, the image its own factor.
     */
    public void setCameraMotion(float vx, float vy, float parallaxX, float parallaxY) {
        camVx = vx;
        camVy = vy;
        parX = parallaxX;
        parY = parallaxY;
    }

    /**
     * A virtual camera velocity integrated internally (the dive / ascent streaks without a real
     * camera). Set back to 0 to stop.
     */
    public void setVirtualVelocity(float vx, float vy) {
        virtVx = vx;
        virtVy = vy;
    }

    /**
     * Gameplay beat feed, call every frame with the phase 0..1 inside the current beat; a wrap of
     * the phase is a beat (shader pulse); {@code barStart} marks the first beat of a bar
     * (bar ping).
     */
    public void setBeat(float phase01, boolean barStart) {
        if (phase01 < lastBeatPhase - 0.5f) beat(barStart);
        lastBeatPhase = phase01;
    }

    /** One beat event: pulse 1 → 0 over 200 ms; bar ping when {@code bar}. */
    public void beat(boolean bar) {
        pulse = 1f;
        if (bar) {
            sonar.ping(vx0 + vw * 0.5f, vy0 + vh * 0.5f, 700f, 1.2f, params.accent, 0.06f, 1);
        }
    }

    /** Gameplay combo for the combo-life motes (0 below 10, 24 at ≥ 100; scatter on break). */
    public void setComboLife(int c) {
        if (c < lastCombo && lastCombo >= 10) scatterAll();
        lastCombo = combo = Math.max(0, c);
    }

    /** Touch ping (140, 0.38 s, accent α .35) plus mote scatter within 220. */
    public void touch(float x, float y) {
        sonar.ping(x, y, 140f, 0.38f, params.accent, 0.35f, 1);
        touchParX = (x - (vx0 + vw * 0.5f)) / Math.max(1f, vw);
        touchParY = (y - (vy0 + vh * 0.5f)) / Math.max(1f, vh);
        for (int i = 0; i < MAX_MOTES; i++) {
            if (mFade[i] <= 0.01f) continue;
            float mx = moteX(i), my = moteY(i);
            float dx = mx - x, dy = my - y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 220f && d > 0.01f) {
                float k = (1f - d / 220f) * 900f;
                mVx[i] += dx / d * k;
                mVy[i] += dy / d * k;
            }
        }
    }

    /** The shared sonar field. */
    public SonarField sonar() {
        return sonar;
    }

    /** Current depth parameters (read only). */
    public DepthParams params() {
        return params;
    }

    /** Live accent colour of the current depth (read only; changes every frame). */
    public Color accent() {
        return params.accent;
    }

    /** Enables the automatic LOW fallback when frames are slow (default on). */
    public void setAutoFallback(boolean enabled) {
        autoFallback = enabled;
    }

    /** Callback run once when the backdrop falls back to LOW (persist + toast). */
    public void setOnAutoLow(Runnable r) {
        onAutoLow = r;
    }

    /**
     * Restarts the 120-frame performance probe (also done automatically when the backdrop starts
     * rendering into a different viewport, i.e. on a new screen). A failed probe still needs a
     * second consecutive failure before the fallback triggers.
     */
    public void resetProbe() {
        probe.rearm();
        if (detail == Detail.LOW || !shaderOk) probe.finish();
    }

    // ================================================================== update
    /** Advances animation. {@link #render} calls this itself; do not call both in one frame. */
    public void update(float delta) {
        if (delta <= 0f) return;
        time += delta;
        depth = Anim.damp(depth, targetDepth, 0.6f, delta);
        if (Math.abs(depth - targetDepth) < 0.05f) depth = targetDepth;
        DepthPalette.params(depth, params);
        sonar.update(delta);
        pulse = Math.max(0f, pulse - delta / 0.2f);
        shaftTilt = Anim.damp(shaftTilt, reduceMotion ? 0f : shaftTiltTarget, 0.15f, delta);
        if (Math.abs(shaftTilt) < 0.01f && (reduceMotion || shaftTiltTarget == 0f)) shaftTilt = 0f;
        virtX += virtVx * delta;
        virtY += virtVy * delta;
        updateParticles(delta);
        updateMotes(delta);
        updateTears();
    }

    private float motion() {
        return mode == Mode.GAMEPLAY ? Math.min(intensity, 0.6f) : intensity;
    }

    private float boxW() {
        return Math.max(1f, vw + 2f * MARGIN);
    }

    private float boxH() {
        return Math.max(1f, vh + 2f * MARGIN);
    }

    private void updateParticles(float dt) {
        float active = activeParticles();
        float m = motion();
        float bw = boxW(), bh = boxH();
        for (int i = 0; i < MAX_P; i++) {
            boolean on = i < active && (mode == Mode.MENU || pPlane[i] == 0);
            pFade[i] = Anim.damp(pFade[i], on ? 1f : 0f, 0.5f, dt);
            if (pFade[i] < 0.003f && !on) continue;
            boolean snow = pRoll[i] < params.snowMix;
            float vy;
            if (pRoll2[i] < params.pixelMix) vy = -pSpeed[i] * 0.25f;
            else if (snow) vy = -(6f + 8f * pSpeed[i] / 90f);
            else vy = pSpeed[i];
            pY[i] += vy * m * dt / bh;
            pX[i] += 4f * m * dt / bw * (pPhase[i] - 0.5f);
            if (pY[i] >= 1f) pY[i] -= 1f;
            if (pY[i] < 0f) pY[i] += 1f;
            if (pX[i] >= 1f) pX[i] -= 1f;
            if (pX[i] < 0f) pX[i] += 1f;
        }
    }

    private float activeParticles() {
        if (mode == Mode.GAMEPLAY) return 24f * particleScale * 3f; // only far-plane ones are shown (~1/3)
        return params.particulates * particleScale * (detail() == Detail.LOW ? 0.5f : 1f);
    }

    private void updateMotes(float dt) {
        float target;
        if (mode == Mode.GAMEPLAY) {
            target = combo < 10 ? 0f : Math.min(24f, 24f * (combo - 10) / 90f);
        } else {
            target = params.bioMotes * (detail() == Detail.LOW ? 0.5f : 1f);
        }
        moteDim = Math.min(1f, moteDim + dt / 0.5f);
        for (int i = 0; i < MAX_MOTES; i++) {
            boolean on = i < target;
            mFade[i] = Anim.damp(mFade[i], on ? 1f : 0f, 0.7f, dt);
            // spring home (ω 5, ζ .55) for scatter offsets
            float w = 5f, z = 0.55f;
            float ax = -w * w * mOx[i] - 2f * z * w * mVx[i];
            float ay = -w * w * mOy[i] - 2f * z * w * mVy[i];
            mVx[i] += ax * dt;
            mVy[i] += ay * dt;
            mOx[i] += mVx[i] * dt;
            mOy[i] += mVy[i] * dt;
            mFlare[i] = Math.max(0f, mFlare[i] - dt / 0.6f);
            if (mFade[i] > 0.05f && sonar.waveAt(moteX(i), moteY(i)) > 0.5f) mFlare[i] = 1f;
        }
    }

    private void scatterAll() {
        float cx = vx0 + vw * 0.5f, cy = vy0 + vh * 0.5f;
        for (int i = 0; i < MAX_MOTES; i++) {
            float dx = moteX(i) - cx, dy = moteY(i) - cy;
            float d = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
            mVx[i] += dx / d * 700f;
            mVy[i] += dy / d * 700f;
        }
        moteDim = 0f;
    }

    private void updateTears() {
        if (mode != Mode.MENU) return;
        if (time >= nextTear) {
            tearStart = time;
            tearSeed = (int) (time * 977f);
            tearDur = 0.10f + 0.06f * Anim.hash01(tearSeed);
            nextTear = time + params.idleGlitchSec * (0.8f + 0.4f * Anim.hash01(tearSeed, 5));
        }
    }

    private float moteX(int i) {
        float m = reduceMotion ? 0f : 1f;
        float wx = mAmp[i] * (MathUtils.sin(time * MathUtils.PI2 / mP1[i] + mPh1[i]) * 0.7f
            + MathUtils.sin(time * MathUtils.PI2 / (mP1[i] * 2.3f) + mPh2[i]) * 0.3f) * m;
        return vx0 + mHx[i] * vw + wx + mOx[i];
    }

    private float moteY(int i) {
        float m = reduceMotion ? 0f : 1f;
        float wy = mAmp[i] * 0.6f * (MathUtils.sin(time * MathUtils.PI2 / mP2[i] + mPh2[i]) * 0.7f
            + MathUtils.sin(time * MathUtils.PI2 / (mP2[i] * 1.7f) + mPh1[i]) * 0.3f) * m;
        return vy0 + mHy[i] * vh + wy + mOy[i];
    }

    // ================================================================== render
    /**
     * Updates (once per frame) and draws the backdrop over the whole visible world of
     * {@code viewport}.
     */
    public void render(Batch batch, Viewport viewport, float delta) {
        Camera cam = viewport.getCamera();
        float zoom = cam instanceof OrthographicCamera ? ((OrthographicCamera) cam).zoom : 1f;
        vw = viewport.getWorldWidth() * zoom;
        vh = viewport.getWorldHeight() * zoom;
        vx0 = cam.position.x - vw * 0.5f;
        vy0 = cam.position.y - vh * 0.5f;
        if (viewport != lastViewport) {
            lastViewport = viewport;
            resetProbe();
        }
        update(delta);
        runProbe();

        boolean began = false;
        if (!batch.isDrawing()) {
            batch.setProjectionMatrix(cam.combined);
            batch.begin();
            began = true;
        }
        float oldColor = batch.getPackedColor();
        batch.setColor(Color.WHITE);

        folded = false;
        if (detail() == Detail.HIGH) drawShader(batch);
        else drawLow(batch);
        if (!folded) drawImage(batch);
        if (mode == Mode.GAMEPLAY && dim > 0f) draw.rect(vx0, vy0, vw, vh, Theme.ABYSS_BLACK, dim);

        boolean additive = mode == Mode.MENU;
        if (additive) batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        drawParticles(batch);
        drawMotes(batch);
        sonar.draw(batch);
        drawTears(batch);
        if (additive) batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        batch.setPackedColor(oldColor);
        if (began) batch.end();
    }

    private float shaftStrength() {
        float s = params.shaftStrength * shaftScale;
        if (mode == Mode.GAMEPLAY) s = Math.min(0.35f, s * 0.35f);
        return s;
    }

    private void drawShader(Batch batch) {
        boolean caustics = causticsAllowed && mode == Mode.MENU && depth < CAUSTIC_MAX_DEPTH && params.caustic > 0.001f;
        boolean img = image != null && imageAlpha > 0.001f;
        boolean wsh = wash != null && washAlpha > 0.001f;
        folded = imageShaderOk && (img || wsh);
        ShaderProgram sp;
        int[] loc;
        if (folded) {
            sp = caustics ? shaderImageCaustics : shaderImage;
            loc = caustics ? locImgCau : locImg;
        } else {
            sp = caustics ? shaderCaustics : shader;
            loc = caustics ? locCau : locBase;
        }
        batch.setShader(sp);
        float pm = reduceMotion ? 0f : 0.5f;
        float m = motion();
        sp.setUniformf(loc[0], params.top.r, params.top.g, params.top.b);
        sp.setUniformf(loc[1], params.mid.r, params.mid.g, params.mid.b);
        sp.setUniformf(loc[2], params.bottom.r, params.bottom.g, params.bottom.b);
        sp.setUniformf(loc[3], params.shaft.r, params.shaft.g, params.shaft.b);
        sp.setUniformf(loc[4], shaftStrength());
        sp.setUniformf(loc[5], mode == Mode.GAMEPLAY ? 0.55f : params.vignette);
        sp.setUniformf(loc[6], mode == Mode.GAMEPLAY ? 0.03f : params.grain);
        sp.setUniformf(loc[7], pulse);
        sp.setUniformf(loc[8], mode == Mode.GAMEPLAY ? 0.70f : 1f);
        sp.setUniformf(loc[9], 1f / 128f, 1f / 128f);
        float t = time * (0.35f + 0.65f * m);
        sp.setUniformf(loc[10], frac(t * 0.012f));
        sp.setUniformf(loc[11], frac(t * 0.020f), frac(t * 0.010f));
        sp.setUniformf(loc[12], frac(t * 0.015f), frac(t * 0.012f));
        sp.setUniformf(loc[13], (parX + virtX) * pm % 10000f, (parY + virtY) * pm % 10000f);
        if (caustics) sp.setUniformf(loc[14], params.caustic);
        sp.setUniformf(loc[15], shaftSlant());
        if (folded) bindImageUniforms(sp, loc, img, wsh);
        batch.draw(draw.noise(), vx0, vy0, vw, vh, 0f, 0f, 1f, 1f);
        batch.setShader(null);
    }

    /**
     * Uniforms and texture units of the IMAGE variant: the image on unit 1 and the wash on unit 2
     * (the noise texture with α 0 stands in for an absent one), each with the transform from the
     * quad's 0..1 coordinates to its cover-cropped texture coordinates. Leaves unit 0 active for
     * the batch.
     */
    private void bindImageUniforms(ShaderProgram sp, int[] loc, boolean img, boolean wsh) {
        Texture noise = draw.noise();
        if (img) {
            imageRect();
            Theme.mix(Theme.WHITE, params.mid, 0.45f, tint);
            setCoverTransform(sp, loc[16]);
            sp.setUniformf(loc[18], tint.r, tint.g, tint.b, imageAlpha);
            image.bind(1);
        } else {
            sp.setUniformf(loc[16], 1f, 1f, 0f, 0f);
            sp.setUniformf(loc[18], 0f, 0f, 0f, 0f);
            noise.bind(1);
        }
        if (wsh) {
            washRect();
            setCoverTransform(sp, loc[17]);
            sp.setUniformf(loc[19], washAlpha);
            wash.bind(2);
        } else {
            sp.setUniformf(loc[17], 1f, 1f, 0f, 0f);
            sp.setUniformf(loc[19], 0f);
            noise.bind(2);
        }
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        sp.setUniformi(loc[20], 1);
        sp.setUniformi(loc[21], 2);
    }

    /**
     * Maps the quad's (0..1, bottom-up) coordinates to the texture coordinates of a texture drawn
     * upright over {@code rect*} (v = 1 at its bottom edge, as {@code Batch.draw(Texture, x, y, w, h)}).
     */
    private void setCoverTransform(ShaderProgram sp, int location) {
        sp.setUniformf(location, vw / rectW, -vh / rectH, (vx0 - rectX) / rectW, 1f - (vy0 - rectY) / rectH);
    }

    /**
     * Shaft slant term of the shader: the noise x-offset per unit of (1 − v), 0.35 at rest (the
     * shafts lean about 16° clockwise at 1080 tall); the tilt rotates that lean.
     */
    private float shaftSlant() {
        if (shaftTilt == 0f) return SHAFT_SLANT;
        float k = vh * SHAFT_SCALE;
        double lean = Math.atan(SHAFT_SLANT / k) - Math.toRadians(shaftTilt);
        return (float) (k * Math.tan(MathUtils.clamp(lean, -1.4, 1.4)));
    }

    private void drawLow(Batch batch) {
        float half = vh * 0.5f;
        draw.rectGradientV(vx0, vy0, vw, half + 1f, params.bottom, params.mid, 1f);
        draw.rectGradientV(vx0, vy0 + half, vw, half, params.mid, params.top, 1f);
        float s = shaftStrength();
        if (s > 0.01f) {
            TextureRegion r = draw.shaft();
            float m = reduceMotion ? 0f : motion();
            for (int k = 0; k < 4; k++) {
                float period = 9f + 1.7f * k;
                float ang = -18f + 6f * MathUtils.sin(time * MathUtils.PI2 / period + k * 1.3f) * m + shaftTilt;
                float sx = vx0 + vw * (0.15f + 0.24f * k) - parX * 0.25f % vw;
                float w = vw * (0.10f + 0.04f * (k % 2));
                float h = vh * 1.25f;
                draw.sprite(r, sx, vy0 + vh - h * 0.42f, w, h, ang, params.shaft, 0.10f * s * (k % 2 == 0 ? 1f : 0.7f));
            }
        }
        float vig = mode == Mode.GAMEPLAY ? 0.55f : params.vignette;
        draw.sprite(draw.vignette(), vx0 + vw * 0.5f, vy0 + vh * 0.5f, vw * 1.05f, vh * 1.1f,
            Theme.ABYSS_BLACK, vig * 1.1f);
    }

    /** Draws the image hook and the wash as their own quads (LOW, or no IMAGE shader variant). */
    private void drawImage(Batch batch) {
        if (image != null && imageAlpha > 0.001f) {
            imageRect();
            Theme.mix(Theme.WHITE, params.mid, 0.45f, tint);
            batch.setColor(tint.r, tint.g, tint.b, imageAlpha);
            batch.draw(image, rectX, rectY, rectW, rectH);
            batch.setColor(Color.WHITE);
        }
        if (wash != null && washAlpha > 0.001f) {
            washRect();
            batch.setColor(1f, 1f, 1f, washAlpha);
            batch.draw(wash, rectX, rectY, rectW, rectH);
            batch.setColor(Color.WHITE);
        }
    }

    /**
     * Rect of the image hook (into {@code rect*}): cover-cropped, Ken Burns 1 → 1.06 over 20 s,
     * 8 % overscan for parallax on menus, offset by the camera pan or the touch parallax, clamped
     * so the image always covers the view.
     */
    private void imageRect() {
        float kb = 1f + 0.06f * Anim.SINE.apply(pingPong(time / 20f));
        float extra = (imageParallax > 0f || mode == Mode.MENU) ? 1.08f : 1f;
        float scale = Math.max(vw / image.getWidth(), vh / image.getHeight()) * kb * extra;
        float w = image.getWidth() * scale, h = image.getHeight() * scale;
        float ox, oy;
        if (reduceMotion) {
            ox = oy = 0f;
        } else if (imageParallax > 0f) {
            ox = -parX * imageParallax;
            oy = -parY * imageParallax;
        } else {
            ox = -touchParX * vw * 0.03f;
            oy = -touchParY * vh * 0.03f;
        }
        float maxX = (w - vw) * 0.5f, maxY = (h - vh) * 0.5f;
        ox = MathUtils.clamp(ox, -maxX, maxX);
        oy = MathUtils.clamp(oy, -maxY, maxY);
        rectX = vx0 + (vw - w) * 0.5f + ox;
        rectY = vy0 + (vh - h) * 0.5f + oy;
        rectW = w;
        rectH = h;
    }

    /**
     * Rect of the wash (into {@code rect*}): cover-cropped and centred; with a framing, that part of the wash at 16:9
     * covers the view (the wash squeezes the whole jacket into 16 × 16, so its own aspect says nothing).
     */
    private void washRect() {
        if (washBox != null) {
            float s = Math.max(vw / 16f, vh / 9f);
            rectW = 16f * s / washBox[2];
            rectH = 9f * s / washBox[3];
            rectX = vx0 + vw * 0.5f - (washBox[0] + washBox[2] * 0.5f) * rectW;
            rectY = vy0 + vh * 0.5f - (1f - washBox[1] - washBox[3] * 0.5f) * rectH;
            return;
        }
        float scale = Math.max(vw / wash.getWidth(), vh / wash.getHeight());
        rectW = wash.getWidth() * scale;
        rectH = wash.getHeight() * scale;
        rectX = vx0 + (vw - rectW) * 0.5f;
        rectY = vy0 + (vh - rectH) * 0.5f;
    }

    private void drawParticles(Batch batch) {
        float bw = boxW(), bh = boxH();
        float m = reduceMotion ? 0f : 1f;
        float vyAll = camVy + virtVy;
        float vxAll = camVx + virtVx;
        float px = (parX + virtX) * m, py = (parY + virtY) * m;
        TextureRegion ring = draw.ringSoft(), dot = draw.softDot(), streak = draw.streak(), white = draw.white();
        boolean gameplay = mode == Mode.GAMEPLAY;
        for (int i = 0; i < MAX_P; i++) {
            float fade = pFade[i];
            if (fade < 0.01f) continue;
            int plane = pPlane[i];
            if (gameplay && plane != 0) continue;
            float pf = PLANE[plane];
            float wx = pX[i] * bw - px * pf;
            float wy = pY[i] * bh - py * pf;
            wx = wrap(wx, bw) + vx0 - MARGIN;
            wy = wrap(wy, bh) + vy0 - MARGIN;
            float planeAlpha = plane == 0 ? 0.45f : (plane == 1 ? 0.7f : 1f);
            float planeSize = plane == 0 ? 0.7f : (plane == 1 ? 0.85f : 1f);
            float sway = MathUtils.sin(time * MathUtils.PI2 / pPeriod[i] + pPhase[i] * 6.28f) * pSway[i] * motion() * m;
            // gameplay (§2.7): far-plane snow only (24 flakes), no bubbles or L6 pixels
            if (!gameplay && pRoll2[i] < params.pixelMix) {
                // L6: blinking squares
                float blink = MathUtils.sin(time * (1.5f + pPhase[i] * 3f) + pPhase[i] * 20f);
                if (blink < 0.2f) continue;
                Color c = (i & 1) == 0 ? params.mote : params.mote2;
                float s = 6f * planeSize;
                batch.setColor(c.r, c.g, c.b, 0.55f * planeAlpha * fade * blink);
                batch.draw(white, wx + sway - s * 0.5f, wy - s * 0.5f, s, s);
            } else if (gameplay || pRoll[i] < params.snowMix) {
                float a = (0.30f + 0.4f * pPhase[i]) * planeAlpha * fade;
                if (gameplay) a = Math.min(GAMEPLAY_SNOW_ALPHA, a);
                float size = pSize[i] * planeSize * 2.4f;
                float speed = Math.abs(vyAll) * pf;
                float stretch = reduceMotion ? 1f : Math.min(6f, 1f + speed * 0.004f);
                Color c = params.mote;
                Theme.mix(Theme.INK1, c, 0.35f, tmp);
                batch.setColor(tmp.r, tmp.g, tmp.b, a);
                if (stretch > 1.15f) {
                    float len = size * stretch;
                    float ang = MathUtils.atan2(-vyAll * pf, -vxAll * pf) * MathUtils.radiansToDegrees;
                    batch.draw(streak, wx + sway - len * 0.5f, wy - size * 0.25f, len * 0.5f, size * 0.25f,
                        len, size * 0.5f, 1f, 1f, ang);
                } else {
                    batch.draw(dot, wx + sway - size * 0.5f, wy - size * 0.5f, size, size);
                }
            } else {
                float a = (0.22f + 0.3f * pPhase[i]) * planeAlpha * fade;
                float size = pSize[i] * 2.33f * planeSize / 0.42f;
                float bob = MathUtils.sin(time * 3f + pPhase[i] * 9f) * 2f * m;
                batch.setColor(0.85f, 0.97f, 1f, a);
                batch.draw(ring, wx + sway * 0.4f + bob - size * 0.5f, wy - size * 0.5f, size, size);
            }
        }
        batch.setColor(Color.WHITE);
    }

    private void drawMotes(Batch batch) {
        TextureRegion dot = draw.softDot();
        for (int i = 0; i < MAX_MOTES; i++) {
            float f = mFade[i];
            if (f < 0.01f) continue;
            float x = moteX(i), y = moteY(i);
            float pulseA = 0.2f + 0.4f * (0.5f + 0.5f * MathUtils.sin(time * MathUtils.PI2 / mPulse[i] + mPh1[i]));
            float a = Math.max(pulseA, mFlare[i]) * f * moteDim * (mode == Mode.GAMEPLAY ? 0.7f : 1f);
            Color c = (i & 1) == 0 ? params.mote : params.mote2;
            float s = mSize[i] * (1f + 0.25f * mFlare[i]);
            batch.setColor(c.r, c.g, c.b, a * 0.55f);
            batch.draw(dot, x - s * 0.5f, y - s * 0.5f, s, s);
            float core = s * 0.28f;
            batch.setColor(c.r, c.g, c.b, a);
            batch.draw(dot, x - core * 0.5f, y - core * 0.5f, core, core);
        }
        batch.setColor(Color.WHITE);
    }

    private void drawTears(Batch batch) {
        if (mode != Mode.MENU || GlitchText.reduceFlashing()) return;
        float I = GlitchText.intensity();
        if (I <= 0.001f) return;
        float t = time - tearStart;
        if (t < 0f || t > tearDur) return;
        int tick = (int) (time * 24f);
        int bars = 1 + (int) (Anim.hash01(tearSeed, 1) * 1.999f);
        for (int k = 0; k < bars; k++) {
            float h = 2f + 6f * Anim.hash01(tearSeed + k, tick);
            float y = vy0 + vh * Anim.hash01(tearSeed, 10 + k);
            Color c = (k & 1) == 0 ? Theme.GLITCH_C : Theme.GLITCH_M;
            float a = (0.05f + 0.06f * Anim.hash01(tearSeed, 20 + k)) * I;
            draw.rect(vx0, y, vw, h, c, a);
        }
    }

    // ================================================================== probe
    /**
     * Automatic LOW fallback (DESIGN §2.4) through {@link FrameProbe}: clamped samples, a
     * median-style over-budget count and two consecutive slow windows, so a single hitch cannot
     * trigger the (persisted) downgrade.
     */
    private void runProbe() {
        if (!autoFallback || detail() == Detail.LOW || Gdx.graphics == null) return;
        if (probe.sample(Gdx.graphics.getDeltaTime()) == FrameProbe.Result.SLOW) {
            detail = Detail.LOW;
            if (onAutoLow != null) onAutoLow.run();
        }
    }

    // ================================================================== setup
    private void seedParticles() {
        for (int i = 0; i < MAX_P; i++) {
            pX[i] = Anim.hash01(i, 1);
            pY[i] = Anim.hash01(i, 2);
            pPlane[i] = i % 3;
            pSize[i] = 2f + 4f * Anim.hash01(i, 3);
            pSpeed[i] = 40f + 50f * Anim.hash01(i, 4);
            pPhase[i] = Anim.hash01(i, 5);
            pSway[i] = 8f * (0.5f + 0.5f * Anim.hash01(i, 6));
            pPeriod[i] = 4f + 5f * Anim.hash01(i, 7);
            pRoll[i] = Anim.hash01(i, 8);
            pRoll2[i] = Anim.hash01(i, 9);
            pFade[i] = 0f;
        }
    }

    private void seedMotes() {
        for (int i = 0; i < MAX_MOTES; i++) {
            mHx[i] = 0.06f + 0.88f * Anim.hash01(i, 11);
            mHy[i] = 0.08f + 0.84f * Anim.hash01(i, 12);
            mSize[i] = 24f + 40f * Anim.hash01(i, 13);
            mPh1[i] = Anim.hash01(i, 14) * 6.28f;
            mPh2[i] = Anim.hash01(i, 15) * 6.28f;
            mP1[i] = 9f + 8f * Anim.hash01(i, 16);
            mP2[i] = 7f + 9f * Anim.hash01(i, 17);
            mAmp[i] = 60f + 80f * Anim.hash01(i, 18);
            mPulse[i] = 3f + 3f * Anim.hash01(i, 19);
        }
    }

    private void compileShaders() {
        try {
            shader = new ShaderProgram(VERT, FRAG);
            shaderCaustics = new ShaderProgram("#define CAUSTICS\n" + VERT, "#define CAUSTICS\n" + FRAG);
            shaderOk = shader.isCompiled() && shaderCaustics.isCompiled();
            if (!shaderOk) {
                shaderLog = shader.getLog() + "\n" + shaderCaustics.getLog();
                if (Gdx.app != null) Gdx.app.error("Backdrop", "shader compile failed, using LOW: " + shaderLog);
            } else {
                fetch(shader, locBase);
                fetch(shaderCaustics, locCau);
                compileImageShaders();
            }
        } catch (RuntimeException e) {
            shaderOk = false;
            shaderLog = String.valueOf(e.getMessage());
        }
        if (!shaderOk) {
            detail = Detail.LOW;
            probe.finish();
        }
    }

    /** The IMAGE variants; on failure the image and wash keep their own draws. */
    private void compileImageShaders() {
        String img = "#define IMAGE\n", both = "#define IMAGE\n#define CAUSTICS\n";
        shaderImage = new ShaderProgram(img + VERT, img + FRAG);
        shaderImageCaustics = new ShaderProgram(both + VERT, both + FRAG);
        imageShaderOk = shaderImage.isCompiled() && shaderImageCaustics.isCompiled();
        if (imageShaderOk) {
            fetch(shaderImage, locImg);
            fetch(shaderImageCaustics, locImgCau);
        } else {
            shaderLog = shaderImage.getLog() + "\n" + shaderImageCaustics.getLog();
            if (Gdx.app != null) {
                Gdx.app.error("Backdrop", "IMAGE shader failed, image hook drawn separately: " + shaderLog);
            }
        }
    }

    /** Uniform names by location index (shared by all variants; missing ones fetch as −1). */
    private static final String[] UNIFORMS = {"u_top", "u_mid", "u_bot", "u_shaftCol", "u_shaft", "u_vig",
        "u_grain", "u_pulse", "u_bright", "u_fragToNoise", "u_shaftT", "u_cauA", "u_cauB", "u_offset", "u_caustic",
        "u_slant", "u_imgXf", "u_washXf", "u_imgTint", "u_washA", "u_image", "u_wash"};

    private static void fetch(ShaderProgram sp, int[] loc) {
        for (int i = 0; i < UNIFORMS.length; i++) loc[i] = sp.fetchUniformLocation(UNIFORMS[i], false);
    }

    @Override
    public void dispose() {
        if (shader != null) shader.dispose();
        if (shaderCaustics != null) shaderCaustics.dispose();
        if (shaderImage != null) shaderImage.dispose();
        if (shaderImageCaustics != null) shaderImageCaustics.dispose();
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }

    private static float wrap(float v, float m) {
        v %= m;
        return v < 0f ? v + m : v;
    }

    private static float pingPong(float t) {
        float f = t % 2f;
        return f > 1f ? 2f - f : f;
    }

    // ================================================================== GLSL (DESIGN §2.4)
    private static final String VERT = ""
        + "attribute vec4 a_position;\n"
        + "attribute vec2 a_texCoord0;\n"
        + "uniform mat4 u_projTrans;\n"
        + "uniform vec2 u_offset;\n"
        + "uniform float u_shaftT;\n"
        + "uniform vec2 u_cauA;\n"
        + "uniform vec2 u_cauB;\n"
        + "uniform float u_slant;\n"
        + "varying vec2 v_uv;\n"
        + "varying vec2 v_shaft;\n"
        + "#ifdef CAUSTICS\n"
        + "varying vec4 v_cau;\n"
        + "#endif\n"
        + "#ifdef IMAGE\n"
        + "uniform vec4 u_imgXf;\n"
        + "uniform vec4 u_washXf;\n"
        + "varying vec4 v_img;\n"
        + "#endif\n"
        + "void main() {\n"
        + "  v_uv = a_texCoord0;\n"
        + "  vec2 w = a_position.xy + u_offset;\n"
        + "  v_shaft = vec2(w.x * 0.0011 + (1.0 - a_texCoord0.y) * u_slant, u_shaftT);\n"
        + "#ifdef CAUSTICS\n"
        + "  v_cau = vec4(w * 0.0016 + u_cauA, w * vec2(0.0013, 0.0015) - u_cauB);\n"
        + "#endif\n"
        + "#ifdef IMAGE\n"
        + "  v_img = vec4(a_texCoord0 * u_imgXf.xy + u_imgXf.zw, a_texCoord0 * u_washXf.xy + u_washXf.zw);\n"
        + "#endif\n"
        + "  gl_Position = u_projTrans * a_position;\n"
        + "}\n";

    private static final String FRAG = ""
        + "#ifdef GL_ES\n"
        + "precision mediump float;\n"
        + "#endif\n"
        + "varying vec2 v_uv;\n"
        + "varying vec2 v_shaft;\n"
        + "uniform vec3 u_top;\n"
        + "uniform vec3 u_mid;\n"
        + "uniform vec3 u_bot;\n"
        + "uniform vec3 u_shaftCol;\n"
        + "uniform float u_shaft;\n"
        + "uniform float u_vig;\n"
        + "uniform float u_grain;\n"
        + "uniform float u_pulse;\n"
        + "uniform float u_bright;\n"
        + "uniform vec2 u_fragToNoise;\n"
        + "uniform sampler2D u_texture;\n"
        + "#ifdef CAUSTICS\n"
        + "varying vec4 v_cau;\n"
        + "uniform float u_caustic;\n"
        + "#endif\n"
        + "#ifdef IMAGE\n"
        + "varying vec4 v_img;\n"
        + "uniform sampler2D u_image;\n"
        + "uniform sampler2D u_wash;\n"
        + "uniform vec4 u_imgTint;\n"
        + "uniform float u_washA;\n"
        + "#endif\n"
        + "void main() {\n"
        + "  float y = v_uv.y;\n"
        + "  vec3 c = mix(mix(u_bot, u_mid, clamp(y * 2.0, 0.0, 1.0)), u_top, clamp(y * 2.0 - 1.0, 0.0, 1.0));\n"
        + "  vec2 n = texture2D(u_texture, v_shaft).rg;\n"
        + "  float s = n.r * 0.65 + n.g * 0.35;\n"
        + "  c += u_shaftCol * (s * s * s) * u_shaft * smoothstep(0.15, 1.0, y);\n"
        + "#ifdef CAUSTICS\n"
        + "  float a = texture2D(u_texture, v_cau.xy).b;\n"
        + "  float b = texture2D(u_texture, v_cau.zw).b;\n"
        + "  c += u_shaftCol * u_caustic * pow(1.0 - abs(a - b), 8.0) * y * 0.30;\n"
        + "#else\n"
        + "  c += (texture2D(u_texture, gl_FragCoord.xy * u_fragToNoise).a - 0.5) * u_grain;\n"
        + "#endif\n"
        + "  vec2 d = v_uv - 0.5;\n"
        + "  c *= (1.0 - u_vig * dot(d, d) * 2.0) * u_bright * (1.0 + u_pulse * 0.03);\n"
        + "#ifdef IMAGE\n"
        + "  vec4 im = texture2D(u_image, v_img.xy);\n"
        + "  c = mix(c, im.rgb * u_imgTint.rgb, im.a * u_imgTint.a);\n"
        + "  vec4 wa = texture2D(u_wash, v_img.zw);\n"
        + "  c = mix(c, wa.rgb, wa.a * u_washA);\n"
        + "#endif\n"
        + "  gl_FragColor = vec4(c, 1.0);\n"
        + "}\n";
}
