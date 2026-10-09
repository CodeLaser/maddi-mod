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
}
