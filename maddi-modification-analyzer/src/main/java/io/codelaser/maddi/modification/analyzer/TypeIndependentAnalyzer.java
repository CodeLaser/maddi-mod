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
import io.codelaser.maddi.cst.api.info.TypeInfo;

/*
Phase 4.1

Given the modification and independence state of the fields of a type, compute its @Immutable properties.
This analyzer computes @Immutable across all subtypes, because the fields are visible across the subtypes,
hence their modification and independence status is shared.

It is possible to have to wait for other type's @Immutable status, because of extensions and non-private fields.
 */
public interface TypeIndependentAnalyzer {

    void go(TypeInfo primaryType, boolean activateCycleBreaking);

    /**
     * The independence the type reaches once everything in {@code afterMark} can no longer change (road to
     * immutability §060), the counterpart of {@link TypeImmutableAnalyzer#immutableAfterMark}. Null when undecided.
     * <p>
     * Independence is a separate axis from immutability, and §060 does not define an "eventual independence".
     * It nevertheless has a well-defined after-the-mark reading: a dependent accessor that can only be called
     * <em>before</em> the mark ({@code TypeInfo.builder()} asserts {@code inspection.isVariable()}) cannot leak
     * anything once the mark has been passed. Without this, every eventually immutable type stops at
     * {@code FINAL_FIELDS} on the dependence cap in {@code computeImmutableType}, before the relaxation it was
     * given an {@code AfterMark} for is ever consulted.
     */
    Value.Independent independentAfterMark(TypeInfo typeInfo, TypeImmutableAnalyzer.AfterMark afterMark,
                                           boolean activateCycleBreaking);

    /**
     * The type's independence with its SELF-REFERENCING fields (declared type = the owning type) left out, as an
     * input to the immutability rule only; never published. A self field's own independence is derived from the
     * very immutability verdict being computed -- dependent while it is undecided -- so reading it there makes
     * that verdict wait on itself. Assuming it independent is self-consistent: if the type turns out immutable,
     * exposing the field is harmless; if not, rules 1 and 2 decide anyway. The published independence keeps
     * counting the field, so a mutable type exposing one stays dependent. Null when undecided.
     */
    Value.Independent independentIgnoringSelfFields(TypeInfo typeInfo, boolean activateCycleBreaking);
}
