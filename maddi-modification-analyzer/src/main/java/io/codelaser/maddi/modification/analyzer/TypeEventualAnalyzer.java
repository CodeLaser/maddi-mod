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

import io.codelaser.maddi.cst.api.info.TypeInfo;

/**
 * Phase 4.3: eventual immutability by <em>propagation</em> (road to immutability §060; plan in
 * {@code docs/design/eventual-immutability.md}).
 * <p>
 * A type that holds a field of eventually immutable type — overwhelmingly one of the {@code io.codelaser.maddi.support}
 * classes — is itself eventually immutable, and its methods inherit the mark: a method calling a {@code @Mark}
 * method on such a field is itself a {@code @Mark}, one calling an {@code @Only(before=)} method can itself only
 * run before the mark, and so on. The same holds for a call on {@code this} to an inherited marked method.
 * <p>
 * This needs no preconditions: the callee's own contract says which side of the transition it belongs to. It is
 * the reason the precondition machinery of the previous analyzer generation does not have to be revived.
 */
public interface TypeEventualAnalyzer {

    void go(TypeInfo typeInfo, boolean activateCycleBreaking);
}
