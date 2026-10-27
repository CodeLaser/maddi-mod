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

package io.codelaser.maddi.modification.prepwork.variable;

import io.codelaser.maddi.modification.prepwork.StatementIndex;

// suffixes in assignment id; these act as the 3 levels for setProperty
public enum Stage {
    INITIAL(StatementIndex.INIT), // +I, + comes before '-', '.', and the digits
    EVALUATION(StatementIndex.EVAL), // -E the - comes before the digits
    MERGE(StatementIndex.MERGE); // =M, the '=' comes after the digits
    public final String label;

    Stage(String label) {
        this.label = label;
    }

    public static boolean isPresent(String s) {
        return s != null && (s.endsWith(INITIAL.label) || s.endsWith(EVALUATION.label) || s.endsWith(MERGE.label));
    }

    public static String without(String s) {
        if (isPresent(s)) {
            return s.substring(0, s.length() - 2);
        }
        return s;
    }

    public static Stage from(String stage) {
        return switch (stage) {
            case "+I" -> INITIAL;
            case "-E" -> EVALUATION;
            case "=M" -> MERGE;
            default -> throw new UnsupportedOperationException();
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
