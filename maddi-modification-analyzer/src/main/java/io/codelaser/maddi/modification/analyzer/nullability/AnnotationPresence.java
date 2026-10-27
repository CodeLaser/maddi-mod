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

import io.codelaser.maddi.cst.api.element.Comment;
import io.codelaser.maddi.cst.api.element.DetailedSources;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.element.Source;
import io.codelaser.maddi.cst.api.element.Visitor;
import io.codelaser.maddi.cst.api.info.InfoMapView;
import io.codelaser.maddi.cst.api.output.OutputBuilder;
import io.codelaser.maddi.cst.api.output.Qualification;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DescendMode;
import io.codelaser.maddi.cst.api.variable.Variable;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A synthetic fact variable: annotation {@code annotationType} is PRESENT on {@code element}, established by
 * {@code element.isAnnotationPresent(X.class)} being true, so {@code element.getAnnotation(X.class)} is not the
 * absent annotation's null (CodeLaser/maddi-mod#22 gap 1's shape, for {@link java.lang.reflect.AnnotatedElement}:
 * nacos' {@code if (method.isAnnotationPresent(Secured.class)) enableAuth(method.getAnnotation(Secured.class))}).
 * Forgotten when the element variable is assigned; no call changes which annotations an element carries.
 */
record AnnotationPresence(Variable element, String annotationType) implements Variable {

    @Override
    public ParameterizedType parameterizedType() {
        return element.parameterizedType();
    }

    @Override
    public String simpleName() {
        return element.simpleName() + "@" + annotationType;
    }

    @Override
    public String fullyQualifiedName() {
        return element.fullyQualifiedName() + "@" + annotationType;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof AnnotationPresence that && fullyQualifiedName().equals(that.fullyQualifiedName());
    }

    @Override
    public int hashCode() {
        return fullyQualifiedName().hashCode();
    }

    @Override
    public String toString() {
        return simpleName();
    }

    @Override
    public OutputBuilder print(Qualification qualification) {
        return element.print(qualification);
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
        return Stream.of();
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
        return element.source();
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
        return new AnnotationPresence(element.rewire(infoMap), annotationType);
    }
}
