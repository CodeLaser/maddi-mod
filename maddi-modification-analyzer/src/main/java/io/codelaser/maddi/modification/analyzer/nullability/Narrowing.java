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
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.output.OutputBuilder;
import io.codelaser.maddi.cst.api.output.Qualification;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DescendMode;
import io.codelaser.maddi.cst.api.variable.Variable;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A synthetic variable for {@link NonNullFacts}, for a read narrowed by the receiver's runtime type
 * (CodeLaser/maddi-mod#22 gap 3). Two kinds:
 * <ul>
 *   <li>{@link #instance}: {@code subject instanceof type} holds (the true branch of the test, or after
 *       {@code if (!(x instanceof T)) return;});</li>
 *   <li>{@link #read}: the local {@code local} holds {@code subject.member()} or {@code subject.member}, a getter
 *       or a field read, assigned and not reassigned since, and {@code subject} not reassigned either.</li>
 * </ul>
 * Where both hold for the same subject, the local is non-null when every class an instance of {@code type} can be
 * leaves no null in the field the member reads ({@code NullabilityPass.NarrowedReads}): in
 * {@code Service s = event.getService(); if (event instanceof Register) add(s);} the null a {@code Fuzzy} event
 * passes to the shared constructor does not reach {@code add}. Equal by kind, subject, local, type and member.
 * Never printed and never part of a CST: {@link #print} and {@link #source()} borrow the subject's.
 */
record Narrowing(Variable subject, TypeInfo type, Variable local, Object member) implements Variable {

    static Narrowing instance(Variable subject, TypeInfo type) {
        return new Narrowing(subject, type, null, null);
    }

    static Narrowing read(Variable local, Variable subject, Object member) {
        return new Narrowing(subject, null, local, member);
    }

    boolean isInstance() {
        return type != null;
    }

    @Override
    public ParameterizedType parameterizedType() {
        return subject.parameterizedType();
    }

    @Override
    public String simpleName() {
        return isInstance() ? subject.simpleName() + " instanceof " + type.simpleName()
                : local.simpleName() + " = " + subject.simpleName() + "." + member;
    }

    @Override
    public String fullyQualifiedName() {
        return isInstance() ? subject.fullyQualifiedName() + " instanceof " + type.fullyQualifiedName()
                : local.fullyQualifiedName() + " = " + subject.fullyQualifiedName() + "." + member;
    }

    @Override
    public String toString() {
        return simpleName();
    }

    @Override
    public OutputBuilder print(Qualification qualification) {
        return subject.print(qualification);
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
        return subject.source();
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
        return this; // walk-local: never stored past the walk that made it
    }
}
