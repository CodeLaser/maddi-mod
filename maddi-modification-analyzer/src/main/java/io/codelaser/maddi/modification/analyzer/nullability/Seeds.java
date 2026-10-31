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

import io.codelaser.maddi.cst.api.expression.ArrayInitializer;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.BinaryOperator;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.SwitchExpression;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.BreakOrContinueStatement;
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.ForEachStatement;
import io.codelaser.maddi.cst.api.statement.ForStatement;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * Where null enters the graph: a seed is a node that may be null by itself, with its origin (the cause chains end
 * there), the types whose code writes the null, and whether it is a written null (strict) or indirect evidence. Also
 * the null literals of the analysed bodies, and the array creations that a loop fills before anything reads them.
 */
final class Seeds {
    private final Trace trace;
    private final Index index;
    // Culprits: the statement the walk is at, where a seed recorded now comes from
    private final WalkSite site;
    private final boolean recordSites = io.codelaser.maddi.modification.common.util.Culprits.ENABLED;

    Seeds(Trace trace, Index index, WalkSite site) {
        this.trace = trace;
        this.index = index;
        this.site = site;
    }

    // each null literal of an analysed body, by identity: a NullConstant equals every other, a Source only compares
    // line and column; a marker whose literal is not found here (a decoded summary) is seeded where it arrives
    private final Map<Expression, MethodInfo> nullOwners = new java.util.IdentityHashMap<>();
    // array creations ('new T[n]') the next statement fills completely before anything reads them (indexFills)
    private final Set<Expression> filledCreations = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    // gate NOFIELDFILL: only a local's declaration counts as the creation the next loop fills (A/B for CodeLaser/maddi-mod#22 gap 4)
    private static final Switches.Gate FIELD_FILLS_OFF = Switches.gate("NOFIELDFILL");
    // nodes seeded by a null that is written (a literal, an argument, an initializer, an annotation), not by indirect
    // evidence only (seedLibrary): Policy.assertContentWrites. Insertion-ordered: it seeds a closure whose cause
    // chains are reported, and a Local hashes by identity, so a hash order differs between runs (maddi-mod#33)
    final Set<Object> strictSeeds = new LinkedHashSet<>();
    // per seed, the types whose code writes its null (a literal, a default value, a comparison); a null element: a
    // seed without a writer in the code (an annotation, a library contract). Read by holderFields.
    final Map<Object, List<TypeInfo>> seedWriters = new HashMap<>();
    final Map<Object, Site> seedSites = new HashMap<>();
    final Map<Object, String> seedOrigin = new LinkedHashMap<>();

    static final String NULL_ARGUMENT = "null argument in ";

    void seed(Object node, String origin) {
        seed(node, origin, null);
    }

    // writer: the type whose code writes the null; null when the seed has none (an annotation, a contract)
    void seed(Object node, String origin, TypeInfo writer) {
        if (origin.startsWith(NULL_ARGUMENT) && index.nullRejectingBodies.contains(origin.substring(NULL_ARGUMENT.length()))) {
            return;
        }
        if (node != null) {
            if (trace.why(node)) trace.log("seed", node, origin);
            seedOrigin.putIfAbsent(node, origin);
            if (recordSites && site.element != null) seedSites.putIfAbsent(node, new Site(site.method, site.element));
            strictSeeds.add(node);
            seedWriters.computeIfAbsent(node, _ -> new ArrayList<>()).add(writer);
        }
    }

    void seedIndirect(Object node, String origin) {
        seedIndirect(node, origin, null);
    }

    // indirect evidence (Policy.assertContentWrites): a library contract, a field's default value (the Kotlin
    // reading is a 'lateinit' field), a comparison with null (the nullTests heuristic). Not a null that is written.
    void seedIndirect(Object node, String origin, TypeInfo writer) {
        if (node != null) {
            if (trace.why(node)) trace.log("indirect seed", node, origin);
            seedOrigin.putIfAbsent(node, origin);
            if (recordSites && site.element != null) seedSites.putIfAbsent(node, new Site(site.method, site.element));
            seedWriters.computeIfAbsent(node, _ -> new ArrayList<>()).add(writer);
        }
    }

    // the analysed method whose body holds this null literal (a lambda's body is the lambda's method)
    MethodInfo nullOwner(Expression nullConstant) {
        return nullOwners.get(nullConstant);
    }

    void indexNullLiterals(MethodInfo mi) {
        if (mi.methodBody() == null) return;
        mi.methodBody().visit(e -> {
            if (e instanceof Lambda) return false;
            if (e instanceof NullConstant nc) nullOwners.put(nc, mi);
            return true;
        });
    }

