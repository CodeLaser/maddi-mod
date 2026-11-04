/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.codelaser.maddi.modification.analyzer;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;

public interface SingleIterationAnalyzer {

    default void go(List<Info> analysisOrder, boolean activateCycleBreaking) {
        go(analysisOrder, activateCycleBreaking, true);
    }

    void go(List<Info> analysisOrder, boolean activateCycleBreaking, boolean firstIteration);

    /**
     * Like the 3-arg {@code go}, additionally providing dependency waves for a strata-parallel FIRST
     * iteration (see ComputeAnalysisOrder.waves): each wave's units run concurrently, units are sequential
     * inside, waves are barriers. Ignored (delegates) by default or when null.
     */
    default void go(List<Info> analysisOrder, boolean activateCycleBreaking, boolean firstIteration,
                    List<List<List<Info>>> firstIterationWaves) {
        go(analysisOrder, activateCycleBreaking, firstIteration);
    }

    /**
     * A seeded (incremental) run: the elements the next {@code go} analyses for the first time in this run. Their
     * cross-type-derived values were just cleared, so the work a cold run does only in its first iteration (the
     * shallow analysis of an abstract type's methods and fields, an abstract method without implementations) is
     * done for them too, whatever the pass. Holds for the next {@code go} only.
     */
    default void setFirstVisits(Set<Info> firstVisits) {
    }

    int propertiesChanged();

    /**
     * Summary-consumption edges discovered by the link computer (consumer read consumed's METHOD_LINKS
     * while computing its own links) — see LinkComputer.recordSummaryConsumption. Accumulated over the run.
     */
    default Map<MethodInfo,
            Set<MethodInfo>> consumedSummaries() {
        return Map.of();
    }

    /**
     * Worklist support: the elements whose analysis values changed during the most recent {@code go} call --
     * per-element counter-delta attribution united with the write-target attribution from TolerantWrite (which
     * also catches the link computer's on-demand recursion writing a CALLEE's summary mid-caller).
     */
    Set<Info> changedInfos();

    /**
     * Worklist v2: only elements whose EXTERNALLY VISIBLE summary changed (method links/modification verdicts,
     * field/type verdicts) — the set whose dependents must be re-analyzed. Element-internal (statement-level)
     * changes are excluded: dependents cannot observe them.
     */
    Set<Info> summaryChangedInfos();

    /** Findings (warnings/errors about the analyzed code) collected by all analyzers of this iteration. */
    List<Message> messages();
}
