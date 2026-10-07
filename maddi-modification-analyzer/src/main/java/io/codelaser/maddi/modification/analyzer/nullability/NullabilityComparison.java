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

import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;

import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scores a nullability verdict against a reference, position by position: the type itself and, recursively, each
 * type argument (the value shape of {@code docs/design/nullability.md} §4.2 B2). The reference is what the source
 * declares (the oracle, §6); the verdict is what the analysis, or a baseline, says.
 * <p>
 * The two error kinds are kept apart because they cost different things (§6):
 * <ul>
 *     <li>{@link Outcome#UNSAFE}: the verdict says non-null where the reference says nullable. Annotating or
 *     translating it so introduces a null-pointer bug, or a compile error the user must resolve.</li>
 *     <li>{@link Outcome#NOISE}: the verdict says nullable where the reference says non-null. It costs an extra
 *     {@code @Nullable}, {@code ?} or {@code !!}.</li>
 * </ul>
 * A position the reference leaves {@link NullableState#UNSPECIFIED} cannot be scored; a position the verdict leaves
 * unspecified is counted as {@link Outcome#UNDECIDED}. Primitive positions are skipped: they are non-null by
 * construction and would inflate the agreement.
 */
public final class NullabilityComparison {

    public enum Kind {FIELD, PARAMETER, RETURN}

    /** TOP: the declaration's own type; ARGUMENT: a type argument, at any depth; ELEMENT: an array's elements. */
    public enum Depth {TOP, ARGUMENT, ELEMENT}

    public enum Outcome {AGREE, UNSAFE, NOISE, UNDECIDED, UNSCORED}

    private final Map<Kind, Map<Depth, Map<Outcome, Integer>>> counts = new EnumMap<>(Kind.class);

    /**
     * Compare one declaration. The two types must be the same declared type, differing only in their
     * {@link NullableState}s; a shape mismatch (a different number of type arguments) is scored down to the common
     * prefix.
     */
    public void add(Kind kind, ParameterizedType reference, ParameterizedType verdict) {
        add(kind, Depth.TOP, reference, verdict);
    }

    private void add(Kind kind, Depth depth, ParameterizedType reference, ParameterizedType verdict) {
        if (!(reference.isPrimitiveExcludingVoid() && reference.arrays() == 0)) {
            increment(kind, depth, outcome(reference.nullable(), verdict.nullable()));
        }
        if (reference.arrays() > 0 && verdict.arrays() == reference.arrays()) {
            add(kind, Depth.ELEMENT, reference.componentType(), verdict.componentType());
            return; // the type arguments are the element's
        }
        int n = Math.min(reference.parameters().size(), verdict.parameters().size());
        for (int i = 0; i < n; i++) {
            add(kind, Depth.ARGUMENT, reference.parameters().get(i), verdict.parameters().get(i));
        }
    }

    static Outcome outcome(NullableState reference, NullableState verdict) {
        if (reference == NullableState.UNSPECIFIED) return Outcome.UNSCORED;
        if (verdict == NullableState.UNSPECIFIED) return Outcome.UNDECIDED;
        if (reference == verdict) return Outcome.AGREE;
        return verdict == NullableState.NONNULL ? Outcome.UNSAFE : Outcome.NOISE;
    }

    private void increment(Kind kind, Depth depth, Outcome outcome) {
        counts.computeIfAbsent(kind, _ -> new EnumMap<>(Depth.class))
                .computeIfAbsent(depth, _ -> new EnumMap<>(Outcome.class))
                .merge(outcome, 1, Integer::sum);
    }

    public int count(Outcome outcome) {
        return counts.values().stream().flatMap(m -> m.values().stream())
                .mapToInt(m -> m.getOrDefault(outcome, 0)).sum();
    }

    public int count(Kind kind, Depth depth, Outcome outcome) {
        return counts.getOrDefault(kind, Map.of()).getOrDefault(depth, Map.of()).getOrDefault(outcome, 0);
    }

    /** One line per (kind, depth), e.g. {@code RETURN/TOP agree=12 unsafe=0 noise=3 undecided=0 unscored=40}. */
    public String report() {
        StringBuilder sb = new StringBuilder();
        for (Kind kind : Kind.values()) {
            for (Depth depth : Depth.values()) {
                Map<Outcome, Integer> m = counts.getOrDefault(kind, Map.of()).getOrDefault(depth, Map.of());
                if (m.isEmpty()) continue;
                sb.append(kind).append('/').append(depth).append(' ')
                        .append(java.util.Arrays.stream(Outcome.values())
                                .map(o -> o.name().toLowerCase() + "=" + m.getOrDefault(o, 0))
                                .collect(Collectors.joining(" ")))
                        .append('\n');
            }
        }
        return sb.append("TOTAL ").append(java.util.Arrays.stream(Outcome.values())
                .map(o -> o.name().toLowerCase() + "=" + count(o)).collect(Collectors.joining(" "))).toString();
    }
}
