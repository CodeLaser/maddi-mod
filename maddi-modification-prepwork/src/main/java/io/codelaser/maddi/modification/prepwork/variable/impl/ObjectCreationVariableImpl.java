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

import io.codelaser.maddi.annotation.rare.IgnoreModifications;
import io.codelaser.maddi.modification.prepwork.variable.ObjectCreationVariable;
import io.codelaser.maddi.cst.api.element.*;
import io.codelaser.maddi.cst.api.info.InfoMap;
import io.codelaser.maddi.cst.api.info.InfoMapView;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.output.OutputBuilder;
import io.codelaser.maddi.cst.api.output.Qualification;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DescendMode;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.element.ElementImpl;
import io.codelaser.maddi.cst.impl.element.SourceImpl;
import io.codelaser.maddi.cst.impl.output.OutputBuilderImpl;
import io.codelaser.maddi.cst.impl.output.TextImpl;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;


public class ObjectCreationVariableImpl implements ObjectCreationVariable {
    public final ParameterizedType returnType;
    public final String index;
    public final String fqn;
    private final MethodInfo methodInfo;
    // hashCode(), cached; String.hash's race-safe scheme
    @IgnoreModifications
    private int hash;
    @IgnoreModifications
    private boolean hashIsZero;

    public ObjectCreationVariableImpl(MethodInfo methodInfo, String index, ParameterizedType parameterizedType) {
        this.returnType = parameterizedType;
        this.index = index;
        fqn = "oc:" + index + ":" + parameterizedType.detailedString();
        this.methodInfo = methodInfo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ObjectCreationVariable that = (ObjectCreationVariable) o;
        return returnType.equals(that.parameterizedType()) &&
               fqn.equals(that.fullyQualifiedName());
    }

    @Override
    public MethodInfo methodInfo() {
        return methodInfo;
    }

    public TypeInfo getOwningType() {
        return methodInfo.typeInfo();
    }

    @Override
    public int hashCode() {
        // Objects.hash(returnType, fqn), without its argument array, and cached: the profile of 2026-10-22
        // (maddi's docs/status/performance-2026-10-22.md) had it at 17% of timefold's CPU. An object creation
        // variable is made by the analysis, after inspection, so its type's hash is final.
        int h = hash;
        if (h != 0 || hashIsZero) return h;
        h = 31 * (31 + Objects.hashCode(returnType)) + Objects.hashCode(fqn);
        if (h == 0) hashIsZero = true;
        else hash = h;
        return h;
    }

    @Override
    public ParameterizedType parameterizedType() {
        return returnType;
    }

    @Override
    public String simpleName() {
        return "oc:" + index;
    }

    @Override
    public String fullyQualifiedName() {
        return fqn;
    }

    @Override
    public OutputBuilder print(Qualification qualification) {
        return new OutputBuilderImpl().add(new TextImpl(simpleName()));
    }

    @Override
    public Stream<Variable> variables(DescendMode descendMode) {
        return Stream.of(this);
    }

    @Override
    public Stream<Variable> variableStreamDoNotDescend() {
        return Stream.of(this);
    }

    @Override
    public Stream<Variable> variableStreamDescend() {
        return Stream.of(this);
    }

    @Override
    public Stream<TypeReference> typesReferenced(Predicate<Element> predicate, DetailedSources detailedSources) {
        return Stream.of(new ElementImpl.TypeReference(parameterizedType().typeInfo(),
                TypeReferenceNature.IMPLICIT, null));
    }

    @Override
    public String toString() {
        return simpleName();
    }

    public MethodInfo getMethodInfo() {
        return methodInfo;
    }

    @Override
    public int complexity() {
        return 1;
    }

    @Override
    public List<Comment> comments() {
        return List.of();
    }

    @Override
    public Source source() {
        return SourceImpl.NO_SOURCE;
    }

    @Override
    public void visit(Predicate<Element> predicate) {
        predicate.test(this);
    }

    @Override
    public void visit(Visitor visitor) {
        visitor.beforeVariable(this);
        visitor.afterVariable(this);
    }

    @Override
    public Variable rewire(InfoMapView infoMap) {
        return new ObjectCreationVariableImpl(infoMap.methodInfo(methodInfo),
                index, parameterizedType().rewire(infoMap));
    }
}
