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

package io.codelaser.maddi.modification.common;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;

/**
 * Which functional interfaces the engine treats as STANDARD: {@code java.util.function}, {@code Runnable}, the
 * synthetic SAM types, and, since CodeLaser/maddi-mod#13, Kotlin's function types {@code kotlin.jvm.functions.Function0..22}
 * and {@code FunctionN}. A standard functional interface has no virtual fields; a call of its SAM on a parameter is
 * an applied-functional-interface marker resolved where the lambda is known; a library method's parameter of such a
 * type ignores modifications. A custom functional interface (a user's one-method interface) keeps its hidden content
 * and its summary.
 * <p>
 * Every Kotlin function type is a {@code FunctionN}, so before this decision a Kotlin higher-order function read its
 * argument as modified and flowing into the function's hidden content, where the Java twin taking a {@code Consumer}
 * did not. Gate {@code KFNCUSTOM} restores that reading for A/B.
 * <p>
 * The CST's own {@link ParameterizedType#isStandardFunctionalInterface()} also serves assignability
 * ({@code IsAssignableFrom}), which this decision leaves alone: the engine's "standard" tests go through here.
 */
public final class FunctionTypes {
    private FunctionTypes() {
    }

    private static final boolean KOTLIN_FUNCTION_TYPES_CUSTOM = System.getenv("KFNCUSTOM") != null;
    private static final boolean LIBRARY_FUNCTION_TYPES_CUSTOM = System.getenv("LIBFNCUSTOM") != null;

    /** A Kotlin function type: {@code kotlin.jvm.functions.Function0..22} or {@code FunctionN}. */
    public static boolean isKotlinFunctionType(TypeInfo typeInfo) {
        return typeInfo != null && typeInfo.fullyQualifiedName().startsWith("kotlin.jvm.functions.Function");
    }

    /** Whether the engine treats {@code typeInfo}'s values as functions without content of their own. */
    public static boolean isStandardFunctionalInterface(TypeInfo typeInfo) {
        if (typeInfo == null) return false;
        return "java.util.function".equals(typeInfo.packageName())
               || !KOTLIN_FUNCTION_TYPES_CUSTOM && isKotlinFunctionType(typeInfo);
    }

    public static boolean isStandardFunctionalInterface(ParameterizedType pt) {
        if (pt.isStandardFunctionalInterface()) return true;
        return !KOTLIN_FUNCTION_TYPES_CUSTOM && pt.isFunctionalInterface() && isKotlinFunctionType(pt.typeInfo());
    }

    public static boolean isSAMOfStandardFunctionalInterface(MethodInfo methodInfo) {
        if (methodInfo.isSAMOfStandardFunctionalInterface()) return true;
        return !KOTLIN_FUNCTION_TYPES_CUSTOM && isKotlinFunctionType(methodInfo.typeInfo())
               && methodInfo == methodInfo.typeInfo().singleAbstractMethod();
    }

    /**
     * Whether a library method's parameter of this type ignores modifications: a standard functional interface, or a
     * library's own function type, one that is annotated {@code @FunctionalInterface} or extends a standard one
     * (Guava's {@code Predicate}, vavr's {@code Function1}, Eclipse Collections' {@code Procedure}, {@code Comparator},
     * {@code Callable}). Calling such an argument does not modify it, so before this a Guava {@code Iterables.any}
     * read its predicate as modified where the JDK's {@code Collection.removeIf} did not. The decision stops at the
     * shallow defaults of library methods: inside the link engine these types stay custom (their summaries, their
     * hidden content). A single-abstract-method type that is neither ({@code Iterable}, {@code Executor},
     * {@code Comparable}) keeps its default. Gate {@code LIBFNCUSTOM} restores the previous reading for A/B.
     */
    public static boolean isLibraryFunctionType(ParameterizedType pt) {
        if (isStandardFunctionalInterface(pt)) return true;
        if (LIBRARY_FUNCTION_TYPES_CUSTOM || pt.arrays() > 0 || !pt.isFunctionalInterface()) return false;
        TypeInfo typeInfo = pt.typeInfo();
        return typeInfo != null && typeInfo.isInterface()
               && (isAnnotatedFunctionalInterface(typeInfo)
                   || typeInfo.interfacesImplemented().stream()
                           .anyMatch(i -> isStandardFunctionalInterface(i.typeInfo())));
    }

    private static boolean isAnnotatedFunctionalInterface(TypeInfo typeInfo) {
        return typeInfo.annotations().stream()
                .anyMatch(ae -> "java.lang.FunctionalInterface".equals(ae.typeInfo().fullyQualifiedName()));
    }
}
