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

package io.codelaser.maddi.modification.prepwork;

import static io.codelaser.maddi.modification.prepwork.StatementIndex.*;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.IntConstant;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.info.TypeParameter;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.ThrowStatement;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.*;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;

public class Util {

    /**
     * A PLACEHOLDER: an overridable instance method whose body only throws -- Eclipse Collections'
     * {@code default MutableByteList sortThis(ByteComparator c) { throw new UnsupportedOperationException(...); }},
     * overridden by {@code ByteArrayList} with a body that sorts. Its own body says nothing about what a call
     * through it does: every call that completes reaches an override. So, like an abstract method, it collects its
     * implementations (prepwork) and its RECEIVER and PARAMETER MODIFICATION is their union (engine work list O2).
     * Every other default or concrete method keeps its own body's verdict (the engine does not compute a dispatch
     * union; see ShadowModificationPass E6), and a placeholder keeps its body's verdict on everything else.
     */
    public static boolean isThrowOnlyPlaceholder(MethodInfo methodInfo) {
        if (methodInfo.isAbstract() || methodInfo.isStatic() || methodInfo.isConstructor() || methodInfo.isFinal()
            || methodInfo.access().isPrivate()) {
            return false;
        }
        Block body = methodInfo.methodBody();
        return body != null && body.statements().size() == 1
               && body.statements().getFirst() instanceof ThrowStatement;
    }

    /**
     * Whether a method's receiver/parameter modification is the union over its implementations: an abstract method,
     * or a {@link #isThrowOnlyPlaceholder placeholder} that has at least one (registered by prepwork).
     */
    public static boolean unionOverImplementations(MethodInfo methodInfo) {
        return methodInfo.isAbstract()
               || isThrowOnlyPlaceholder(methodInfo)
                  && !methodInfo.analysis().getOrDefault(PropertyImpl.IMPLEMENTATIONS,
                ValueImpl.SetOfMethodInfoImpl.EMPTY).isEmpty();
    }

    /**
     * CodeLaser/maddi-mod#117, a soundness rule for the structural verdict. Hidden and accessible content may share
     * objects: fernflower's Statement keeps its children in 'stats' (hidden content, elements of a collection) and the
     * first of them again in 'first' (accessible, a field of type Statement). Modifying an element of 'stats' may then
     * modify the object 'first' points to. Up the owner chain of the container ('sequence' for
     * 'sequence.stats.§$s'), the first owner whose type has another non-static field whose type is compatible with
     * the element's: assignable one way or the other, on the erasure. A field whose type is a type parameter is
     * hidden content itself and does not count; a field of type Object counts. Null when there is none.
     * Gate NOHIDDENALIAS.
     */
    public static Variable hiddenAliasOwner(Variable containerFace, Variable element) {
        if (NO_HIDDEN_ALIAS) return null;
        Variable container = firstRealVariable(containerFace);
        if (!(container instanceof FieldReference)) return null;
        ParameterizedType elementType = element.parameterizedType();
        if (elementType == null || elementType.isTypeParameter()) return null;
        if (elementType.bestTypeInfo() == null) return null;
        Variable v = container;
        while (v instanceof FieldReference fr) {
            Variable owner = fr.scopeVariable();
            if (owner == null || owner.parameterizedType() == null) return null;
            TypeInfo ownerType = owner.parameterizedType().bestTypeInfo();
            if (ownerType != null && hasCompatibleField(ownerType, fr.fieldInfo(), elementType)) {
                return owner;
            }
            v = owner;
        }
        return null;
    }

    public static final boolean NO_HIDDEN_ALIAS = System.getenv("NOHIDDENALIAS") != null;

    private static boolean hasCompatibleField(TypeInfo ownerType, FieldInfo except, ParameterizedType elementType) {
        return Stream.concat(Stream.of(ownerType), ownerType.superTypesExcludingJavaLangObject().stream())
                .flatMap(t -> t.fields().stream())
                .filter(f -> !f.isStatic() && !f.equals(except))
                .map(FieldInfo::type)
                .anyMatch(ft -> !ft.isTypeParameter() && ft.arrays() == elementType.arrays()
                                && mayBeSameObject(ft, elementType));
    }

