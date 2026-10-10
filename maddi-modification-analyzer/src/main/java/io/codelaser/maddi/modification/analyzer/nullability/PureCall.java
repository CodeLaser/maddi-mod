/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.element.Comment;
import io.codelaser.maddi.cst.api.element.DetailedSources;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.element.Source;
import io.codelaser.maddi.cst.api.element.Visitor;
import io.codelaser.maddi.cst.api.info.InfoMapView;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.output.OutputBuilder;
import io.codelaser.maddi.cst.api.output.Qualification;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DescendMode;
import io.codelaser.maddi.cst.api.variable.Variable;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * {@code x.m()}, a synthetic variable for the result of a call to a non-modifying method without arguments on a
 * tracked variable (CodeLaser/maddi-mod#22 gap 8): known non-null after {@code x.m() != null}, so the same call
 * again ({@code String errMsg = ioe.getMessage();}) is not the library's null. Forgotten when {@code x} is assigned,
 * and at a call that may change {@code x}: a modifying one on {@code x}, one handed {@code x}, and, for a field
 * receiver, any call. Java only: Kotlin does not smart-cast a call.
 */
record PureCall(Variable receiver, MethodInfo method, ParameterizedType type) implements Variable {

    @Override
    public ParameterizedType parameterizedType() {
        return type;
    }

    @Override
    public String simpleName() {
        return receiver.simpleName() + "." + method.name() + "()";
    }

    @Override
    public String fullyQualifiedName() {
        return receiver.fullyQualifiedName() + "." + method.fullyQualifiedName();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof PureCall that && fullyQualifiedName().equals(that.fullyQualifiedName());
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
        return receiver.print(qualification);
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
        return receiver.source();
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
        return new PureCall(receiver.rewire(infoMap), method, type);
    }
}
