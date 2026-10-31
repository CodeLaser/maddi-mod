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

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;

import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.isSubtype;

/**
 * Per final instance field, the concrete classes whose instances may hold null in it; per getter, its field.
 * {@link NonNullFacts} asks it whether a read {@code x.getF()} / {@code x.f} is non-null where {@code x} is known
 * to be an instance of a type: when none of those classes is a subtype of it. A field absent from the map is not
 * known (not final, no analysed subclass, an open world): never narrowed.
 */
record NarrowedReads(Map<FieldInfo, Set<TypeInfo>> nullClasses, Map<MethodInfo, FieldInfo> getters) {
    static final NarrowedReads NONE = new NarrowedReads(Map.of(), Map.of());

    boolean isEmpty() {
        return nullClasses.isEmpty();
    }

    boolean nonNull(Object member, TypeInfo type) {
        FieldInfo field = member instanceof FieldInfo f ? f : member instanceof MethodInfo m ? getters.get(m) : null;
        Set<TypeInfo> classes = field == null ? null : nullClasses.get(field);
        if (classes == null) return false;
        return classes.stream().noneMatch(x -> isSubtype(x, type));
    }
}