    /*
     On the erasure, assignable one way or the other; Object is compatible with everything. For the same generic type,
     the type arguments must agree as well: a 'Map<String, Object>' taken out of one map field is not the field
     'Map<String, List<String>> tags' (TestMapValueModification). A type parameter or wildcard argument agrees with
     anything; for two different types (a subtype pair) the arguments are not compared.
     */
    private static boolean mayBeSameObject(ParameterizedType a, ParameterizedType b) {
        TypeInfo ta = a.bestTypeInfo();
        TypeInfo tb = b.bestTypeInfo();
        if (ta == null || tb == null) return true;
        if (ta.isJavaLangObject() || tb.isJavaLangObject()) return true;
        if (ta.equals(tb)) return argumentsAgree(a, b);
        return ta.superTypesExcludingJavaLangObject().contains(tb) || tb.superTypesExcludingJavaLangObject().contains(ta);
    }

    private static boolean argumentsAgree(ParameterizedType a, ParameterizedType b) {
        List<ParameterizedType> pa = a.parameters();
        List<ParameterizedType> pb = b.parameters();
        if (pa.isEmpty() || pb.isEmpty() || pa.size() != pb.size()) return true; // raw: no evidence
        for (int i = 0; i < pa.size(); i++) {
            ParameterizedType x = pa.get(i);
            ParameterizedType y = pb.get(i);
            if (x.isTypeParameter() || y.isTypeParameter() || x.wildcard() != null || y.wildcard() != null) continue;
            if (x.arrays() != y.arrays()) return false;
            TypeInfo tx = x.bestTypeInfo();
            TypeInfo ty = y.bestTypeInfo();
            if (tx == null || ty == null) continue;
            if (!tx.equals(ty) || !argumentsAgree(x, y)) return false;
        }
        return true;
    }

    /**
     * On by default since CodeLaser/maddi-mod#116 (CodeLaser/maddi#202, engine work list O2 option 2): see
     * {@link #dispatchUnion}. A non-modifying verdict on a method may not stand when an override modifies: a call
     * through it can reach the override. Gate {@code NODISPATCHUNION=1} restores option D, a concrete method keeping
     * its own body's verdict. Not final, so that a test can switch it for one analysis.
     */
    public static volatile boolean DISPATCH_UNION = System.getenv("NODISPATCHUNION") == null;

    /**
     * Under {@link #DISPATCH_UNION}: a concrete, overridable source method that source methods override. A call
     * through it may reach any of them, so its receiver and parameter modification is its own body's AND their
     * union (the link computer folds it in, {@code copyModificationsIntoMethod}). Unlike a placeholder its body
     * counts, and everything but modification stays its own. A non-virtual {@code super.m()} call reads the union
     * too: optimism traded for a little pessimism.
     */
    public static boolean dispatchUnion(MethodInfo methodInfo) {
        return DISPATCH_UNION && !methodInfo.isAbstract() && !methodInfo.isStatic() && !methodInfo.isConstructor()
               && !methodInfo.isFinal() && !methodInfo.access().isPrivate() && !isThrowOnlyPlaceholder(methodInfo)
               && !methodInfo.analysis().getOrDefault(PropertyImpl.IMPLEMENTATIONS,
                ValueImpl.SetOfMethodInfoImpl.EMPTY).isEmpty();
    }

    /**
     * Under {@link #dispatchUnion}: does one of the method's implementations modify its receiver (structurally, when
     * {@code structural})? An undecided implementation counts as modifying, as in the abstract-method fold; the
     * verdicts only go from modified to unmodified, so a later iteration lifts it once the implementation is decided.
     */
    public static boolean implementationModifies(MethodInfo methodInfo, boolean structural) {
        for (MethodInfo implementation : implementations(methodInfo)) {
            Value.Bool deep = implementation.analysis().getOrDefault(PropertyImpl.NON_MODIFYING_METHOD,
                    ValueImpl.BoolImpl.FALSE);
            Value.Bool v = structural ? implementation.analysis().getOrDefault(
                    PropertyImpl.STRUCTURALLY_NON_MODIFYING_METHOD, deep) : deep;
            if (!v.isTrue()) return true;
        }
        return false;
    }

