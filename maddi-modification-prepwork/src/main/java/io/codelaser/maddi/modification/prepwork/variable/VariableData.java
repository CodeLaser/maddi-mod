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

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.variable.Variable;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface VariableData extends Value {
    Set<String> knownVariableNames();

    boolean isKnown(String fullyQualifiedName);

    default String knownVariableNamesToString() {
        return knownVariableNames().stream().map(Object::toString).sorted().collect(Collectors.joining(", "));
    }

    default VariableInfo variableInfo(String fullyQualifiedName) {
        return variableInfoContainerOrNull(fullyQualifiedName).best();
    }

    default String indexOfDefinitionOrNull(Variable variable) {
        VariableInfoContainer vic = variableInfoContainerOrNull(variable.fullyQualifiedName());
        if (vic == null) return null;
        return vic.indexOfDefinition();
    }

    default VariableInfo variableInfo(Variable variable) {
        return variableInfo(variable, Stage.MERGE);
    }

    default VariableInfo variableInfo(Variable variable, Stage stage) {
        return variableInfoContainerOrNull(variable.fullyQualifiedName()).best(stage);
    }

    default VariableInfo variableInfo(String fullyQualifiedName, Stage stage) {
        return variableInfoContainerOrNull(fullyQualifiedName).best(stage);
    }

    VariableInfoContainer variableInfoContainerOrNull(String fullyQualifiedName);

    Stream<VariableInfoContainer> variableInfoContainerStream();

    default Iterable<VariableInfo> variableInfoIterable() {
        return variableInfoIterable(Stage.MERGE);
    }

    default Iterable<VariableInfo> variableInfoIterable(Stage stage) {
        Stream<VariableInfo> stream = variableInfoContainerStream().map(vic -> vic.best(stage));
        return stream::iterator;
    }

    default Stream<VariableInfo> variableInfoStream() {
        return variableInfoStream(Stage.MERGE);
    }

    default Stream<VariableInfo> variableInfoStream(Stage stage) {
        return variableInfoContainerStream().map(vic -> vic.best(stage));
    }
}
