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

import io.codelaser.maddi.analysis.api.AnalysisValueFeed;

import io.codelaser.maddi.cst.api.info.Info;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * The analyzer takes a single {@link AnalysisValueFeed} ({@code setValueFeed}); a long run wants two at
 * once — the {@link CheckpointWriter} (durability) and the {@link AnalysisProgressFeed} (observability).
 * This fans each event out to every delegate, guarding each call so one misbehaving feed cannot silence
 * the others: a checkpoint IO failure must not stop the progress heartbeat, and vice versa.
 */
public class CompositeValueFeed implements AnalysisValueFeed {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompositeValueFeed.class);

    private final List<AnalysisValueFeed> delegates;

    public CompositeValueFeed(AnalysisValueFeed... delegates) {
        this.delegates = List.of(delegates);
    }

    @Override
    public void passCompleted(int iteration, boolean fullPass, Collection<Info> analyzed) {
        for (AnalysisValueFeed d : delegates) {
            try {
                d.passCompleted(iteration, fullPass, analyzed);
            } catch (RuntimeException | AssertionError | StackOverflowError e) {
                LOGGER.warn("value feed {} threw on passCompleted; ignoring", d.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void waveCompleted(int iteration, int wave, Collection<Info> analyzed) {
        for (AnalysisValueFeed d : delegates) {
            try {
                d.waveCompleted(iteration, wave, analyzed);
            } catch (RuntimeException | AssertionError | StackOverflowError e) {
                LOGGER.warn("value feed {} threw on waveCompleted; ignoring", d.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void elementCompleted() {
        // per-element (hot): most delegates leave this a no-op; only the progress feed ticks a counter
        for (AnalysisValueFeed d : delegates) {
            try {
                d.elementCompleted();
            } catch (RuntimeException | AssertionError | StackOverflowError e) {
                LOGGER.warn("value feed {} threw on elementCompleted; ignoring", d.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void phase(Phase phase, int iteration) {
        for (AnalysisValueFeed d : delegates) {
            try {
                d.phase(phase, iteration);
            } catch (RuntimeException | AssertionError | StackOverflowError e) {
                LOGGER.warn("value feed {} threw on phase; ignoring", d.getClass().getSimpleName(), e);
            }
        }
    }
}
