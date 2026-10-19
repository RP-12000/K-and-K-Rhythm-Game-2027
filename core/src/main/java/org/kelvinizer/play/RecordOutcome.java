package org.kelvinizer.play;

import org.kelvinizer.profile.Depth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 记录一局成绩后发生的变化（不可变）：最高分、评级、这张谱和潜水员的深度、贝壳的前后值，以及变深后解锁的头像外观。
 * 由 {@code ProfileStore.recordPlay} 返回，结算画面用这些前后值做动画。
 *
 * <p>What recording a {@link PlayResult} changed, returned by {@code ProfileStore.recordPlay}
 * (ARCHITECTURE §5, DESIGN Appendix A.10). Immutable; the result screen only animates
 * the before/after pairs.
 *
 * <p>For autoplay {@link #recorded()} is false and every before/after pair is equal. For
 * the tutorial no chart record exists: {@link #grade()} is null and only the shells change.
 */
public final class RecordOutcome {
    private final boolean recorded;
    private final boolean tutorial;
    private final int previousBest;
    private final boolean newBest;
    private final boolean firstPlay;
    private final String grade;
    private final String previousGrade;
    private final boolean gradeImproved;
    private final float playDepth;
    private final float bestDepth;
    private final int depthBefore;
    private final int depthAfter;
    private final int topTenIndex;
    private final ShellBreakdown shellBreakdown;
    private final long shellsBefore;
    private final long shellsAfter;
    private final List<String> unlocks;
    private final int localRank;
    private final int localCount;

    private RecordOutcome(Builder b) {
        recorded = b.recorded;
        tutorial = b.tutorial;
        previousBest = b.previousBest;
        newBest = b.newBest;
        firstPlay = b.firstPlay;
        grade = b.grade;
        previousGrade = b.previousGrade;
        gradeImproved = b.gradeImproved;
        playDepth = b.playDepth;
        bestDepth = b.bestDepth;
        depthBefore = b.depthBefore;
        depthAfter = b.depthAfter;
        topTenIndex = b.topTenIndex;
        shellBreakdown = b.shellBreakdown != null ? b.shellBreakdown : ShellBreakdown.NONE;
        shellsBefore = b.shellsBefore;
        shellsAfter = b.shellsAfter;
        unlocks = b.unlocks.isEmpty() ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(b.unlocks));
        localRank = b.localRank;
        localCount = b.localCount;
    }

    /** @return a new builder (used by ProfileStore). */
    public static Builder builder() {
        return new Builder();
    }

    /** @return false for autoplay (nothing was saved). */
    public boolean recorded() { return recorded; }
    /** @return true for the tutorial (no chart record, shells only). */
    public boolean tutorial() { return tutorial; }
    /** @return best score before this play (0 when none). */
    public int previousBest() { return previousBest; }
    /** @return true when this play beat a previous best (never on a first play). */
    public boolean isNewBest() { return newBest; }
    /** @return true when this was the first play of the chart + difficulty ("FIRST DIVE"). */
    public boolean isFirstPlay() { return firstPlay; }
    /** @return grade of this play ("R+".."F"), or null for the tutorial. */
    public String grade() { return grade; }
    /** @return best grade before this play, or null when none. */
    public String previousGrade() { return previousGrade; }
    /** @return true when this play improved the best grade (not on a first play). */
    public boolean gradeImproved() { return gradeImproved; }
    /** @return chart depth of this play in metres (unrounded). */
    public float playDepth() { return playDepth; }
    /** @return chart depth of the chart's best after this play in metres (unrounded). */
    public float bestDepth() { return bestDepth; }
    /** @return player depth in whole metres before. */
    public int depthBefore() { return depthBefore; }
    /** @return player depth in whole metres after. */
    public int depthAfter() { return depthAfter; }
    /** @return depth gained in metres (≥ 0 in practice). */
    public int depthDelta() { return depthAfter - depthBefore; }
    /** @return index 0..9 of this chart in the player's top 10 after the play, or −1. */
    public int topTenIndex() { return topTenIndex; }
    /** @return shells granted by this play. */
    public int shellsGained() { return shellBreakdown.total(); }
    /** @return itemised shells. */
    public ShellBreakdown shellBreakdown() { return shellBreakdown; }
    /** @return shells before. */
    public long shellsBefore() { return shellsBefore; }
    /** @return shells after. */
    public long shellsAfter() { return shellsAfter; }
    /** @return labels of avatar options unlocked by the new depth (unmodifiable, may be empty). */
    public List<String> unlocks() { return unlocks; }
    /** @return this profile's rank among local profiles on this chart (1-based, 0 = none). */
    public int localRank() { return localRank; }
    /** @return number of local profiles with a record on this chart. */
    public int localCount() { return localCount; }

    @Override
    public String toString() {
        return "RecordOutcome{recorded=" + recorded + " grade=" + grade + " prevBest=" + previousBest
            + " newBest=" + newBest + " first=" + firstPlay + " chartDepth " + playDepth + "/" + bestDepth
            + " depth " + depthBefore + "->" + depthAfter + " top10=" + topTenIndex + " " + shellBreakdown
            + " shells " + shellsBefore + "->" + shellsAfter + " unlocks=" + unlocks
            + " local " + localRank + "/" + localCount + "}";
    }

    /** Builder for {@link RecordOutcome}. */
    public static final class Builder {
        private boolean recorded;
        private boolean tutorial;
        private int previousBest;
        private boolean newBest;
        private boolean firstPlay;
        private String grade;
        private String previousGrade;
        private boolean gradeImproved;
        private float playDepth;
        private float bestDepth;
        private int depthBefore;
        private int depthAfter;
        private int topTenIndex = -1;
        private ShellBreakdown shellBreakdown;
        private long shellsBefore;
        private long shellsAfter;
        private final List<String> unlocks = new ArrayList<>();
        private int localRank;
        private int localCount;

        private Builder() {
        }

        /** Sets whether the run changed the profile (false for autoplay). */
        public Builder recorded(boolean v) { recorded = v; return this; }
        /** Marks a tutorial outcome. */
        public Builder tutorial(boolean v) { tutorial = v; return this; }
        /** Sets the previous best score and the NEW BEST / FIRST DIVE flags. */
        public Builder best(int previousBest, boolean newBest, boolean firstPlay) {
            this.previousBest = previousBest;
            this.newBest = newBest;
            this.firstPlay = firstPlay;
            return this;
        }
        /** Sets this run's grade, the previous best grade and whether the best grade improved. */
        public Builder grade(String grade, String previousGrade, boolean improved) {
            this.grade = grade;
            this.previousGrade = previousGrade;
            this.gradeImproved = improved;
            return this;
        }
        /** Sets the chart depth of this run and of the chart's best after recording. */
        public Builder chartDepth(float playDepth, float bestDepth) {
            this.playDepth = playDepth;
            this.bestDepth = bestDepth;
            return this;
        }
        /** Sets the player depth before/after (unrounded metres; stored as displayed). */
        public Builder depth(float before, float after) {
            depthBefore = Depth.meters(before);
            depthAfter = Depth.meters(after);
            return this;
        }
        /** Sets the chart's index in the best-10 list after the run (−1 = not in it). */
        public Builder topTenIndex(int v) { topTenIndex = v; return this; }
        /** Sets the shell breakdown and the totals before and after the run. */
        public Builder shells(ShellBreakdown breakdown, long before, long after) {
            this.shellBreakdown = breakdown;
            this.shellsBefore = before;
            this.shellsAfter = after;
            return this;
        }
        /** Adds an avatar option unlocked by the new depth. */
        public Builder addUnlock(String label) { unlocks.add(label); return this; }
        /** @return the mutable unlock list (filled by {@code Depth.unlocksBetween}). */
        public List<String> unlockList() { return unlocks; }
        /** Sets the local ranking among profiles on this device (rank 0 = none). */
        public Builder local(int rank, int count) { localRank = rank; localCount = count; return this; }

        /** @return the immutable outcome. */
        public RecordOutcome build() {
            return new RecordOutcome(this);
        }
    }
}
