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
import io.codelaser.maddi.cst.impl.analysis.NullAnnotations;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

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
 * annotation (by simple name, {@link NullAnnotations}): a source annotation is a contract.
 * <p>
 * Only the top-level state is written, in declaration position. For a TYPE_USE flavour that position is wrong in two
 * cases, which are therefore skipped: an ARRAY ({@code @Nullable String[] a} would speak about the elements; the
 * array itself needs {@code String @Nullable []}), and a NESTED type ({@code @Nullable Map.Entry} does not compile;
 * it needs {@code Map.@Nullable Entry}). Both need the annotation inside the printed type, as do type arguments
 * ({@code List<@Nullable String>}): not yet.
 * <p>
 * In the {@code @NullMarked} style ({@code writeNonNull} false) a missing annotation reads as non-null, so an
 * UNSPECIFIED verdict cannot simply be left out: the analysis did not decide it (an open-world parameter that no
 * analysed call passes null to, what it flows into, a degraded method's return). A method or constructor with such a
 * parameter or return gets the flavour's {@link Flavour#nullUnmarked} annotation, and then its non-null positions
 * need the explicit non-null annotation. A field cannot be unmarked on its own: it gets {@code @Nullable}, the
 * reading that is safe for whoever reads the field. A type variable is not affected: under {@code @NullMarked} it is
 * parametric already. A flavour without an unmarked annotation leaves UNSPECIFIED out, as before.
 */
public class NullabilityDecorator implements Qualification.Decorator {

    /**
     * The annotation family to write.
     *
     * @param typeUse     the annotations are TYPE_USE (JSpecify, Checker Framework, JetBrains since 20.0): see the
     *                    class comment for what is skipped
     * @param nullUnmarked the annotation that leaves a method's nullness unspecified inside a {@code @NullMarked}
     *                    scope; null when the family has none
     */
    public enum Flavour {
        JSPECIFY("org.jspecify.annotations.Nullable", "org.jspecify.annotations.NonNull", true,
                "org.jspecify.annotations.NullUnmarked"),
        CHECKER("org.checkerframework.checker.nullness.qual.Nullable",
                "org.checkerframework.checker.nullness.qual.NonNull", true, null),
        JETBRAINS("org.jetbrains.annotations.Nullable", "org.jetbrains.annotations.NotNull", true, null),
        JSR305("javax.annotation.Nullable", "javax.annotation.Nonnull", false, null),
        MADDI("io.codelaser.maddi.annotation.Nullable", "io.codelaser.maddi.annotation.NotNull", false, null);

        public final String nullable;
        public final String nonNull;
        public final boolean typeUse;
        public final String nullUnmarked;

        Flavour(String nullable, String nonNull, boolean typeUse, String nullUnmarked) {
            this.nullable = nullable;
            this.nonNull = nonNull;
            this.typeUse = typeUse;
            this.nullUnmarked = nullUnmarked;
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
    private final AnnotationExpression nullUnmarkedAnnotation;
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
        nullUnmarkedAnnotation = options.flavour.nullUnmarked == null ? null
                : annotation(runtime, options.flavour.nullUnmarked);
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
        if (element instanceof MethodInfo mi && unmarked(mi)) {
            importsNeeded.add(nullUnmarkedAnnotation.typeInfo().fullyQualifiedName());
            list.add(nullUnmarkedAnnotation);
        }
        AnnotationExpression ae = nullness(element);
        if (ae != null && list.stream().noneMatch(NullAnnotations::isNullnessAnnotation)) {
            importsNeeded.add(ae.typeInfo().fullyQualifiedName());
            list.add(ae);
        }
        return list;
    }

    private AnnotationExpression nullness(Element element) {
        NullableState state = state(element);
        if (state == null) return null;
        return switch (state) {
            case NULLABLE -> nullableAnnotation;
            case NONNULL -> options.writeNonNull || unmarkedPosition(element) ? nonNullAnnotation : null;
            // a field cannot be unmarked: in the @NullMarked style its safe reading is nullable
            case UNSPECIFIED -> !options.writeNonNull && nullUnmarkedAnnotation != null
                                && element instanceof FieldInfo fi && !isTypeVariable(fi.type())
                    ? nullableAnnotation : null;
        };
    }

    // the method of a parameter or return in a method that gets the unmarked annotation
    private boolean unmarkedPosition(Element element) {
        return switch (element) {
            case ParameterInfo pi -> unmarked(pi.methodInfo());
            case MethodInfo mi -> unmarked(mi);
            default -> false;
        };
    }

    // in the @NullMarked style, a method with an undecided parameter or return (not a type variable: parametric)
    private boolean unmarked(MethodInfo mi) {
        if (options.writeNonNull || nullUnmarkedAnnotation == null) return false;
        return java.util.stream.Stream.concat(mi.parameters().stream(), Stream.of(mi))
                .anyMatch(e -> state(e) == NullableState.UNSPECIFIED && !isTypeVariable(type(e)));
    }

    private static boolean isTypeVariable(ParameterizedType type) {
        return type.typeParameter() != null && type.arrays() == 0;
    }

    private static ParameterizedType type(Element element) {
        return switch (element) {
            case ParameterInfo pi -> pi.parameterizedType();
            case MethodInfo mi -> mi.returnType();
            case FieldInfo fi -> fi.type();
            default -> throw new UnsupportedOperationException();
        };
    }

    // the verdict to print at this position; null where nothing can be printed
    private NullableState state(Element element) {
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
        return element.analysis().getOrDefault(property, ValueImpl.NullabilityImpl.UNSPECIFIED).state();
    }

    private static boolean isNested(ParameterizedType type) {
        TypeInfo ti = type.typeInfo();
        return ti != null && ti.compilationUnitOrEnclosingType().isRight();
    }

    private static boolean alreadyAnnotated(Element element, ParameterizedType type) {
        return Stream.concat(element.annotations().stream(), type.annotations().stream())
                .anyMatch(NullAnnotations::isNullnessAnnotation);
    }

    @Override
    public List<ImportStatement> importStatements() {
        List<ImportStatement> list = new ArrayList<>();
        if (delegate != null) list.addAll(delegate.importStatements());
        importsNeeded.forEach(fqn -> list.add(runtime.newImportStatementBuilder().setImport(fqn).build()));
        return list;
    }
}
