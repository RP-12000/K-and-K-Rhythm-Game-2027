package org.kelvinizer.play;

/**
 * 一局获得的贝壳明细（不可变）：基础贝壳，加上首次游玩、新纪录、首次 FULL COMBO / ALL RESONANCE 和每日首局等奖励。
 * 首次完成新手教程固定得 150 贝壳；结算画面按项列出这些贝壳，{@link #total()} 是实际获得的总数。
 *
 * <p>Itemised shells of one play (the former EXP, DESIGN §11.6). Immutable; {@link #total()} is
 * the number of shells granted.
 */
public final class ShellBreakdown {
    /** A breakdown that grants nothing (autoplay, quit, repeat tutorial). */
    public static final ShellBreakdown NONE = new ShellBreakdown(0, 0, 0, 0, 0, 0, 0);

    /** Base shells from constant, score and chart length. */
    public final int base;
    /** +40 for the first play of this chart and difficulty. */
    public final int firstPlay;
    /** +20 for a new best (+40 when the grade improved); never on a first play. */
    public final int newBest;
    /** +50 for the first FULL COMBO on this chart. */
    public final int fullCombo;
    /** +100 for the first ALL RESONANCE (PURE) on this chart. */
    public final int allResonance;
    /** +50 for the first completed play of the local day. */
    public final int daily;
    /** Flat 150 for the first tutorial completion (no base). */
    public final int tutorial;

    /** Creates a breakdown from its items. */
    public ShellBreakdown(int base, int firstPlay, int newBest, int fullCombo, int allResonance,
                        int daily, int tutorial) {
        this.base = base;
        this.firstPlay = firstPlay;
        this.newBest = newBest;
        this.fullCombo = fullCombo;
        this.allResonance = allResonance;
        this.daily = daily;
        this.tutorial = tutorial;
    }

    /** @return the shells granted (sum of every item). */
    public int total() {
        return base + firstPlay + newBest + fullCombo + allResonance + daily + tutorial;
    }

    @Override
    public String toString() {
        return "Shells{base=" + base + " first=" + firstPlay + " best=" + newBest + " fc=" + fullCombo
            + " ar=" + allResonance + " daily=" + daily + " tutorial=" + tutorial + " total=" + total() + "}";
    }
}