    /** As {@link #implementationModifies}, for the parameter at {@code index}. */
    public static boolean implementationModifiesParameter(MethodInfo methodInfo, int index, boolean structural) {
        for (MethodInfo implementation : implementations(methodInfo)) {
            if (index >= implementation.parameters().size()) continue;
            ParameterInfo ip = implementation.parameters().get(index);
            Value.Bool deep = ip.analysis().getOrDefault(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.FALSE);
            Value.Bool v = structural ? ip.analysis().getOrDefault(PropertyImpl.STRUCTURALLY_UNMODIFIED_PARAMETER, deep)
                    : deep;
            if (!v.isTrue()) return true;
        }
        return false;
    }

    private static Iterable<MethodInfo> implementations(MethodInfo methodInfo) {
        return methodInfo.analysis().getOrDefault(PropertyImpl.IMPLEMENTATIONS, ValueImpl.SetOfMethodInfoImpl.EMPTY)
                .methodInfoSet();
    }

    public static boolean acceptModificationLink(Variable from, Variable to) {
        return isVirtualModification(from) == isVirtualModification(to);
    }

    public static boolean atSameLevel(String i0, String i1) {
        int d0 = i0.lastIndexOf(DOT);
        int d1 = i1.lastIndexOf(DOT);
        return d0 == -1 && d1 == -1
               || d0 > 0 && d1 > 0 && i0.substring(0, d0).equals(i1.substring(0, d1));
    }

    public static String endOf(String index) {
        int i = index.lastIndexOf('.');
        if (i < 0) return "~";
        return index.substring(0, i) + ".~";
    }

    public static Stream<FieldInfo> fieldsOf(Variable v) {
        if (v instanceof FieldReference fr) {
            Stream<FieldInfo> sub = fr.scopeVariable() != null ? fieldsOf(fr.scopeVariable()) : Stream.empty();
            return Stream.concat(sub, Stream.of(fr.fieldInfo()));
        }
        return Stream.of();
    }

    public static Stream<TypeInfo> realTypeStream(Variable v) {
        return switch (v) {
            case null -> Stream.empty();
            case FieldReference fr -> {
                TypeInfo owner;
                if (virtual(fr.fieldInfo())) {
                    owner = null;
                } else {
                    TypeParameter typeParameter = fr.fieldInfo().type().typeParameter();
                    if (typeParameter != null
                        && typeParameter.getIndex() < fr.scope().parameterizedType().parameters().size()) {
                        // return the concrete value for types like SetOnce<T>
                        owner = fr.scope().parameterizedType().parameters().get(typeParameter.getIndex()).typeInfo();
                    } else {
                        owner = fr.fieldInfo().owner();
                    }
                }
                yield Stream.concat(Stream.ofNullable(owner), realTypeStream(fr.scopeVariable()));
            }
            case DependentVariable dv -> realTypeStream(dv.arrayVariable());
            default -> Stream.of();
        };
    }

    public static Iterable<Variable> goUp(Variable variable) {
        return new Iterable<>() {
            @Override
            public @NotNull Iterator<Variable> iterator() {
                return new Iterator<>() {
                    Variable v = variable;

                    @Override
                    public boolean hasNext() {
                        return v != null;
                    }

                    @Override
                    public Variable next() {
                        Variable rv = v;
                        if (v instanceof FieldReference fr && fr.scopeVariable() != null) {
                            v = fr.scopeVariable();
                        } else if (v instanceof DependentVariable dv) {
                            v = dv.arrayVariable();
                        } else {
                            v = null;
                        }
                        return rv;
                    }
                };
            }
        };
    }

