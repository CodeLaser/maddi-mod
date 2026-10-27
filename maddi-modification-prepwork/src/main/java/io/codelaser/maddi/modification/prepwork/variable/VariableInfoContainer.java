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

import io.codelaser.maddi.annotation.NotNull;
import io.codelaser.maddi.cst.api.variable.Variable;

public interface VariableInfoContainer {
     /* note: local variables also have a variableNature. This one can override their value
        but only when this variable nature is VariableDefinedOutsideLoop. All the others have to agree exactly,
        and can be used interchangeably.
         */

    @NotNull
    Variable variable();

    @NotNull
    VariableNature variableNature();

    // default statement/modification time
    int IGNORE_STATEMENT_TIME = -1;

    // prefixes in assignment id
    // see TestLevelSuffixes to visually understand the order

    String NOT_YET_READ = "-";

    boolean hasEvaluation();

    boolean hasMerge();

    boolean isPrevious();

    boolean has(Stage level);

    /**
     * General method for obtaining the "most relevant" <code>VariableInfo</code> object describing the state
     * of the variable after executing this statement.
     *
     * @return a VariableInfo object, always. There would not have been a <code>VariableInfoContainer</code> if there
     * was not at least one <code>VariableInfo</code> object.
     */
    @NotNull
    VariableInfo best();

    /*
     * like current, but then with a limit
     */
    @NotNull
    VariableInfo best(Stage level);

    /**
     * Returns either the current() of the previous VIC, or the initial value if this is the first statement
     * where this variable occurs.
     */
    @NotNull
    VariableInfo getPreviousOrInitial();

    /**
     * @return if the variable was created in this statement
     */
    boolean isInitial();

    boolean isRecursivelyInitial();

    @NotNull
    VariableInfo getRecursiveInitial();

    VariableInfo bestCurrentlyComputed();

    String indexOfDefinition();

    /**
     * A back-reference-free snapshot of this container: its {@code previousOrInitial} link into the
     * previous statement's container is collapsed to a value snapshot, so this container no longer pins
     * the chain of earlier statements' containers — the basis of the VariableData flatten-snapshot
     * memory lever (see {@code maddi-modification-analyzer/DESIGN-vardata-flatten.md}). The value-bearing
     * queries are preserved: {@link #best()}, {@link #best(Stage)}, {@link #has(Stage)},
     * {@link #bestCurrentlyComputed()}, {@link #indexOfDefinition()} and {@link #getPreviousOrInitial()}
     * all return what they did. Only the structural "where did this come from" queries change —
     * {@link #isInitial()}/{@link #isPrevious()}/{@link #isRecursivelyInitial()}/
     * {@link #getRecursiveInitial()} — whose callers are validated as none-external-that-matter in the
     * design. Returns {@code this} when already back-reference-free.
     */
    VariableInfoContainer flattened();
}