    /*
     'new T[n]' (no initializer) holds nulls until filled: Kotlin's arrayOfNulls. With fewer dimension expressions
     than dimensions ('new T[n][]') the inner arrays are null; else the innermost reference elements are. Returns the
     depth of the Content that is null, 0 when the expression is not such a creation.
     */
    private static int arrayCreatedWithNulls(Expression value) {
        // the front end's array creation is a ConstructorCall of a synthetic constructor, with the array's type
        if (!(NonNullFacts.unwrap(value) instanceof ConstructorCall cc) || cc.arrayInitializer() != null) return 0;
        ParameterizedType type = cc.parameterizedType();
        if (type == null || type.arrays() == 0) return 0;
        List<Expression> sizes = cc.parameterExpressions().stream().filter(e -> !e.isEmpty()).toList();
        // 'new T[0]': no elements (guava's EMPTY_ARRAY)
        if (!sizes.isEmpty() && sizes.getFirst() instanceof io.codelaser.maddi.cst.api.expression.IntConstant ic
            && ic.constant() == 0) return 0;
        int dimensions = sizes.size();
        if (dimensions > 0 && dimensions < type.arrays()) return dimensions;
        boolean primitive = type.copyWithoutArrays().isPrimitiveExcludingVoid();
        return primitive ? 0 : type.arrays();
    }

    /*
     'T[] a = new T[n]; for (int i = 0; i < n; i++) { ... a[i] = v; ... }': every element is written before anything
     reads the array, so the creation leaves no null; each 'v' flows into the elements through the links. Only a
     one-dimensional creation, followed directly by the loop: from 0, while i < n (the same size expression) or
     i < a.length, one step at a time, with 'a[i] = ...' as a statement of the body itself, and no break, continue
     or return in the body, and no other assignment to i. Guava 2026-10-07: 'TypeResolver.resolveTypes' and the like.
     */
    void indexFills(Block block) {
        List<Statement> statements = block.statements();
        for (int i = 0; i < statements.size(); i++) {
            Statement statement = statements.get(i);
            {
                Variable array = null;
                Expression init = null;
                if (statement instanceof LocalVariableCreation lvc) {
                    List<LocalVariable> locals = lvc.localVariableStream().toList();
                    if (locals.size() == 1) {
                        array = locals.getFirst();
                        init = locals.getFirst().assignmentExpression();
                    }
                } else if (!FIELD_FILLS_OFF.get() && statement instanceof ExpressionAsStatement eas
                           && eas.expression() instanceof Assignment a && a.assignmentOperator() == null
                           && (a.variableTarget() instanceof FieldReference || a.variableTarget() instanceof LocalVariable)) {
                    // `this.workers = new Worker[n]; for (...) workers[i] = new Worker();` in a constructor (nacos's
                    // NacosExecuteTaskExecuteEngine, CodeLaser/maddi-mod#22 gap 4), or a local assigned after its declaration
                    array = a.variableTarget();
                    init = a.value();
                }
                if (init != null && arrayCreatedWithNulls(init) == 1
                    && NonNullFacts.unwrap(init) instanceof ConstructorCall cc
                    && (fillingLoop(statements, i, array) instanceof ForStatement loop
                        && fills(loop, array, cc.parameterExpressions().getFirst())
                        || !COUNTED_FILLS_OFF.get() && countedFill(statements, i, array, cc.parameterExpressions().getFirst()))) {
                    filledCreations.add(init);
                }
            }
            statement.subBlockStream().forEach(this::indexFills);
            statement.visit(e -> {
                if (e instanceof SwitchExpression se) {
                    se.entries().forEach(entry -> {
                        if (entry.statement() instanceof Block arm) indexFills(arm);
                    });
                }
                return !(e instanceof Block) && !(e instanceof Lambda);
            });
        }
    }

    /*
     The statement after the creation, or after the sibling creations that follow it: 'var left = new Function[n];
     var types = new JoinerType[n]; var right = new Function[n]; for (int i = 0; i < n; i++) { left[i] = ..; .. }'
     fills all three (timefold DefaultBiJoiner.reorderedEqualsFirst, whose null elements let Joiners' mapping
     parameters tolerate null, #68: 23 C# warnings, test-manager 2026-10-30). A sibling is a local of its own
     initialised with an array creation, not mentioning the array. Gate NOSIBLINGFILLS: the next statement only.
     */
    private Statement fillingLoop(List<Statement> statements, int creation, Variable array) {
        int j = creation + 1;
        while (!SIBLING_FILLS_OFF.get() && j < statements.size()
               && statements.get(j) instanceof LocalVariableCreation lvc && lvc.localVariableStream().count() == 1
               && NonNullFacts.unwrap(lvc.localVariableStream().findFirst().orElseThrow().assignmentExpression())
                       instanceof ConstructorCall sibling
               && sibling.parameterizedType() != null && sibling.parameterizedType().arrays() > 0
               && !mentionsInStatement(lvc, array)) {
            j++;
        }
        return j < statements.size() ? statements.get(j) : null;
    }

