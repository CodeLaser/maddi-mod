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

package io.codelaser.maddi.modification.prepwork.io;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.element.Comment;
import io.codelaser.maddi.cst.api.element.CompilationUnit;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.element.ImportStatement;
import io.codelaser.maddi.cst.api.expression.AnnotationExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.output.Qualification;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.cst.impl.type.DeclaredNullability;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Annotated Java output for the inferred nullability (maddi-mod docs/design/nullability.md D1): reads the B2
 * properties ({@code NULLABILITY_FIELD}, {@code _PARAMETER}, {@code _METHOD}) and writes {@code @Nullable}, and
 * optionally the non-null annotation, in the chosen {@link Flavour}. Wraps another decorator (typically
 * {@link DecoratorImpl}), so maddi's own annotations and the null annotations can be printed together.
 * <p>
 * Nothing is written for a primitive, for an UNSPECIFIED verdict, or where the source already carries a null
 * annotation (by simple name, as {@link DeclaredNullability} reads them): a source annotation is a contract.
 * <p>
 * Only the top-level state is written, in declaration position. For a TYPE_USE flavour that position is wrong in two
 * cases, which are therefore skipped: an ARRAY ({@code @Nullable String[] a} would speak about the elements; the
 * array itself needs {@code String @Nullable []}), and a NESTED type ({@code @Nullable Map.Entry} does not compile;
 * it needs {@code Map.@Nullable Entry}). Both need the annotation inside the printed type, as do type arguments
 * ({@code List<@Nullable String>}): not yet.
 */
public class NullabilityDecorator implements Qualification.Decorator {

    /**
     * The annotation family to write.
     *
     * @param typeUse the annotations are TYPE_USE (JSpecify, Checker Framework, JetBrains since 20.0): see the class
     *                comment for what is skipped
     */
    public enum Flavour {
        JSPECIFY("org.jspecify.annotations.Nullable", "org.jspecify.annotations.NonNull", true),
        CHECKER("org.checkerframework.checker.nullness.qual.Nullable",
                "org.checkerframework.checker.nullness.qual.NonNull", true),
        JETBRAINS("org.jetbrains.annotations.Nullable", "org.jetbrains.annotations.NotNull", true),
        JSR305("javax.annotation.Nullable", "javax.annotation.Nonnull", false),
        MADDI("io.codelaser.maddi.annotation.Nullable", "io.codelaser.maddi.annotation.NotNull", false);

        public final String nullable;
        public final String nonNull;
        public final boolean typeUse;

        Flavour(String nullable, String nonNull, boolean typeUse) {
            this.nullable = nullable;
            this.nonNull = nonNull;
            this.typeUse = typeUse;
        }
    }

    /**
     * @param flavour      the annotation family
     * @param writeNonNull also annotate NONNULL verdicts: for code outside a {@code @NullMarked} scope. False is the
     *                     {@code @NullMarked} style: only {@code @Nullable} is written.
     */
    public record Options(Flavour flavour, boolean writeNonNull) {
        public static final Options JSPECIFY_NULL_MARKED = new Options(Flavour.JSPECIFY, false);
    }

    private final Options options;
    private final Qualification.Decorator delegate;
    private final AnnotationExpression nullableAnnotation;
    private final AnnotationExpression nonNullAnnotation;
    private final Set<String> importsNeeded = new TreeSet<>();
    private final Runtime runtime;

    /**
     * @param delegate the decorator whose annotations, comments and imports come first; null for none
     */
    public NullabilityDecorator(Runtime runtime, Options options, Qualification.Decorator delegate) {
        this.runtime = runtime;
        this.options = options;
        this.delegate = delegate;
        nullableAnnotation = annotation(runtime, options.flavour.nullable);
        nonNullAnnotation = annotation(runtime, options.flavour.nonNull);
    }

    // the annotation's type need not be on the analysed classpath: a stub carries the name for printing and import
    private static AnnotationExpression annotation(Runtime runtime, String fqn) {
        TypeInfo typeInfo;
        try {
            typeInfo = runtime.getFullyQualified(fqn, false);
        } catch (RuntimeException e) {
            typeInfo = null;
        }
        if (typeInfo == null) {
            int dot = fqn.lastIndexOf('.');
            CompilationUnit cu = runtime.newCompilationUnitStub(fqn.substring(0, dot));
            typeInfo = runtime.newTypeInfo(cu, fqn.substring(dot + 1));
            typeInfo.builder().setTypeNature(runtime.typeNatureStub())
                    .setParentClass(runtime.objectParameterizedType())
                    .setAccess(runtime.accessPublic())
                    .setSource(runtime.noSource())
                    .commit();
        }
        return runtime.newAnnotationExpressionBuilder().setTypeInfo(typeInfo).build();
    }

    @Override
    public List<Comment> comments(Element element) {
        return delegate == null ? List.of() : delegate.comments(element);
    }

    @Override
    public List<AnnotationExpression> annotations(Element element) {
        List<AnnotationExpression> list = new ArrayList<>();
        if (delegate != null) list.addAll(delegate.annotations(element));
        AnnotationExpression ae = nullness(element);
        if (ae != null && list.stream().noneMatch(DeclaredNullability::isNullnessAnnotation)) {
            importsNeeded.add(ae.typeInfo().fullyQualifiedName());
            list.add(ae);
        }
        return list;
    }

    private AnnotationExpression nullness(Element element) {
        ParameterizedType type;
        Property property;
        switch (element) {
            case FieldInfo fi -> {
                type = fi.type();
                property = PropertyImpl.NULLABILITY_FIELD;
            }
            case ParameterInfo pi -> {
                type = pi.parameterizedType();
                property = PropertyImpl.NULLABILITY_PARAMETER;
            }
            case MethodInfo mi when !mi.isConstructor() && !mi.returnType().isVoid() -> {
                type = mi.returnType();
                property = PropertyImpl.NULLABILITY_METHOD;
            }
            default -> {
                return null;
            }
        }
        if (type.isPrimitiveExcludingVoid() && type.arrays() == 0) return null;
        if (options.flavour.typeUse && (type.arrays() > 0 || isNested(type))) return null;
        if (alreadyAnnotated(element, type)) return null;
        NullableState state = element.analysis().getOrDefault(property, ValueImpl.NullabilityImpl.UNSPECIFIED).state();
        return switch (state) {
            case NULLABLE -> nullableAnnotation;
            case NONNULL -> options.writeNonNull ? nonNullAnnotation : null;
            case UNSPECIFIED -> null;
        };
    }

    private static boolean isNested(ParameterizedType type) {
        TypeInfo ti = type.typeInfo();
        return ti != null && ti.compilationUnitOrEnclosingType().isRight();
    }

    private static boolean alreadyAnnotated(Element element, ParameterizedType type) {
        return Stream.concat(element.annotations().stream(), type.annotations().stream())
                .anyMatch(DeclaredNullability::isNullnessAnnotation);
    }

    @Override
    public List<ImportStatement> importStatements() {
        List<ImportStatement> list = new ArrayList<>();
        if (delegate != null) list.addAll(delegate.importStatements());
        importsNeeded.forEach(fqn -> list.add(runtime.newImportStatementBuilder().setImport(fqn).build()));
        return list;
    }
}