    public static boolean hasVirtualFields(Variable v) {
        if (v instanceof ReturnVariable rv) {
            return rv.methodInfo().isAbstract() || rv.methodInfo().typeInfo().compilationUnit().externalLibrary();
        }
        if (v instanceof ParameterInfo pi) {
            return pi.methodInfo().isAbstract() || pi.methodInfo().typeInfo().compilationUnit().externalLibrary();
        }
        TypeInfo typeInfo;
        if (v.parameterizedType().typeInfo() != null) {
            typeInfo = v.parameterizedType().typeInfo();
        } else if (v.parameterizedType().typeParameter() != null) {
            typeInfo = v.parameterizedType().typeParameter().typeInfo();
        } else {
            return false; // wildcard type
        }
        return typeInfo.isAbstract() || typeInfo.compilationUnit().externalLibrary();
    }

    /**
     * all
     *
     * @param scope an index designating the scope (of a variable)
     * @param index an index
     * @return true when the index is in the scope
     */
    public static boolean inScopeOf(String scope, String index) {
        if (BEFORE_METHOD.equals(scope)) return true;
        int dashScope = Math.max(scope.lastIndexOf(DASH), scope.lastIndexOf(PLUS));
        if (dashScope >= 0) {
            // 0-E -> in scope means starting with 0
            String sub = scope.substring(0, dashScope);
            return index.startsWith(sub);
        }
        int lastDotScope = scope.lastIndexOf(DOT);
        if (lastDotScope < 0) {
            // scope = 3 --> 3.0.0 ok, 3 ok, 4 ok
            return index.compareTo(scope) >= 0;
        }
        // scope = 3.0.2 --> 3.0.3 ok, 3.0.2.0.0 OK,  but 3.1.3 is not OK; 4 is not OK
        String withoutDot = scope.substring(0, lastDotScope);
        if (!index.startsWith(withoutDot)) return false;
        return index.compareTo(scope) >= 0;
    }

    public static boolean isContainerType(TypeInfo typeInfo) {
        return typeInfo.simpleName().startsWith("§");
    }

    public static boolean isPrimary(Variable variable) {
        return variable == primary(variable);
    }

    public static boolean isSlice(Variable v) {
        return v instanceof DependentVariable dv && dv.indexExpression() instanceof IntConstant ic && ic.constant() < 0;
    }

    public static boolean isVirtualModification(Variable variable) {
        return variable instanceof FieldReference fr && isVirtualModificationField(fr.fieldInfo());
    }

    public static boolean isVirtualModificationField(FieldInfo fieldInfo) {
        return fieldInfo.name().startsWith("§m") && fieldInfo.type().typeInfo() != null
               && "java.util.concurrent.atomic.AtomicBoolean".equals(fieldInfo.type().typeInfo().fullyQualifiedName());
    }

    public static LocalVariable lvPrimaryOrNull(Variable variable) {
        if (variable instanceof LocalVariable lv) return lv;
        if (primary(variable) instanceof LocalVariable lv) return lv;
        return null;
    }

    public static ParameterInfo parameterPrimaryOrNull(Variable variable) {
        if (primary(variable) instanceof ParameterInfo pi) return pi;
        return null;
    }

    /**
     * The method {@code levels} lambdas up from {@code methodInfo}: the target of a Kotlin non-local return with
     * {@code ReturnStatement.exitLevels() == levels}. {@code methodInfo} itself for 0, {@code null} when the chain
     * ends before.
     */
    public static MethodInfo enclosingMethod(MethodInfo methodInfo, int levels) {
        MethodInfo m = methodInfo;
        for (int i = 0; i < levels && m != null; i++) {
            m = lambdaContainer(m);
        }
        return m;
    }

    /** Whether {@code outer} is {@code inner} or a method whose body (through lambdas) contains {@code inner}. */
    public static boolean isOrEncloses(MethodInfo outer, MethodInfo inner) {
        for (MethodInfo m = inner; m != null; m = lambdaContainer(m)) {
            if (m == outer) return true;
        }
        return false;
    }

