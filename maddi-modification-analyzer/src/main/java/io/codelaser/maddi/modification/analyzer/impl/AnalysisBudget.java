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

package io.codelaser.maddi.modification.analyzer.impl;

import io.codelaser.maddi.modification.analyzer.AnalysisBudgetExceededException;

import java.time.Duration;

/**
 * The wall-clock deadline of one {@code IteratingAnalyzer.analyze} call (gap {@code G43}). The coordinator names
 * each pass before it starts; the workers check it per element, so {@link #check} must stay cheap and thread-safe.
 */
final class AnalysisBudget {
    private final Duration budget;
    private final long budgetNanos;
    private final long startNanos;
    private final int analysisOrderSize;
    private volatile int iteration;
    private volatile int elementsInPass;

    private AnalysisBudget(Duration budget, int analysisOrderSize) {
        this.budget = budget;
        this.budgetNanos = budget.toNanos();
        this.startNanos = System.nanoTime();
        this.analysisOrderSize = analysisOrderSize;
    }

    /** @return the running budget, or {@code null} when {@code budget} is null (unlimited) */
    static AnalysisBudget start(Duration budget, int analysisOrderSize) {
        return budget == null ? null : new AnalysisBudget(budget, analysisOrderSize);
    }

    /** Name the pass about to run, for the refusal's message. Its first element's check refuses a spent budget. */
    void startPass(int iteration, int elementsInPass) {
        this.iteration = iteration;
        this.elementsInPass = elementsInPass;
    }

    /** @throws AnalysisBudgetExceededException when the budget is spent; {@code elementsDone} is of the current pass */
    void check(int elementsDone) {
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed > budgetNanos) {
            throw new AnalysisBudgetExceededException(budget, Duration.ofNanos(elapsed), iteration, elementsDone,
                    elementsInPass, analysisOrderSize);
        }
    }
}
