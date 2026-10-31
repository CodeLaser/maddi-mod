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

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.InlineConditional;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.SwitchExpression;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.BreakOrContinueStatement;
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.statement.ThrowStatement;
import io.codelaser.maddi.cst.api.statement.YieldStatement;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.NullAnnotations;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;

// static helpers the parts of the nullability pass share: about types, and about nodes regardless of the graph
final class Types {
    private Types() {
    }

    static boolean primitive(ParameterizedType type) {
        return type != null && type.arrays() == 0 && type.isPrimitiveExcludingVoid();
    }

    /*
     Policy.withoutTestNulls: whether a node of the graph belongs to test code, by the source set of the type that
     declares it. A node without a type (a library's, the null markers' own) is not test code.
     */
    static boolean testCode(Object node) {
        if (node instanceof Content c) return testCode(c.of());
        if (node instanceof Arg a) return testCode(a.of());
        TypeInfo type = switch (node) {
            case TypeInfo t -> t;
            case ParameterInfo pi -> pi.methodInfo().typeInfo();
            case MethodInfo mi -> mi.typeInfo();
            case FieldInfo fi -> fi.owner();
            case Local l -> l.methodInfo() == null ? null : l.methodInfo().typeInfo();
            case null, default -> null;
        };
        if (type == null) return false;
        var cu = type.primaryType().compilationUnit();
        return cu != null && cu.sourceSet() != null && cu.sourceSet().test();
    }

    static boolean isSlot(Object node) {
        return node instanceof Arg || node instanceof Content || node instanceof Keyed;
    }

    static boolean hasBody(MethodInfo m) {
        return !m.isAbstract() && m.methodBody() != null;
    }

    static NullableState explicit(List<io.codelaser.maddi.cst.api.expression.AnnotationExpression> list) {
        boolean nonNull = false;
        for (io.codelaser.maddi.cst.api.expression.AnnotationExpression ae : list) {
            String name = ae.typeInfo().simpleName();
            if (NullAnnotations.NULLABLE.contains(name)) return NullableState.NULLABLE;
            if (NullAnnotations.NON_NULL.contains(name)) nonNull = true;
        }
        return nonNull ? NullableState.NONNULL : null;
    }

    static NullableState stateOf(Info info, Property property) {
        return info.analysis().getOrDefault(property, ValueImpl.NullabilityImpl.UNSPECIFIED).state();
    }

    /**
     * The methods {@code mi} overrides; for a lambda's synthetic method (which overrides nothing in the model) the
     * functional interface's abstract method: a lambda runs where that method is called, and Kotlin types its
     * parameters by it.
     */
    static Set<MethodInfo> overrides(MethodInfo mi) {
        Set<MethodInfo> overrides = mi.overrides();
        if (!overrides.isEmpty() || mi.isConstructor() || !mi.typeInfo().isAnonymous()
            || mi.typeInfo().interfacesImplemented().size() != 1) {
            return overrides;
        }
        TypeInfo functional = mi.typeInfo().interfacesImplemented().getFirst().typeInfo();
        MethodInfo sam = functional == null ? null : functional.singleAbstractMethod();
        if (sam == null || sam == mi || sam.parameters().size() != mi.parameters().size()) return overrides;
        return Set.of(sam);
    }

    static boolean visibleOutside(MethodInfo mi) {
        TypeInfo owner = mi.typeInfo();
        boolean access = mi.access().isPublic() || mi.access().isProtected() && extensible(owner);
        return access && visibleOutside(owner);
    }

    static boolean visibleOutside(TypeInfo ti) {
        if (ti.isAnonymous()) return false;
        if (ti.compilationUnitOrEnclosingType().isLeft()) return ti.access().isPublic();
        TypeInfo enclosing = ti.compilationUnitOrEnclosingType().getRight();
        boolean access = ti.access().isPublic() || ti.access().isProtected() && extensible(enclosing);
        return access && visibleOutside(enclosing);
    }