    /**
     * The method whose body holds the lambda whose SAM is {@code lambdaMethod}. {@code TypeInfo.enclosingMethod()}
     * names the SOURCE method for a lambda nested in a lambda (the Kotlin front end converts the whole body against
     * that method); the lambda in between is found in that method's body.
     */
    private static MethodInfo lambdaContainer(MethodInfo lambdaMethod) {
        MethodInfo source = lambdaMethod.typeInfo().enclosingMethod();
        if (source == null || source.methodBody() == null) return source;
        MethodInfo found = lambdaContainer(source.methodBody(), lambdaMethod, source);
        return found == null ? source : found;
    }

    private static MethodInfo lambdaContainer(Element root,
                                              MethodInfo lambdaMethod,
                                              MethodInfo current) {
        MethodInfo[] found = {null};
        root.visit(e -> {
            if (found[0] != null) return false;
            if (e instanceof Lambda lambda) {
                if (lambda.methodInfo() == lambdaMethod) {
                    found[0] = current;
                } else if (lambda.methodInfo().methodBody() != null) {
                    found[0] = lambdaContainer(lambda.methodInfo().methodBody(), lambdaMethod, lambda.methodInfo());
                }
                return false;
            }
            return true;
        });
        return found[0];
    }

    public static Variable oneBelowThis(Variable v) {
        if (v instanceof FieldReference fr && fr.scopeVariable() != null && !fr.scopeIsThis()) {
            return oneBelowThis(fr.scopeVariable());
        }
        if (v instanceof DependentVariable dv) {
            return oneBelowThis(dv.arrayVariable());
        }
        return v;
    }

    public static Variable primary(Variable variable) {
        if (variable instanceof FieldReference fr) {
            if (fr.scopeVariable() != null
                // accept this.§xs, but not this.v.§xs
                // see e.g. TestPrefix,3 for the this.§xs situation
                && (!(fr.scopeVariable() instanceof This) || Util.virtual(fr.fieldInfo()))) {
                return primary(fr.scopeVariable());
            }
        }
        if (variable instanceof DependentVariable dv) {
            return primary(dv.arrayVariable());
        }
        return variable;
    }

    public static Variable firstRealVariable(Variable variable) {
        if (variable instanceof FieldReference fr
            && fr.scopeVariable() != null
            && Util.virtual(fr.fieldInfo())) {
            return firstRealVariable(fr.scopeVariable());
        }
        if (variable instanceof DependentVariable dv
            && dv.indexExpression() instanceof IntConstant ic
            && ic.constant() < 0) {
            // slices
            return firstRealVariable(dv.arrayVariable());
        }
        return variable;
    }

    // to avoid TimSort problems
    public static int isPartOfComparator(Variable v1, Variable v2) {
        if (v1 instanceof FieldReference fr1 && v2 instanceof FieldReference fr2) {
            int c = fr1.fieldInfo().simpleName().compareTo(fr2.fieldInfo().simpleName());
            if (c != 0) return c;
            if (fr1.scopeVariable() != null && fr2.scopeVariable() != null) {
                return isPartOfComparator(fr1.scopeVariable(), fr2.scopeVariable());
            }
        }
        if (v1 instanceof DependentVariable dv1 && v2 instanceof DependentVariable dv2) {
            int c = isPartOfComparator(dv1.arrayVariable(), dv2.arrayVariable());
            if (c != 0) return c;
        }
        return v1.fullyQualifiedName().compareTo(v2.fullyQualifiedName());
    }

    public static boolean isPartOf(Variable base, Variable sub) {
        if (base.equals(sub)) return true;
        if (sub instanceof FieldReference fr) {
            if (fr.scopeVariable() != null) {
                return isPartOf(base, fr.scopeVariable());
            }
        }
        if (sub instanceof DependentVariable dv) {
            return isPartOf(base, dv.arrayVariable());
        }
        return false;
    }

