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

import java.time.Duration;

/**
 * Thrown by {@link IteratingAnalyzer#analyze} when the run outlives
 * {@link IteratingAnalyzer.Configuration#maxDuration()}: the analysis REFUSES with the counts it reached, instead of
 * running until it is killed.
 * <p>
 * The budget is checked before every element of every pass, so a single pass that never
 * finishes (the OpenSearch {@code G43} run: 1 h 40 min inside the first pass) is refused too. An element already
 * running is not interrupted: the refusal comes when it completes, so the overshoot is one element's cost.
 * <p>
 * ⚠ The analysis values on the CST are PARTIAL after this exception: some elements were analysed in the refused
 * pass and some were not. A caller that wants to try again must first clear what the run wrote. Fault tolerance
 * does not apply: this is not an element's crash, and it is never recorded as a finding and swallowed.
 */
public class AnalysisBudgetExceededException extends RuntimeException {
    private final Duration budget;
    private final Duration elapsed;
    private final int iteration;
    private final int elementsDone;
    private final int elementsInPass;
    private final int analysisOrderSize;

    /**
     * @param iteration      the pass that was refused (1-based); when {@code elementsDone} is 0 it had not started
     * @param elementsDone   the elements of that pass processed before the refusal
     * @param elementsInPass the elements that pass was to process (the worklist subset)
     */
    public AnalysisBudgetExceededException(Duration budget, Duration elapsed, int iteration, int elementsDone,
                                           int elementsInPass, int analysisOrderSize) {
        super(message(budget, elapsed, iteration, elementsDone, elementsInPass, analysisOrderSize));
        this.budget = budget;
        this.elapsed = elapsed;
        this.iteration = iteration;
        this.elementsDone = elementsDone;
        this.elementsInPass = elementsInPass;
        this.analysisOrderSize = analysisOrderSize;
    }

    private static String message(Duration budget, Duration elapsed, int iteration, int elementsDone,
                                  int elementsInPass, int analysisOrderSize) {
        String where = elementsDone == 0
                ? "before iteration " + iteration + " started (" + elementsInPass + " element(s) to do)"
                : "in iteration " + iteration + " after " + elementsDone + " of " + elementsInPass
                  + " element(s) of that pass";
        return "The modification analysis exceeded its time budget of " + format(budget) + " (elapsed "
               + format(elapsed) + ") " + where + "; the analysis order has " + analysisOrderSize
               + " element(s). The run was refused, not completed: its values are partial.";
    }

    private static String format(Duration d) {
        long minutes = d.toMinutes();
        return minutes > 0 ? minutes + " min " + d.toSecondsPart() + " s"
                : d.toSecondsPart() + "." + String.format("%03d", d.toMillisPart()) + " s";
    }

    public Duration budget() {
        return budget;
    }

    public Duration elapsed() {
        return elapsed;
    }

    public int iteration() {
        return iteration;
    }

    public int elementsDone() {
        return elementsDone;
    }

    public int elementsInPass() {
        return elementsInPass;
    }

    public int analysisOrderSize() {
        return analysisOrderSize;
    }
}