    static boolean extensible(TypeInfo ti) {
        return !ti.isFinal() && !ti.isSealed() && !ti.typeNature().isEnum() && !ti.typeNature().isRecord();
    }

    static TypeInfo writer(MethodInfo mi) {
        return mi == null ? null : mi.typeInfo();
    }

    static boolean isTypeVariable(ParameterizedType pt) {
        return pt.typeParameter() != null && pt.arrays() == 0;
    }

    // no type variable among the type arguments (or an array's element), at any depth
    static boolean concreteArguments(ParameterizedType pt) {
        if (pt.arrays() > 0) return concreteArguments(pt.componentType()) && pt.componentType().typeParameter() == null;
        for (ParameterizedType a : pt.parameters()) {
            if (a.typeParameter() != null || !concreteArguments(a)) return false;
        }
        return true;
    }

    static boolean isBoxedVoid(ParameterizedType pt) {
        return pt.arrays() == 0 && pt.typeInfo() != null && "java.lang.Void".equals(pt.typeInfo().fullyQualifiedName());
    }

    static boolean isMethodTypeVariable(ParameterizedType pt) {
        return pt.typeParameter() != null && pt.typeParameter().isMethodTypeParameter();
    }

    // a type variable of a class ('E' in Factory<E>), also as the bound of a wildcard ('? extends E')
    static boolean isClassTypeVariable(ParameterizedType pt) {
        return pt.typeParameter() != null && pt.arrays() == 0 && !pt.typeParameter().isMethodTypeParameter();
    }

    static boolean isExtendsWildcard(ParameterizedType pt) {
        return pt.wildcard() != null && pt.wildcard().isExtends();
    }

    // the index of the owner's type parameter that is the field's type ('T t' in 'Box<T>'); -1 otherwise
    static int classTypeVariable(FieldInfo fi) {
        ParameterizedType type = fi.type();
        if (type.arrays() > 0 || type.typeParameter() == null || type.typeParameter().isMethodTypeParameter()) return -1;
        return type.typeParameter().getOwner().isLeft() && type.typeParameter().getOwner().getLeft() == fi.owner()
                ? type.typeParameter().getIndex() : -1;
    }

    // '@NullSource' / '@NullAndEmptySource' (org.junit.jupiter.params.provider) on a parameterized test
    static boolean junitNullSource(MethodInfo mi) {
        return mi.annotations().stream().anyMatch(a -> a.typeInfo() != null
                && (a.typeInfo().simpleName().equals("NullSource")
                    || a.typeInfo().simpleName().equals("NullAndEmptySource")));
    }