    private static final Switches.Gate SIBLING_FILLS_OFF = Switches.gate("NOSIBLINGFILLS");

    private static boolean fills(ForStatement loop, Variable array,
                                 Expression size) {
        if (loop.initializers().size() != 1 || !(loop.initializers().getFirst() instanceof LocalVariableCreation init)
            || init.localVariableStream().count() != 1) return false;
        LocalVariable index = init.localVariableStream().findFirst().orElseThrow();
        if (!(index.assignmentExpression() instanceof io.codelaser.maddi.cst.api.expression.IntConstant zero)
            || zero.constant() != 0) return false;
        if (!(NonNullFacts.unwrap(loop.expression()) instanceof BinaryOperator bo) || bo.operator() == null
            || !"<".equals(bo.operator().name()) || !isVariable(bo.lhs(), index)) return false;
        Expression bound = NonNullFacts.unwrap(bo.rhs());
        boolean sameBound = bound.equals(NonNullFacts.unwrap(size))
                            || bound instanceof io.codelaser.maddi.cst.api.expression.ArrayLength length
                               && isVariable(length.scope(), array);
        if (!sameBound || loop.updaters().size() != 1 || !(loop.updaters().getFirst() instanceof Assignment step)
            || !index.equals(step.variableTarget()) || !step.assignmentOperatorIsPlus()
            || step.prefixPrimitiveOperator() == null
               && !(step.value() instanceof io.codelaser.maddi.cst.api.expression.IntConstant one && one.constant() == 1)) {
            return false;
        }
        boolean[] escapes = {false};
        loop.block().visit(e -> {
            if (e instanceof Lambda) return false;
            // a return leaves a local array unread unless it hands the array out: 'if (x == null) return null;'
            // (timefold SolverMetricUtil.extractScore, CodeLaser/maddi-mod#27 1); a field keeps what was filled
            boolean returnsArray = e instanceof ReturnStatement rs
                                   && (!(array instanceof LocalVariable) || rs.expression() != null
                                                                            && mentions(rs.expression(), array));
            if (e instanceof BreakOrContinueStatement || returnsArray
                || e instanceof Assignment a && index.equals(a.variableTarget())) escapes[0] = true;
            return !escapes[0];
        });
        if (escapes[0]) return false;
        return loop.block().statements().stream().anyMatch(st -> st instanceof ExpressionAsStatement eas
                && eas.expression() instanceof Assignment a && a.assignmentOperator() == null
                && a.variableTarget() instanceof DependentVariable dv
                && isVariable(dv.arrayExpression(), array) && isVariable(dv.indexExpression(), index));
    }

    /*
     'T[] a = new T[c.size()]; ... int k = 0; ... for (X x : c) { ... a[k++] = v; ... }': one element per element of
     the collection, so the creation leaves no null (guava's RegularImmutableMultiset.create, whose entryArray became
     Array<ImmutableEntry<E>?> and with it IndexedImmutableSet.get's E?, worktree-maddi-all 2026-10-27). The size is
     'c.size()' or a local holding it; the statements between do not mention the array, except an
     'if (size == 0) return ...;' (no element to fill); the counter is a local declared there from 0, written only by
     the one 'a[k++] = v', a statement of the loop's own body; no break, continue, or return handing out the array.
     Gate NOCOUNTEDFILLS.
     */
    private static boolean countedFill(List<Statement> statements, int creation, Variable array, Expression size) {
        Expression sizeExpression = NonNullFacts.unwrap(size);
        Variable sizeLocal = null;
        if (sizeExpression instanceof VariableExpression sv && sv.variable() instanceof LocalVariable lv
            && lv.assignmentExpression() != null) {
            sizeLocal = lv;
            sizeExpression = NonNullFacts.unwrap(lv.assignmentExpression());
        }
        if (!(sizeExpression instanceof MethodCall mc) || !"size".equals(mc.methodInfo().name())
            || !mc.parameterExpressions().isEmpty()
            || !(NonNullFacts.unwrap(mc.object()) instanceof VariableExpression cv)) return false;
        Variable collection = cv.variable();
        Set<Variable> counters = new HashSet<>();
        for (int j = creation + 1; j < statements.size(); j++) {
            Statement st = statements.get(j);
            if (st instanceof ForEachStatement fe && isVariable(fe.expression(), collection)) {
                return countedLoop(fe, array, counters);
            }
            if (writes(st, collection) || sizeLocal != null && writes(st, sizeLocal)) return false;
            for (Variable counter : counters) if (writes(st, counter)) return false;
            if (st instanceof LocalVariableCreation lvc) {
                for (LocalVariable lv : lvc.localVariableStream().toList()) {
                    if (lv.parameterizedType().isInt() && lv.assignmentExpression() instanceof
                            io.codelaser.maddi.cst.api.expression.IntConstant zero && zero.constant() == 0) {
                        counters.add(lv);
                    }
                }
            }
            if (mentionsInStatement(st, array) && !emptyReturn(st, sizeLocal, sizeExpression)) return false;
        }
        return false;
    }

