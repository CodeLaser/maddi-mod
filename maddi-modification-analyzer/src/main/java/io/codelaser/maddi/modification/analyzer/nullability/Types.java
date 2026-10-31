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
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;

// static helpers the parts of the nullability pass share: about types, and about nodes regardless of the graph
final class Types {
    private Types() {
    }

    static boolean primitive(ParameterizedType type) {
        return type != null && type.arrays() == 0 && type.isPrimitiveExcludingVoid();
    }

    /*
     Policy.withoutTestNulls: whether a node of the graph belongs to test code, by the source set of the type that
     declares it. A node without a type (a library's, the null markers' own) is not test code.
     */
    static boolean testCode(Object node) {
        if (node instanceof Content c) return testCode(c.of());
        if (node instanceof Arg a) return testCode(a.of());
        TypeInfo type = switch (node) {
            case TypeInfo t -> t;
            case ParameterInfo pi -> pi.methodInfo().typeInfo();
            case MethodInfo mi -> mi.typeInfo();
            case FieldInfo fi -> fi.owner();
            case Local l -> l.methodInfo() == null ? null : l.methodInfo().typeInfo();
            case null, default -> null;
        };
        if (type == null) return false;
        var cu = type.primaryType().compilationUnit();
        return cu != null && cu.sourceSet() != null && cu.sourceSet().test();
    }

    static boolean isSlot(Object node) {
        return node instanceof Arg || node instanceof Content || node instanceof Keyed;
    }
}
