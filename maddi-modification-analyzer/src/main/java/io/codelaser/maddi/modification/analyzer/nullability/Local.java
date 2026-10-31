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

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import java.util.Objects;

/**
 * A local variable as a graph node, keyed by its DECLARATION (by identity) and name: Java forbids a local to
 * shadow a local, so in scope a name denotes one declaration, but same-named locals of sibling blocks or of
 * different methods are different variables. The method is for display; a key without a declaration (a pattern
 * variable, a name no scope resolved) is per method and name.
 */
public record Local(MethodInfo methodInfo, Element declaration, String name) {
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Local(MethodInfo m, Element d, String n)) || !name.equals(n)) return false;
        return declaration == null ? d == null && methodInfo.equals(m) : declaration == d;
    }

    @Override
    public int hashCode() {
        return declaration == null ? Objects.hash(methodInfo, name)
                : 31 * System.identityHashCode(declaration) + name.hashCode();
    }

    @Override
    public String toString() {
        return name + " in " + methodInfo.fullyQualifiedName();
    }
}