    // 'if (n == 0) return ...;' without else: the array has no element yet to fill
    private static boolean emptyReturn(Statement st, Variable sizeLocal, Expression sizeCall) {
        if (!(st instanceof io.codelaser.maddi.cst.api.statement.IfElseStatement ifs)
            || ifs.elseBlock() != null && !ifs.elseBlock().isEmpty()
            || ifs.block().statements().size() != 1 || !(ifs.block().statements().getFirst() instanceof ReturnStatement)
            || !(NonNullFacts.unwrap(ifs.expression()) instanceof BinaryOperator bo) || bo.operator() == null
            || !"==".equals(bo.operator().name())
            || !(NonNullFacts.unwrap(bo.rhs()) instanceof io.codelaser.maddi.cst.api.expression.IntConstant zero)
            || zero.constant() != 0) return false;
        Expression lhs = NonNullFacts.unwrap(bo.lhs());
        return sizeLocal != null ? isVariable(lhs, sizeLocal) : lhs.equals(sizeCall);
    }

    private static boolean countedLoop(ForEachStatement loop, Variable array, Set<Variable> counters) {
        Variable[] counter = {null};
        for (Statement st : loop.block().statements()) {
            if (st instanceof ExpressionAsStatement eas && eas.expression() instanceof Assignment a
                && a.assignmentOperator() == null && a.variableTarget() instanceof DependentVariable dv
                && isVariable(dv.arrayExpression(), array)
                && NonNullFacts.unwrap(dv.indexExpression()) instanceof Assignment step
                && Boolean.FALSE.equals(step.prefixPrimitiveOperator()) && step.assignmentOperatorIsPlus()
                && counters.contains(step.variableTarget())) {
                if (counter[0] != null) return false;
                counter[0] = step.variableTarget();
            }
        }
        if (counter[0] == null) return false;
        boolean[] escapes = {false};
        int[] steps = {0};
        loop.block().visit(e -> {
            if (e instanceof Lambda) return false;
            if (e instanceof BreakOrContinueStatement
                || e instanceof ReturnStatement rs && rs.expression() != null && mentions(rs.expression(), array)) {
                escapes[0] = true;
            }
            if (e instanceof Assignment a && counter[0].equals(a.variableTarget()) && ++steps[0] > 1) escapes[0] = true;
            return !escapes[0];
        });
        return !escapes[0];
    }

    private static boolean writes(Statement st, Variable v) {
        boolean[] found = {false};
        st.visit(e -> {
            if (e instanceof Assignment a && v.equals(a.variableTarget())) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    private static boolean mentionsInStatement(Statement st, Variable v) {
        boolean[] found = {false};
        st.visit(e -> {
            if (e instanceof VariableExpression ve && sameVariable(ve.variable(), v)) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    // gate NOCOUNTEDFILLS: a creation filled by a counted for-each keeps its null elements (A/B)
    private static final Switches.Gate COUNTED_FILLS_OFF = Switches.gate("NOCOUNTEDFILLS");

    void seedCreated(Object target, Expression value, MethodInfo mi) {
        if (target == null || value == null) return;
        int depth = filledCreations.contains(value) ? 0 : arrayCreatedWithNulls(value);
        if (depth > 0) {
            Object content = target;
            for (int d = 0; d < depth; d++) content = new Content(content);
            seed(content, "array created with null elements in " + where(mi), writer(mi));
        }
        Expression unwrapped = NonNullFacts.unwrap(value);
        ArrayInitializer initializer = unwrapped instanceof ArrayInitializer ai ? ai
                : unwrapped instanceof ConstructorCall cc ? cc.arrayInitializer() : null;
        if (initializer != null) seedInitializer(new Content(target), initializer, mi);
    }

    /*
     An array initializer, at any depth: a null element seeds the elements, a nested initializer or creation is
     walked one level down. The links have this index-precisely for a method's own arrays, but not for a field
     initializer ('int[][][] t = {{null, null}, null}'), which no method's variable data holds.
     */
    private void seedInitializer(Content elements, ArrayInitializer initializer, MethodInfo mi) {
        for (Expression element : initializer.expressions()) {
            // also an alternative of the element: '{…, monitor ? "1" : null}' (fernflower FlattenStatementsHelper)
            for (Expression e : alternatives(element)) {
                if (NonNullFacts.unwrap(e) instanceof NullConstant) {
                    seed(elements, "null in an array initializer in " + where(mi), writer(mi));
                } else {
                    seedCreated(elements, e, mi);
                }
            }
        }
    }
}
