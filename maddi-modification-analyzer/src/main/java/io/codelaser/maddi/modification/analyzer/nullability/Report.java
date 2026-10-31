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

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.ForEachStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.NullabilityFacts;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @param verdicts fields, parameters, and methods (their return value)
 * @param locals   every declared local variable, by its declaration
 * @param useSites per statement, the locals and parameters known non-null when it starts (M4)
 * @param smartCasts the same, restricted to what Kotlin's smart cast derives: for a printer that drops
 *                   {@code !!} where Kotlin will see the variable as non-null
 * @param nonNullCallResults calls to an analysed method whose return null reaches, where it reaches only through
 *                   parameters whose arguments at that call cannot be null (by identity; see {@link #callResultNonNull})
 * @param preconditions the parameters a null argument makes throw before their method completes (dereferenced,
 *                   required, rejected on every normal exit), in a closed world with their whole override family
 *                   (CodeLaser/maddi-mod#41). A fact about the program, whatever the policy: diagnose reports
 *                   their {@link #nullArguments}, an annotator writes {@code @NonNull}, a printer under
 *                   {@link Policy#preconditionsNonNull} declares them non-null
 * @param nullArguments the calls that may pass null to a precondition parameter, as the analysis without
 *                   {@link Policy#preconditionsNonNull} sees them: where the NPE inside the callee comes from
 * @param preconditionsNonNull under {@link Policy#preconditionsNonNull}, the precondition parameters it declared
 *                   non-null that null reached without it: where a printer reports the change
 *                   ({@link #preconditionNonNull}). Empty under any other policy
 * @implNote the report is the {@link NullabilityFacts} a printer of a null-checked language asks for: the
 * declaration verdicts, the locals, the assertions at a declaration, the use-site facts, as smart casts only, and
 * the call results. A printer with hints for the library's declarations composes it with
 * {@link NullabilityFacts#STORED} ({@link NullabilityFacts#orElse}).
 */
public record Report(Map<Info, ParameterizedType> verdicts, Map<Local, ParameterizedType> locals,
                     Map<Object, Object> cause, Map<Object, String> seedOrigin, NonNullFacts useSites,
                     NonNullFacts smartCasts, Set<Local> asserted, Set<Local> unobserved,
                     Set<Expression> nonNullCallResults, Set<ParameterInfo> preconditions,
                     List<NullArgument> nullArguments, Set<ParameterInfo> preconditionsNonNull,
                     Set<ParameterInfo> rejectsNull, Set<Object> writtenReach,
                     Set<io.codelaser.maddi.cst.api.info.TypeParameter> nonNullTypeParameters,
                     Map<TypeInfo, ParameterizedType> parentClasses)
        implements NullabilityFacts {

    /** Kotlin: a class's superclass with a nullable type argument its own code writes ('LinkedList<String?>'). */
    @Override
    public ParameterizedType parentClass(TypeInfo typeInfo) {
        return parentClasses.get(typeInfo);
    }

    /** Kotlin: a class or method type parameter the verdicts hold non-null ({@link TypeUses#nonNullBounds}): the printer bounds it by Any. */
    @Override
    public boolean nonNullTypeParameter(io.codelaser.maddi.cst.api.info.TypeParameter typeParameter) {
        return nonNullTypeParameters.contains(typeParameter);
    }

    /**
     * A written null reaches this node: a literal, an argument, an initializer, an annotation, a default value
     * (the pass's seed), not only a library contract or other indirect evidence (CodeLaser/maddi-mod#49). Reached
     * but not by a written null: by contract only. A node can be reached both ways; this says whether the first
     * holds, whatever {@link #seedOrigin} and {@link #cause} recorded first.
     */
    public boolean reachedByWrittenNull(Object node) {
        return writtenReach.contains(node);
    }

    @Override
    public ParameterizedType field(FieldInfo fieldInfo) {
        return verdicts.get(fieldInfo);
    }

    @Override
    public ParameterizedType parameter(ParameterInfo parameterInfo) {
        return verdicts.get(parameterInfo);
    }

    @Override
    public ParameterizedType returnType(MethodInfo methodInfo) {
        return verdicts.get(methodInfo);
    }

    @Override
    public ParameterizedType reassignedParameter(ParameterInfo parameterInfo) {
        return reassigned(parameterInfo);
    }

    /** The smart casts only: {@link #useSites} also counts requireNonNull and contracts, which the target does not. */
    @Override
    public boolean nonNullAt(Statement statement, Variable variable) {
        return smartCasts.nonNullAt(statement, variable);
    }

    /** The same at a call: also what the enclosing condition establishes, and final fields of this. */
    @Override
    public boolean nonNullAt(Expression expression, Variable variable) {
        return smartCasts.nonNullAt(expression, variable);
    }

    @Override
    public boolean preconditionNonNull(ParameterInfo parameterInfo) {
        return preconditionsNonNull.contains(parameterInfo);
    }

    @Override
    public boolean rejectsNull(ParameterInfo parameterInfo) {
        return rejectsNull.contains(parameterInfo);
    }

    /** The pass analysed the program: a declaration without a nullable verdict is not null. */
    @Override
    public boolean nullabilityAnalysed() {
        return true;
    }

    /**
     * CodeLaser/maddi-mod#22 gap 9: the callee's return is nullable (its verdict), but not the result of THIS call,
     * because null reaches the return only from parameters this call's arguments do not make null.
     * {@code getInteger(key, defaultValue)} returning {@code search(key).orElse(defaultValue)}, called as
     * {@code getInteger(KEY, 5)} by one caller and {@code getInteger(key, null)} by another. Compared by identity.
     */
    public boolean callResultNonNull(Expression call) {
        return nonNullCallResults.contains(call);
    }

    /**
     * Whether the printer asserts this local at its declaration ({@link Policy#assertAtDeclaration}): null reaches
     * its initializer, the block dereferences it unconditionally further on, and its verdict is non-null.
     * Same arguments as {@link #local}.
     */
    public boolean assertedAtDeclaration(MethodInfo methodInfo, Element declaration, LocalVariable variable) {
        return asserted.contains(new Local(methodInfo, declaration, variable.simpleName()));
    }

    /**
     * For a local {@link #assertedAtDeclaration}: the linking engine proves that no statement between the
     * declaration and the first unconditional dereference refers to it, directly or through a linked variable,
     * so asserting it earlier changes no behaviour. False without that proof. Same arguments as {@link #local}.
     */
    public boolean unobservedBeforeDereference(MethodInfo methodInfo, Element declaration, LocalVariable variable) {
        Local local = new Local(methodInfo, declaration, variable.simpleName());
        return asserted.contains(local) && unobserved.contains(local);
    }

    /**
     * The verdict of a local variable, or null when there is none (a pattern variable; a declaration the pass
     * did not see).
     *
     * @param declaration the {@link LocalVariableCreation} (also of a for-each loop, a {@code for} initializer,
     *                    a try resource), the {@link ForEachStatement}, or the {@link TryStatement.CatchClause}
     *                    that declares {@code variable}; compared by identity
     */
    /**
     * A parameter the body assigns: the verdict of what the body assigns it, the type of the Kotlin printer's
     * {@code var p = p} (CodeLaser/maddi-mod#22 gap 5). The parameter's own verdict is the caller's value only. Null when
     * the body never assigns it.
     */
    public ParameterizedType reassigned(ParameterInfo parameterInfo) {
        return locals.get(new Local(parameterInfo.methodInfo(), null, parameterInfo.name()));
    }

    public ParameterizedType local(MethodInfo methodInfo, Element declaration, LocalVariable variable) {
        Element d = declaration instanceof ForEachStatement fe ? fe.initializer() : declaration;
        return locals.get(new Local(methodInfo, d, variable.simpleName()));
    }

    /** The chain that made a node nullable: node, its cause, ..., the seed and its origin. */
    public String explain(Object node) {
        StringBuilder sb = new StringBuilder(label(node));
        Object n = node;
        Set<Object> seen = new LinkedHashSet<>();
        while (cause.containsKey(n) && seen.add(n)) {
            n = cause.get(n);
            sb.append(" <- ").append(label(n));
        }
        String origin = seedOrigin.get(n);
        return origin == null ? sb.toString() : sb.append(" <- ").append(origin).toString();
    }

    static String label(Object node) {
        return switch (node) {
            case Content c -> "elements of " + label(c.of());
            case Arg a -> "type argument " + a.index() + " of " + label(a.of());
            case Keyed k -> (k.key() == null ? "values under a key not known" : "values under key " + k.key())
                            + " in " + label(k.slot());
            case MethodInfo mi -> "return " + mi.fullyQualifiedName();
            case Info info -> info.fullyQualifiedName();
            default -> String.valueOf(node);
        };
    }
}
