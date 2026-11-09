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

package io.codelaser.maddi.run.openjdkmain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;

/**
 * The incremental analysis must keep up with the cold one: the guard against an analyzer change that makes a warm
 * run settle elsewhere than a cold run does. On fernflower, the rewire driver with the modification analysis seeded
 * after every re-parse ({@code maddi.rewire.analysis}): seven edits (the driver's fixed seed picks the types), each
 * one's seeded analysis and its restore's must stay incremental, without falling back to the cold analysis
 * ({@code maddi.rewire.requireIncremental}), and after the last restore the warm state must equal a cold parse, prep
 * and analysis of the same files (the oracle).
 * <p>
 * Measured 2026-11-07 (maddi-mod fca7d9f6, cycle breaking no longer optimistic about undecided values): all seven
 * incremental, 0.15 to 8 s each, against 350 to 500 s for the cold analysis. Before, two of the seven fell back: cycle
 * breaking wrote optimistic values the warm run, with its decided neighbours, did not.
 * <p>
 * A failure is either a fall-back ({@code REWIRE STATS ... (fell back to cold)}; the {@code Early cutoff: ... moved}
 * lines before it name the element and its prior and current values) or an oracle difference
 * ({@code REWIRE ORACLE DIFFERENCE}). Costs about two cold analyses of fernflower.
 */
@Tag("slow")
public class TestIncrementalFernflower {

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
    }

    @Test
    public void test(@TempDir Path tmp) throws Exception {
        int exitValue = TestRewireFernflower.rewire(tmp, Map.of(
                "maddi.rewire.iterations", "7",
                "maddi.rewire.oracleEvery", "7",
                "maddi.rewire.analysis", "true",
                "maddi.rewire.requireIncremental", "true"));
        assertEquals(Main.EXIT_OK, exitValue, "a seeded analysis fell back to the cold analysis, or the oracle found"
                                              + " a difference with a cold run: see the REWIRE lines of the log");
    }
}