    // iterative, no streams: the recursive concat+collect version allocated a stream pipeline and a set PER
    // SCOPE LEVEL, and this helper runs inside FollowGraph/MakeGraph hot loops (11.6% of a corpus run's
    // allocations, async-profiler round 2). Callers only read the result; do not mutate it.
    /**
     * False when one of the two is a REAL field path (or array access) of the other, e.g. {@code x} and
     * {@code x.f}, {@code x.f} and {@code x.f.g}; the virtual faces ({@code x} and {@code x.§m}, {@code x.§$s})
     * are the same real variable and do not count. An identity or assignment of that shape ('x's field holds x')
     * is never an execution fact of the link engine: it only arises as a composition across the alternatives
     * of a join, and it is the seed of unbounded field-path growth.
     */
    public static boolean notOwnFieldPath(Variable v1, Variable v2) {
        Variable r1 = firstRealVariable(v1);
        Variable r2 = firstRealVariable(v2);
        if (r1.equals(r2)) return true;
        return !scopeVariables(r1).contains(r2) && !scopeVariables(r2).contains(r1);
    }

    public static Set<Variable> scopeVariables(Variable variable) {
        Variable scope = scopeOrArray(variable);
        if (scope == null) return Set.of();
        Set<Variable> result = new HashSet<>();
        while (scope != null) {
            result.add(scope);
            scope = scopeOrArray(scope);
        }
        return result;
    }

    private static Variable scopeOrArray(Variable v) {
        if (v instanceof FieldReference fr) return fr.scopeVariable();
        if (v instanceof DependentVariable dv) return dv.arrayVariable();
        return null;
    }

    public static Stream<Variable> variableAndScopes(Variable variable) {
        if (variable instanceof FieldReference fr && fr.scopeVariable() != null) {
            return Stream.concat(variableAndScopes(fr.scopeVariable()), Stream.of(variable));
        }
        if (variable instanceof DependentVariable dv && dv.arrayVariable() != null) {
            return Stream.concat(variableAndScopes(dv.arrayVariable()), Stream.of(variable));
        }
        return Stream.of(variable);
    }

    public static String simpleName(Variable variable) {
        return simpleName(variable, Set.of());
    }

    public static String simpleName(Variable variable, Set<Variable> modified) {
        assert modified != null;
        assert variable != null;
        return switch (variable) {
            case ParameterInfo pi -> pi.index() + ":" + pi.name() + (modified.contains(pi) ? "*" : "");
            case ReturnVariable rv -> rv.methodInfo().name();
            case FieldReference fr -> {
                boolean frModified = modified.contains(fr);
                String scope = fr.scopeVariable() != null
                        ? simpleName(fr.scopeVariable(), frModified ? Set.of() : modified)
                        : fr.scope().toString();
                yield scope + "." + fr.fieldInfo().name() + (frModified ? "*" : "");
            }
            case DependentVariable dv -> {
                boolean dvModified = modified.contains(dv);
                String index = dv.indexVariable() != null
                        ? simpleName(dv.indexVariable(), dvModified ? Set.of() : modified)
                        : dv.indexExpression().toString();
                String simpleArrayVar;
                if (dv.arrayVariable() != null) simpleArrayVar = simpleName(dv.arrayVariable(), modified);
                else simpleArrayVar = dv.arrayExpression().toString();
                yield simpleArrayVar + "[" + index + "]" + (dvModified ? "*" : "");
            }
            default -> variable + (modified.contains(variable) ? "*" : "");
        };
    }


    // 3.0.0-E, +I
    public static String stage(String assignmentId) {
        Matcher m = StatementIndex.STAGE_PATTERN.matcher(assignmentId);
        if (m.matches()) return m.group(2);
        throw new UnsupportedOperationException();
    }

    public static String stripStage(String index) {
        Matcher m = StatementIndex.STAGE_PATTERN.matcher(index);
        if (m.matches()) return m.group(1);
        return index;
    }

    // add a character so that we're definitely beyond this index
    public static String beyond(String index) {
        return index + END;
    }

    public static boolean virtual(FieldInfo fieldInfo) {
        return fieldInfo.name().startsWith("§");
    }

