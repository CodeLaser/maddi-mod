/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
