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

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;

import java.util.Set;

/*
Phase 4.1

Given the modification and independence state of the fields of a type, compute its @Immutable properties.
This analyzer computes @Immutable across all subtypes, because the fields are visible across the subtypes,
hence their modification and independence status is shared.

It is possible to have to wait for other type's @Immutable status, because of extensions and non-private fields.
 */
public interface TypeImmutableAnalyzer {

    void go(TypeInfo primaryType, boolean activateCycleBreaking);

    /**
     * What may be discounted when computing the level a type reaches <em>after the mark</em> (road to
     * immutability §060). Everything listed here modifies only on the before-side of the state transition, so
     * once the mark has been passed it can no longer change.
     *
     * @param fields  fields of eventually immutable type that the type's own {@code @Mark} methods commit
     * @param methods the type's {@code @Mark} and {@code @Only(before=)} methods. Only the abstract ones
     *                actually matter -- rule 1 catches a concrete modifying method through the field it
     *                modifies -- but an interface has nothing <em>but</em> abstract methods, which is exactly
     *                the case this exists for.
     */
    record AfterMark(Set<FieldInfo> fields, Set<MethodInfo> methods, boolean inheritedMarks) {
        public static final AfterMark NONE = new AfterMark(Set.of(), Set.of(), false);

        public AfterMark(Set<FieldInfo> fields, Set<MethodInfo> methods) {
            this(fields, methods, false);
        }

        /** {@code inheritedMarks}: an after-mark evaluation whose transition lives entirely on
         *  supertypes (a markless sub-interface with inherited labels, EVENTUALCLUSTER) -- the excused
         *  sets are empty, but the relaxation paths must still engage: the degenerate
         *  {@code AfterMark(∅,∅)} otherwise reads as NONE and silently degrades to the unconditional
         *  verdict, letting a plain-@FinalFields super sink the member. */
        public boolean isNone() {
            return fields.isEmpty() && methods.isEmpty() && !inheritedMarks;
        }
    }

    /**
     * The level the type reaches once everything in {@code afterMark} can no longer change. Null when undecided.
     * Used by the eventual analyzer (phase 4.3).
     */
    Value.Immutable immutableAfterMark(TypeInfo typeInfo, AfterMark afterMark, boolean activateCycleBreaking);
}