    /**
     * A real field of THIS object (reached through {@code this}) whose type, as seen from here, is an unbound type
     * parameter: the holder's own hidden content (road to immutability 045/080). The holder cannot modify such a
     * value -- it can call only java.lang.Object's methods on it -- so handing it to a {@code @Modified} parameter is
     * not a modification of the field or of its holder: {@code f.apply(this._1)} in vavr's {@code Tuple2.map}.
     * <p>
     * The CONCRETE type of the reference decides, not the field's declaration: {@code this.box.t} is hidden content
     * when {@code box : Box<T1>}, accessible content when {@code box : Box<StringBuilder>}. And only fields of THIS:
     * {@code src.t} of a parameter {@code Box<X> src} is the caller's object, and a type-parameter PARAMETER handed
     * over stays modified, because that is still the one channel that carries a concrete function's modification
     * back to the caller's argument ({@code TestHiddenContentToModifiedArgument}).
     */
    /**
     * A real field DECLARED with an unbound type parameter ({@code public final T1 _1}): its object is hidden content
     * of the owner (road to immutability 045), so the FIELD is never modified, whatever some code does to the object
     * in one instance's field -- that is exactly what {@code @Immutable(hc=true)} states. The VARIABLE {@code t._1} in
     * that code is still modified, and so is its scope {@code t}: that is the channel to the code's caller. What must
     * not happen is the field NODE carrying it to every value ever stored in that field: vavr's
     * {@code CheckedFunction2.tupled()}, {@code t -> apply(t._1, t._2)}, marked {@code Tuple2._2} modified, and the
     * field-to-constructor-parameter rule took it through {@code Tuple.of} to {@code List.Cons.tail}.
     */
    public static boolean isHiddenContentFieldDeclaration(FieldInfo fieldInfo) {
        return !virtual(fieldInfo) && fieldInfo.type().isUnboundTypeParameter();
    }

    public static boolean isHiddenContentField(Variable v) {
        return v instanceof FieldReference fr
               && !virtual(fr.fieldInfo())
               && fr.scopeIsRecursivelyThis()
               && fr.parameterizedType().isUnboundTypeParameter();
    }

    public static boolean virtual(Variable v) {
        if (v instanceof FieldReference fr) {
            return virtual(fr.fieldInfo());
        }
        if (v instanceof DependentVariable dv) {
            return virtual(dv.arrayVariable()) ||
                   dv.indexExpression() instanceof IntConstant ic && ic.constant() < 0;
        }
        return false;
    }

    /**
     * A type a caller passes a FUNCTION as: a functional interface, except one whose single abstract method is
     * {@code iterator()}. {@code java.lang.Iterable} and Kotlin's {@code Sequence} are functional interfaces by shape
     * but containers by use; read as a supplier of an Iterator, a static {@code toList(Iterable)} or
     * {@code first(Sequence)} linked to nothing (CodeLaser/maddi#78). {@link #needsVirtual} still uses the shape (see its note).
     */
    public static boolean isFunctionType(ParameterizedType pt) {
        if (!pt.isFunctionalInterface()) return false;
        MethodInfo sam = pt.typeInfo().singleAbstractMethod();
        return sam == null || !("iterator".equals(sam.name()) && sam.parameters().isEmpty());
    }

    public static boolean needsVirtual(ParameterizedType pt) {
        if (pt.typeParameter() != null && pt.arrays() > 0) return true;
        // NOTE: this excludes ALL functional interfaces, not just java.util.function (which is what
        // VirtualFieldComputer.compute() does) -- see virtual-fields.md #3. It looks inconsistent, but it is
        // load-bearing: functional-interface values are handled via the SAM/lambda linking path, not via
        // virtual-field hidden content, and aligning this to compute() breaks TestModificationFunctional
        // (modification propagation through custom functional interfaces such as ThrowingFunction).
        if (pt.isFunctionalInterface()) return false;
        TypeInfo best = pt.bestTypeInfo();
        return best != null && (best.isAbstract() || best.compilationUnit().externalLibrary());
    }
}
