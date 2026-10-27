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


package io.codelaser.maddi.modification.analyzer.nullability;

import java.util.Set;

/**
 * The nullability pass's A/B gates ({@code NOVIEWLOOPS}, {@code NOGAP9}, …). A gate is off when its environment
 * variable is set, or while a caller has switched it off ({@link #offForNextPasses}): one JVM can then run several
 * passes over one analysis, each with other gates off. The analysis is the expensive part of a corpus run, the pass a
 * small one, so an A/B of n gates costs one analysis and n + 1 passes instead of n + 1 analyses.
 */
public final class Switches {
    private static volatile Set<String> off = Set.of();

    private Switches() {
    }

    public static Gate gate(String name) {
        return new Gate(name, System.getenv(name) != null);
    }

    /** The gates switched off, besides the environment's, for the passes run from now on. */
    public static void offForNextPasses(Set<String> names) {
        off = Set.copyOf(names);
    }

    public record Gate(String name, boolean inEnvironment) {
        /** Whether the gate is off: the rule it guards does not apply. */
        public boolean get() {
            return inEnvironment || off.contains(name);
        }
    }
}
