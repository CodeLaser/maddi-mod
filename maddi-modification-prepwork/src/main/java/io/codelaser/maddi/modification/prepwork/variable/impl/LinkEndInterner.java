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

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
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
 * the engine also keeps equal ends of different types apart, and of the same structure (see sameStructure).
 * <p>
 * On by default since 2026-11-13; gate {@code NOINTERNLINKS} (presence) switches it off. Proving-corpus A/B (2026-11-12, same commit, gate off
 * against on): activemq, jenkins, langchain4j 0 lines; fernflower, timefold 2; guava 6 (it varies run to run). Each
 * moved verdict is an anonymous class's or lambda's method going from nonModifying false to true, where the method
 * modifies nothing of its own (VarVersionsProcessor.$1.processExprent reads a captured map; InvokableTest's Runnables
 * print captured locals). Fernflower's live heap at iteration 1: 784 -> 588 MB.
 */
public final class LinkEndInterner implements AutoCloseable {
    private static final boolean OFF = System.getenv("NOINTERNLINKS") != null;
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
        if (OFF || !shareable(variable)) return variable;
        LinkEndInterner interner = CURRENT.get();
        if (interner == null) return variable;
        Variable known = interner.ends.putIfAbsent(variable, variable);
        if (known == null || known == variable) return variable;
        return sameStructure(known, variable, 0) ? known : variable;
    }

    /*
     A real field's reference is shared only when its field is the one its owner declares. A field re-owned by a
     translation (FieldInfo.withOwner, one object per field and owner since CanonicalVirtuals.reowned) used to be a new
     object each time, so that its references were never shared; shared, they moved jenkins' verdicts (28 lines,
     nonModifying true to false on methods that modify nothing, 2026-11-13) for a reason not found yet. Kept apart as
     before: the field objects stay one per owner, their references are not shared.
     */
    private static boolean shareable(Variable variable) {
        if (!(variable instanceof FieldReference fr)) return true;
        FieldInfo fi = fr.fieldInfo();
        if (fi.name().startsWith("§")) return true;
        for (FieldInfo declared : fi.owner().fields()) {
            if (declared == fi) return true;
        }
        return false;
    }

    /*
     Equal is not enough even within one method: a field reference's name is its field and its scope's name, and two
     equal names can stand for different scope chains (a scope that is a variable, and one that is an expression named
     by its position). Given the other one, the engine's isPartOf(primary, from) failed (timefold, ActiveMQ) and
     verdicts moved. Shared only when the structure the engine walks is the same: class, type, field, scope chain.
     */
    private static boolean sameStructure(Variable a, Variable b, int depth) {
        if (a == b) return true;
        if (a == null || b == null || depth > 32) return false;
        if (a.getClass() != b.getClass() || !Objects.equals(a.parameterizedType(), b.parameterizedType())) return false;
        if (a instanceof FieldReference fa && b instanceof FieldReference fb) {
            return fa.fieldInfo() == fb.fieldInfo()
                   && fa.isDefaultScope() == fb.isDefaultScope()
                   && Objects.equals(fa.scope(), fb.scope())
                   && sameStructure(fa.scopeVariable(), fb.scopeVariable(), depth + 1)
                   && (fa.scopeVariable() != null || fb.scopeVariable() == null);
        }
        if (a instanceof DependentVariable da && b instanceof DependentVariable db) {
            return Objects.equals(da.arrayExpression(), db.arrayExpression())
                   && Objects.equals(da.indexExpression(), db.indexExpression())
                   && sameStructure(da.arrayVariable(), db.arrayVariable(), depth + 1)
                   && (da.arrayVariable() != null || db.arrayVariable() == null);
        }
        return true;
    }
}
