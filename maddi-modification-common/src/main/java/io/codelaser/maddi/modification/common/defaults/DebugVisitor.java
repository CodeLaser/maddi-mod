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

package io.codelaser.maddi.modification.common.defaults;

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.graph.G;

import java.util.List;
import java.util.Map;

public interface DebugVisitor {

    default void allTypes(List<TypeInfo> allTypes) {
    }

    default void dataMapAfterFieldMethodAnalyzer(Map<Element, ShallowAnalyzer.InfoData> dataMap) {
    }

    default void dataMapAfterTypeAnalyzer(Map<Element, ShallowAnalyzer.InfoData> dataMap) {
    }

    default void inputTypes(List<TypeInfo> types) {
    }

    default void sortedLinearized(List<TypeInfo> sorted) {
    }

    default void typeGraph(G<TypeInfo> typeGraph) {
    }
}
