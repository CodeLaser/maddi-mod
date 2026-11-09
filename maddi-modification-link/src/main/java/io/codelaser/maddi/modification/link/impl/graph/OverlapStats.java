package io.codelaser.maddi.modification.link.impl.graph;

import java.util.concurrent.atomic.AtomicLong;

/*
CodeLaser/maddi-mod#92 diagnostic (env OVLSTATS=1): where the implicit '∩' costs time. Totals printed at JVM exit.
 */
public final class OverlapStats {
    public static final boolean ON = System.getenv("OVLSTATS") != null;

    private static final AtomicLong builds = new AtomicLong();
    private static final AtomicLong buildNanos = new AtomicLong();
    private static final AtomicLong buildVertices = new AtomicLong();
    private static final AtomicLong maxBuildNanos = new AtomicLong();
    private static final AtomicLong freezes = new AtomicLong();
    private static final AtomicLong freezeNanos = new AtomicLong();
    private static final AtomicLong reads = new AtomicLong();
    private static final AtomicLong cachedReads = new AtomicLong();

    // per thread: the nanos spent in builds and freezes, for the per-method line (LinkComputerImpl.doMethod)
    private static final ThreadLocal<long[]> threadNanos = ThreadLocal.withInitial(() -> new long[1]);

    public static long threadNanos() {
        return threadNanos.get()[0];
    }

    private static final AtomicLong freezeStale = new AtomicLong();
    private static final AtomicLong freezeAffected = new AtomicLong();
    private static final AtomicLong freezeInFrozen = new AtomicLong();
    private static final AtomicLong freezeSeeds = new AtomicLong();
    private static final AtomicLong freezeNewGroups = new AtomicLong();
    private static final AtomicLong growthPasses = new AtomicLong();
    private static final AtomicLong creationNanos = new AtomicLong();
    private static final AtomicLong growthNanos = new AtomicLong();

    static void freezeShape(boolean stale, boolean affected, boolean inFrozen, int seeds) {
        if (stale) freezeStale.incrementAndGet();
        if (affected) freezeAffected.incrementAndGet();
        if (inFrozen) freezeInFrozen.incrementAndGet();
        freezeSeeds.addAndGet(seeds);
    }

    static void freezeNewGroups() {
        freezeNewGroups.incrementAndGet();
    }

    static void buildPhases(long creation, long growth, int passes) {
        creationNanos.addAndGet(creation);
        growthNanos.addAndGet(growth);
        growthPasses.addAndGet(passes);
    }

    private static void print() {
        System.out.println("OVLSTATS builds=" + builds + " buildMs=" + buildNanos.get() / 1_000_000
                           + " avgVertices=" + (builds.get() == 0 ? 0 : buildVertices.get() / builds.get())
                           + " maxBuildMs=" + maxBuildNanos.get() / 1_000_000
                           + " freezes=" + freezes + " freezeMs=" + freezeNanos.get() / 1_000_000
                           + " reads=" + reads + " cachedReads=" + cachedReads
                           + " freezeStale=" + freezeStale + " freezeAffected=" + freezeAffected
                           + " freezeInFrozen=" + freezeInFrozen + " freezeSeeds=" + freezeSeeds
                           + " freezeNewGroups=" + freezeNewGroups + " creationMs=" + creationNanos.get() / 1_000_000
                           + " growthMs=" + growthNanos.get() / 1_000_000 + " growthPasses=" + growthPasses);
    }

    private OverlapStats() {
    }

    static void build(long nanos, int vertices) {
        builds.incrementAndGet();
        buildNanos.addAndGet(nanos);
        threadNanos.get()[0] += nanos;
        buildVertices.addAndGet(vertices);
        maxBuildNanos.accumulateAndGet(nanos, Math::max);
        if (nanos > 200_000_000L) System.out.println("OVLSLOW build " + nanos / 1_000_000 + "ms vertices=" + vertices);
        if (builds.get() % 20_000 == 0) print();
    }

    static void freeze(long nanos) {
        freezes.incrementAndGet();
        freezeNanos.addAndGet(nanos);
        threadNanos.get()[0] += nanos;
        if (nanos > 200_000_000L) System.out.println("OVLSLOW freeze " + nanos / 1_000_000 + "ms");
        if (freezes.get() % 20_000 == 0) print();
    }

    static void read(boolean cached) {
        reads.incrementAndGet();
        if (cached) cachedReads.incrementAndGet();
    }
}