    static boolean mentions(Expression e, Variable v) {
        boolean[] found = {false};
        e.visit(x -> {
            if (x instanceof VariableExpression ve && sameVariable(ve.variable(), v)) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    /*
     The variable a link names against the one an expression reads. An element is named by the link engine with its
     own index ('arr[1]' for the 'arr[i]' of 'val = arr[i]' in a loop), so an element of the same array counts as the
     same variable whatever the index: the pass keeps one Content per array anyway. Without it the direct link
     'val ← arr[1]' was never observed in its statement, and the element's null did not reach 'val' (fernflower
     SFormsFastMapDirect.removeAllFields, 'val val = arr!![i]!!', after 23f8bf65 dropped the derived 'val ∈ arr').
     */
    static boolean sameVariable(Variable read, Variable linked) {
        if (read.equals(linked)) return true;
        return read instanceof DependentVariable r && linked instanceof DependentVariable l
               && sameVariable(r.arrayVariable(), l.arrayVariable());
    }

    // a return, throw, yield, break or continue anywhere in the statement, outside lambdas and nested classes
    static boolean mayLeave(Statement statement) {
        boolean[] found = {false};
        statement.visit(e -> {
            if (e instanceof Lambda || e instanceof ConstructorCall cc && cc.anonymousClass() != null) return false;
            if (e instanceof ReturnStatement || e instanceof ThrowStatement || e instanceof YieldStatement
                || e instanceof BreakOrContinueStatement) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    static boolean isVariable(Expression e, Variable v) {
        return NonNullFacts.unwrap(e) instanceof VariableExpression ve && ve.variable().equals(v);
    }

    static String where(MethodInfo mi) {
        return mi == null ? "a field initializer" : mi.fullyQualifiedName();
    }

    /*
     The values an expression may evaluate to: a conditional's branches, a switch expression's arms ('-> v', and each
     'yield v' of a block arm), recursively; else the expression itself. Fernflower's 'MatchEngine': 'value = switch
     (property) { case STATEMENT_TYPE -> stat_type.get(strValue); ... }'.
     */
    static List<Expression> alternatives(Expression value) {
        List<Expression> out = new ArrayList<>();
        alternatives(value, out);
        return out;
    }

    static void alternatives(Expression value, List<Expression> out) {
        if (value == null) return;
        switch (NonNullFacts.unwrap(value)) {
            case InlineConditional ic -> {
                alternatives(ic.ifTrue(), out);
                alternatives(ic.ifFalse(), out);
            }
            case SwitchExpression se -> se.entries().forEach(entry -> {
                Statement arm = entry.statement();
                if (arm instanceof Block block) {
                    block.visit(e -> {
                        if (e instanceof YieldStatement ys) alternatives(ys.expression(), out);
                        // a nested switch expression's yields are its own
                        return !(e instanceof Lambda) && !(e instanceof SwitchExpression);
                    });
                } else if (arm instanceof ExpressionAsStatement || arm instanceof YieldStatement) {
                    alternatives(arm.expression(), out);
                }
            });
            default -> out.add(value);
        }
    }

    /*
     The receiver's type argument that the callee's class type variable 'index' stands for. The same class (or one
     with as many type parameters, ArrayList<E> -> List<E>): the same position. Otherwise through the receiver's
     supertype: on a 'VBStyleCollection<Statement, Integer>', which extends ArrayList<E>, 'get' returns ArrayList's E,
     the receiver's argument 0 (fernflower 'stat.getStats().get(0)'). -1 when it maps to no argument of the receiver.
     */
    static int slotIndex(ParameterizedType receiverType, TypeInfo calleeClass, int index) {
        int arity = calleeClass.typeParameters().size();
        if (receiverType.arrays() > 0 || index >= arity) return -1;
        TypeInfo receiverClass = receiverType.typeInfo();
        if (receiverClass == calleeClass || receiverClass == null || receiverType.parameters().size() == arity) {
            return index < receiverType.parameters().size() ? index : -1;
        }
        ParameterizedType formal = receiverClass.asParameterizedType();
        ParameterizedType asSuper = formal.concreteSuperType(calleeClass.asParameterizedType());
        if (asSuper == null || index >= asSuper.parameters().size()) return -1;
        ParameterizedType argument = asSuper.parameters().get(index);
        if (argument.arrays() > 0 || argument.typeParameter() == null || argument.typeParameter().isMethodTypeParameter()
            || argument.typeParameter().getOwner().getLeft() != receiverClass) return -1;
        int j = argument.typeParameter().getIndex();
        return j < receiverType.parameters().size() ? j : -1;
    }

    // 'm(array)' for 'm(String... xs)': the one argument in the varargs position is itself the array
    static boolean passesTheArray(List<Expression> arguments, List<ParameterInfo> parameters, int i) {
        if (arguments.size() != parameters.size()) return false;
        if (arguments.get(i) instanceof NullConstant) return true; // 'm((String[]) null)' would be a cast; javac warns
        ParameterizedType type = arguments.get(i).parameterizedType();
        return type != null && type.arrays() == parameters.get(i).parameterizedType().arrays();
    }

    static boolean isSubtype(TypeInfo sub, TypeInfo sup) {
        return sub == sup || sub.superTypesExcludingJavaLangObject().contains(sup);
    }
}
