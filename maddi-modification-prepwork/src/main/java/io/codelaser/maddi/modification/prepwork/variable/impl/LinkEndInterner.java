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

package io.codelaser.maddi.modification.prepwork.variable.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.codelaser.maddi.cst.api.variable.Variable;

/**
 * One object per link end within the computation of one method's links. The link engine builds the same end again and
 * again (a virtual field's face 'x.§m', its scope expression, a field read through a call): on fernflower, 3.8M field
 * references and 4.2M variable expressions held about 50,000 distinct ends, a third of the live heap
 * (2026-11-12). A link made while a scope is open takes its ends through it; the scope belongs to the thread that
 * opened it, and ends with the method.
 * <p>
 * Within one method only. A variable's equality is the method's: a local {@code lst} of one method equals a local
 * {@code lst} of another, and a field whose scope is no variable is named by the scope's line and column, which another
 * file has as well. Shared across methods, an end of one method stood in for an end of another, and verdicts moved
 * (fernflower: 30 lines of the fingerprint). An end is shared only with an equal end of the same class and type, as
 * the engine also keeps equal ends of different types apart.
 * <p>
 * Gate {@code INTERNLINKS} (presence): on. Off by default until the proving-corpus A/B is in: on fernflower it moves
 * one verdict (VarVersionsProcessor.$1.processExprent, nonModifying false to true; the lambda only reads the captured
 * map the enclosing method writes, so the change looks right).
 */
public final class LinkEndInterner implements AutoCloseable {
    private static final boolean OFF = System.getenv("INTERNLINKS") == null;
    private static final ThreadLocal<LinkEndInterner> CURRENT = new ThreadLocal<>();

    private final Map<Variable, Variable> ends = new HashMap<>();
    private final LinkEndInterner previous;

    private LinkEndInterner(LinkEndInterner previous) {
        this.previous = previous;
    }

    /**
     * Opens a scope for the links of one method, on this thread, until {@link #close()}; a nested method (one whose
     * summary is computed while another's links are) has its own.
     */
    public static LinkEndInterner open() {
        LinkEndInterner interner = new LinkEndInterner(CURRENT.get());
        CURRENT.set(interner);
        return interner;
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    /** the end to keep: an equal end of the same class and type met before in this scope, else this one */
    static Variable intern(Variable variable) {
        if (OFF) return variable;
        LinkEndInterner interner = CURRENT.get();
        if (interner == null) return variable;
        Variable known = interner.ends.putIfAbsent(variable, variable);
        if (known == null || known == variable) return variable;
        if (known.getClass() != variable.getClass()
            || !Objects.equals(known.parameterizedType(), variable.parameterizedType())) {
            return variable;
        }
        return known;
    }
}
