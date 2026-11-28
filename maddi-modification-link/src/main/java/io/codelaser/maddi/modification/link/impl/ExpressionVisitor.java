package io.codelaser.maddi.modification.link.impl;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.element.RecordPattern;
import io.codelaser.maddi.cst.api.expression.*;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.statement.SwitchEntry;
import io.codelaser.maddi.cst.api.translate.TranslationMap;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.*;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.modification.common.FunctionTypes;
import io.codelaser.maddi.modification.common.util.TolerantWrite;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.link.impl.localvar.FunctionalInterfaceVariable;
import io.codelaser.maddi.modification.link.impl.localvar.IntermediateVariable;
import io.codelaser.maddi.modification.link.impl.localvar.MarkerVariable;
import io.codelaser.maddi.modification.link.impl.translate.VariableTranslationMap;
import io.codelaser.maddi.modification.link.impl.translate.VirtualFieldTranslationMapForMethodParameters;
import io.codelaser.maddi.modification.link.vf.VirtualFieldComputer;
import io.codelaser.maddi.modification.link.vf.VirtualFields;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.*;
import io.codelaser.maddi.modification.prepwork.variable.impl.LinksImpl;
import io.codelaser.maddi.modification.prepwork.callgraph.LazyCacheField;

public record ExpressionVisitor(Runtime runtime,
                                JavaInspector javaInspector,
                                LinkComputer.Options linkComputerOptions,
                                VirtualFieldComputer virtualFieldComputer,
                                LinkComputerRecursion linkComputer,
                                LinkComputerImpl.SourceMethodComputer sourceMethodComputer,
                                MethodInfo currentMethod,
                                RecursionPrevention recursionPrevention,
                                AtomicInteger variableCounter) {

    public record WriteMethodCall(Expression methodCall, Links linksFromObject) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpressionVisitor.class);
    // gate NOEXPRSCOPEFIELD: a field of an expression's result is not linked to that result (variableExpression)
    static final boolean NO_EXPRESSION_SCOPE_FIELD = Gate.isSet("NOEXPRSCOPEFIELD");
    // gate NOCALLRESULTFIELD: a call result its callee calls a field of the receiver is no genuine containment
    private static final boolean NO_CALL_RESULT_FIELD = Gate.isSet("NOCALLRESULTFIELD");
    // gate NOELEMENTOWNER: a modified receiver that is an element of a variable unknown here modifies nothing more
    private static final boolean NO_ELEMENT_OWNER = Gate.isSet("NOELEMENTOWNER");
    // gate NOCHAINSTRUCTURAL: a chained call does not take its receiver call's structural verdict (#114)
    private static final boolean NO_CHAIN_STRUCTURAL = Gate.isSet("NOCHAINSTRUCTURAL");

    // gate NOGETTERRECEIVER: an inlined getter drops its receiver expression's links and modifications
    private static final boolean NO_GETTER_RECEIVER = Gate.isSet("NOGETTERRECEIVER");
    private static final boolean NO_SUPER_THIS = Gate.isSet("NOSUPERTHIS");

    /*
     The call modifies its receiver, and the receiver is an element of a variable this method does not know: a getter
     handed it out ('C c = getClass(n)', 'getClass ∈ this.classes.§$$s[-2]'; 'getClass(n).getPool()'). A known
     container takes the modification over its own '∋' link (WriteLinksAndModification.notLinkedToModified, the
     direct 'classes.get(n)'); an unknown one has no variable to carry it, and the modification was lost
     (CodeLaser/maddi-mod#100, fernflower's StructContext.instanceOf). Its nearest known scope ('this') is modified
     instead, deep-only: what changed is an element of its hidden content. The element counterpart of #80.
     */
    private static Set<Variable> ownersOfModifiedElement(Variable objectPrimary, Links objectLinks,
                                                         Set<Variable> modified, VariableData variableData,
                                                         Stage stage) {
        if (objectPrimary == null || objectLinks == null || !modified.contains(objectPrimary)) {
            return Set.of();
        }
        Set<Variable> out = new HashSet<>();
        Iterable<Link> links = objectLinks.linkSet();
        if (objectLinks.isEmpty() && variableData != null && variableData.isKnown(objectPrimary.fullyQualifiedName())) {
            // a local holding the element: its links are in the variable data
            VariableInfo vi = variableData.variableInfo(objectPrimary, stage);
            Links ofLocal = vi == null ? null : vi.linkedVariables();
            links = ofLocal == null ? Set.of() : ofLocal.linkSet();
        }
        for (Link link : links) {
            if (link.linkNature() != LinkNatureImpl.IS_ELEMENT_OF) continue;
            Variable container = Util.firstRealVariable(link.to());
            if (container == null || variableData != null && variableData.isKnown(container.fullyQualifiedName())) {
                continue;
            }
            // 'this' stands for the receiver whether or not the body names it; without variable data (an expression
            // evaluated on its own, a return value's) only 'this' and a parameter are certainly the method's own
            Util.variableAndScopes(container)
                    .filter(v -> v != container && !v.isIgnoreModifications()
                                 && (v instanceof This || v instanceof ParameterInfo
                                     || variableData != null && variableData.isKnown(v.fullyQualifiedName())))
                    .findFirst().ifPresent(out::add);
        }
        return out;
    }

    static final Result EMPTY = new Result(LinksImpl.EMPTY, LinkedVariablesImpl.EMPTY, Map.of(), Set.of(), List.of(),
            Map.of(), Set.of(), Set.of());

    public Result visit(Expression expression, VariableData variableData, Stage stage) {
        Result r = switch (expression) {
            case VariableExpression ve -> variableExpression(ve, variableData, stage);
            case Assignment a -> assignment(variableData, stage, a);
            case MethodCall mc -> methodCall(variableData, stage, mc);
            case MethodReference mr -> methodReference(variableData, stage, mr);
            case ConstructorCall cc -> {
                if (cc.anonymousClass() != null) {
                    yield anonymousClass(variableData, stage, cc);
                }
                if (cc.arrayInitializer() != null) {
                    yield arrayInitializer(variableData, stage, cc.arrayInitializer());
                }
                yield constructorCall(variableData, stage, cc);
            }
            case Lambda lambda -> lambda(variableData, lambda);
            case Cast cast -> cast(variableData, stage, cast);
            case InstanceOf instanceOf -> instanceOf(variableData, stage, instanceOf);
            case ConstantExpression<?> ce -> constantExpression(ce);
            case InlineConditional ic -> inlineConditional(ic, variableData, stage);
            case ArrayInitializer ai -> arrayInitializer(variableData, stage, ai);
            case And and -> and.expressions().stream().map(e -> visit(e, variableData, stage))
                    .reduce(EMPTY, Result::merge);
            case Or or -> or.expressions().stream().map(e -> visit(e, variableData, stage))
                    .reduce(EMPTY, Result::merge);
            case CommaExpression ce -> ce.expressions().stream().map(e -> visit(e, variableData, stage))
                    .reduce(EMPTY, Result::merge);
            case ArrayLength al -> visit(al.scope(), variableData, stage).moveLinksToExtra();
            case EnclosedExpression ee -> visit(ee.inner(), variableData, stage);
            case UnaryOperator uo -> unaryOperator(variableData, stage, uo);
            case GreaterThanZero gt0 -> greaterThanZero(variableData, stage, gt0);
            case BinaryOperator bo -> binaryOperator(variableData, stage, bo);
            case TypeExpression _, EmptyExpression _ -> EMPTY;
            case SwitchExpression se -> switchExpression(se, variableData, stage);
            default -> {
                // CodeLaser/maddi#22: an expression kind the linker does not enumerate contributes no links; before, this threw
                // and (under fault tolerance) cost the whole method its link analysis
                LOGGER.warn("Link engine: no visitor for {} in {}; contributes no links", expression.getClass().getName(),
                        currentMethod);
                yield EMPTY;
            }
        };
        // ⛔ the return value matters: for the shared EMPTY, setEvaluated returns a copy (CodeLaser/maddi#83). Ignoring it wrote the
        // first TypeExpression ever visited into EMPTY, and every later EMPTY in the JVM carried that expression
        if (r.getEvaluated() == null) r = r.setEvaluated(expression);
        return r;
    }

    private @NotNull Result greaterThanZero(VariableData variableData, Stage stage, GreaterThanZero gt0) {
        Result r = visit(gt0.expression(), variableData, stage);
        GreaterThanZero newGt0 = runtime.newGreaterThanZero(r.getEvaluated(), gt0.allowEquals());
        return r.setEvaluated(newGt0);
    }

    private @NotNull Result unaryOperator(VariableData variableData, Stage stage, UnaryOperator uo) {
        Result result = visit(uo.expression(), variableData, stage);
        UnaryOperator newUnary = runtime.newUnaryOperator(uo.comments(), uo.source(), uo.operator(),
                result.getEvaluated(), uo.precedence());
        return result.with(LinksImpl.EMPTY).setEvaluated(newUnary);
    }

    private Result binaryOperator(VariableData variableData, Stage stage, BinaryOperator bo) {
        Result rLhs = visit(bo.lhs(), variableData, stage);
        Result rRhs = visit(bo.rhs(), variableData, stage);
        BinaryOperator evaluated = runtime.newBinaryOperatorBuilder()
                .setLhs(rLhs.getEvaluated())
                .setOperator(bo.operator()).setRhs(rRhs.getEvaluated())
                .setParameterizedType(bo.parameterizedType()).setPrecedence(bo.precedence())
                .setSource(bo.source())
                .build();
        return rLhs.merge(rRhs)
                .with(LinksImpl.EMPTY)
                .setEvaluated(evaluated);
    }

    Result arrayInitializer(VariableData variableData, Stage stage, ArrayInitializer ai) {
        List<Result> rs = ai.expressions().stream()
                .map(e -> visit(e, variableData, stage)).toList();
        // make new object of the correct array type; this will become the primary
        // then assignments per index
        int i = 0;
        LocalVariable c = IntermediateVariable.newObject(runtime, variableCounter.getAndIncrement(),
                ai.parameterizedType());
        VariableExpression cVe = runtime.newVariableExpression(c);
        LinksImpl.Builder builder = new LinksImpl.Builder(c);
        Result combined = null;
        for (Result r : rs) {
            if (combined == null) {
                combined = r;
            } else {
                combined = combined.merge(r);
            }
            if (r.links().primary() != null) {
                DependentVariable dv = runtime.newDependentVariable(cVe, runtime.newInt(i));
                builder.add(dv, LinkNatureImpl.IS_ASSIGNED_FROM, r.links().primary());
            }
            ++i;
        }
        Links links = builder.build();
        if (rs.isEmpty()) return new Result(links, LinkedVariablesImpl.EMPTY);
        return combined.with(links);
    }

    Result constantExpression(ConstantExpression<?> ce) {
        LocalVariable lv = MarkerVariable.constant(variableCounter.getAndIncrement(), ce.parameterizedType(), ce);
        return new Result(new LinksImpl.Builder(lv).build(), LinkedVariablesImpl.EMPTY)
                .addVariableRepresentingConstant(lv).setEvaluated(ce);
    }

    /*
    simple passthrough, except
    1. narrowing casts on primitives return ERASE (as for an operation on a primitive)
    2. variable casts are stored for the DOWNCAST info
     */
    private Result cast(VariableData variableData, Stage stage, Cast cast) {
        Result r = visit(cast.expression(), variableData, stage);
        if (narrowingCast(cast.expression().parameterizedType(), cast.parameterizedType())) {
            return r.with(LinksImpl.EMPTY);
        }
        // use r.getValuated() rather than cast.expression(); in this way, getters get resolved
        // see e.g. analyzer/TestCast,3
        if (r.getEvaluated() instanceof VariableExpression ve && cast.parameterizedType().typeInfo() != null) {
            return r.addCast(ve.variable(), cast.parameterizedType().typeInfo());
        }
        return r;
    }

    // long -> int etc.
    private boolean narrowingCast(ParameterizedType from, ParameterizedType to) {
        return from.isPrimitiveExcludingVoid()
               && to.isPrimitiveExcludingVoid()
               && !from.equals(to)
               && runtime.widestType(from, to).equals(from);
    }


    /*
     The binding's links -- `a ≡ b`, a record pattern's `a ≻ x` -- are links of the variables, side links of the
     expression, whose VALUE is a boolean and has none. As the value they became the links of whatever held the
     boolean: `return o instanceof U x && t == x.t` returned `←0:o`, so a caller's `a.equals(b)` was linked to `b`
     (CodeLaser/maddi-mod#155, fernflower's VarExprent.equals reached through Kotlin's `!=`).
     */
    private Result instanceOf(VariableData variableData, Stage stage, InstanceOf instanceOf) {
        if (instanceOf.patternVariable() != null) {
            Result r = visit(instanceOf.expression(), variableData, stage);
            if (r.getEvaluated() instanceof VariableExpression ve) {
                if (instanceOf.testType().typeInfo() != null) {
                    r.addCast(ve.variable(), instanceOf.testType().typeInfo());
                }
                Links.Builder linksBuilder = new LinksImpl.Builder(ve.variable());
                // a instanceof B b -> a ≡ b
                if (instanceOf.patternVariable().localVariable() != null) {
                    LocalVariable lv = instanceOf.patternVariable().localVariable();
                    if (!lv.isUnnamed()) {
                        // MEDIATED: the pattern binding re-mediates the declared type (task #39)
                        ((LinksImpl.Builder) linksBuilder).add(LinkNatureImpl.IS_ASSIGNED_TO, lv, true);
                        // 'o instanceof Set set' where o is itself a bound deconstruction component: the cast
                        // alias is the same object, hence a component of o's containers too
                        sourceMethodComputer.followGraph.graph().markPatternBindingAlias(ve.variable(), lv);
                        return r.moveLinksToExtra().with(linksBuilder.build()).moveLinksToExtra();
                    }
                } else if (instanceOf.patternVariable().recordType() != null) {
                    // a instanceof Point(Coord x, Coord y) -> x is field of a, y is field of a
                    recursivelyAddToBuilder(linksBuilder, instanceOf.patternVariable());
                    // side-band mark: these ≻ links are GENUINE containment (deconstruction components), which
                    // isInvalidFieldContainment must not drop as it does accessor-copy expansions
                    for (Link link : linksBuilder.linkSet()) {
                        sourceMethodComputer.followGraph.graph().markPatternBinding(link.from(), link.to());
                    }
                    return r.moveLinksToExtra().with(linksBuilder.build()).moveLinksToExtra();
                }
            }
            // no need to set expression, it will be lost
            return r.moveLinksToExtra(); // result is a boolean
        }
        return EMPTY;
    }

    private void recursivelyAddToBuilder(Links.Builder linksBuilder, RecordPattern recordPattern) {
        if (recordPattern.patterns() != null) {
            for (RecordPattern rp : recordPattern.patterns()) {
                recursivelyAddToBuilder(linksBuilder, rp);
            }
        }
        if (recordPattern.localVariable() != null && !recordPattern.localVariable().isUnnamed()) {
            linksBuilder.add(LinkNatureImpl.CONTAINS_AS_FIELD, recordPattern.localVariable());
        }
    }

    // Capture a method reference as a functional-interface value: wrap the referenced method's summary in a
    // FunctionalInterfaceVariable, re-homed onto the SAM. Sub-cases (see linking-manual.md §7.1): bound instance
    // ('target::add', scope is an instance), unbound instance ('M::clear', scope is the type — callee 'this' is the
    // SAM's first parameter), static ('String::valueOf'), and constructor ('R::new' — re-homed like a factory below).
    private Result methodReference(VariableData variableData, Stage stage, MethodReference mr) {
        MethodLinkedVariables mlv = linkComputer.recurseMethod(mr.methodInfo());
        Result object = visit(mr.scope(), variableData, stage);
        MethodLinkedVariables tMlv;
        if (object.links().primary() != null) {
            This thisVar = runtime.newThis(mr.methodInfo().typeInfo().asParameterizedType());
            TranslationMap tm = new VariableTranslationMap(runtime).put(thisVar, object.links().primary());
            tMlv = mlv.translate(tm);
        } else {
            tMlv = mlv;
        }
        Links newRv;
        int i = 0;
        Map<Variable, Links> map = new HashMap<>();
        if (mr.methodInfo().isConstructor()) {
            // A constructor reference's SAM returns the newly created object (the constructor's 'this'). The
            // constructor's own summary has no return value; its effect is 'param → this.field'. Re-home those onto
            // a synthetic return variable, reversed, so the SAM return links to its parameters ('rv.field ← param')
            // — the same shape a factory method ('Y wrap(Y y){return new R<>(y);}' -> 'wrap.v←0:y') produces, so
            // that e.g. stream.map(R::new) lifts like stream.map(this::wrap). See TestStreamMapSpec.
            Variable rv = IntermediateVariable.returnValue(runtime, variableCounter.getAndIncrement(),
                    mr.methodInfo().typeInfo().asParameterizedType());
            Links.Builder rvBuilder = new LinksImpl.Builder(rv);
            for (Links paramLinks : tMlv.ofParameters()) {
                ParameterInfo pi = mr.methodInfo().parameters().get(i);
                Links.Builder paramSelf = new LinksImpl.Builder(pi);
                for (Link link : paramLinks) {
                    if (rootVariable(link.to()) instanceof This actualThis) {
                        TranslationMap thisToRv = new VariableTranslationMap(runtime).put(actualThis, rv);
                        rvBuilder.add(thisToRv.translateVariableRecursively(link.to()),
                                link.linkNature().reverse(), link.from());
                    } else {
                        paramSelf.add(link.from(), link.linkNature(), link.to());
                    }
                }
                Links ps = paramSelf.build();
                if (ps.primary() != null && !ps.isEmpty()) map.put(pi, ps);
                ++i;
            }
            newRv = rvBuilder.build();
        } else {
            newRv = unboundReceiverAsSamParameter(mr, object, tMlv.ofReturnValue());
            for (Links paramLinks : tMlv.ofParameters()) {
                map.put(mr.methodInfo().parameters().get(i), paramLinks);
                ++i;
            }
        }
        if (object.links().primary() != null) {
            map.merge(object.links().primary(), object.links(), Links::merge);
            object.extra().forEach(e -> map.merge(e.getKey(), e.getValue(), Links::merge));
        }
        // A method reference whose receiver is not a caller-scope instance carries an INTERNAL 'this' whose
        // self-modifications must not leak into the caller's modified set:
        //  - an unbound instance MR ('M::clear', scope is the type): the receiver is the SAM's first parameter (the
        //    stream/collection element); its mutation maps to the source's hidden content, which LinkFunctionalInterface
        //    would have to express (it carries only links), so we conservatively drop it. See TestStreamForEachSpec.
        //  - a constructor reference ('Box::new'): the receiver is the freshly-created object; a constructor always
        //    "modifies" its own 'this' (it sets the fields), but that is internal to construction, not a caller effect.
        //    See TestSupplierSpec.
        // In both, the referenced method's own 'this' (typeInfo == mr.methodInfo().typeInfo()) is internal; filter it.
        boolean internalReceiver = object.links().primary() == null && !mr.methodInfo().isStatic();
        Set<Variable> leakedModified = internalReceiver
                ? tMlv.modified().stream()
                .filter(v -> !(Util.primary(v) instanceof This t && t.typeInfo() == mr.methodInfo().typeInfo()))
                .collect(Collectors.toUnmodifiableSet())
                : tMlv.modified();
        Set<Variable> leakedDeepOnly = deepOnlyAmong(tMlv, leakedModified);
        Result wrapped = new Result(newRv, new LinkedVariablesImpl(map)).addModified(leakedModified, leakedDeepOnly, null);
        FunctionalInterfaceVariable fiv = new FunctionalInterfaceVariable(
                runtime,
                variableCounter.getAndIncrement(),
                mr.parameterizedType(),
                wrapped);
        Links links = new LinksImpl.Builder(fiv).build();
        return new Result(links, LinkedVariablesImpl.EMPTY)
                .addModified(leakedModified, leakedDeepOnly, null);
    }

    /*
     An unbound instance method reference ('Order::customer') is applied to the SAM's first parameter, as the lambda
     'o -> o.customer()' is. Its summary speaks of the referenced method's 'this' ('customer←this.customer'), and a
     field of 'this' is a primary of its own: the lifting engine would map the whole 'this.customer' onto the source,
     as if the function were the identity (CodeLaser/maddi-mod#58). Re-homed onto the SAM's parameter, it reads
     'customer←0:t.customer', the lambda's shape.
     */
    private Links unboundReceiverAsSamParameter(MethodReference mr, Result object, Links rv) {
        MethodInfo target = mr.methodInfo();
        if (object.links().primary() != null || target.isStatic() || target.isConstructor()
            || rv == null || rv.isEmpty()) return rv;
        MethodInfo sam = mr.parameterizedType().typeInfo() == null ? null
                : mr.parameterizedType().typeInfo().singleAbstractMethod();
        if (sam == null || sam.parameters().isEmpty()) return rv;
        This thisVar = runtime.newThis(target.typeInfo().asParameterizedType());
        TranslationMap tm = new VariableTranslationMap(runtime).put(thisVar, sam.parameters().getFirst());
        return rv.translate(tm);
    }

    /** the deep-only subset of {@code variables}: deep-only in every result that has them (#25) */
    private static Set<Variable> deepOnlyAmong(List<Result> results, Set<Variable> variables) {
        Set<Variable> deepOnly = new HashSet<>();
        Set<Variable> structural = new HashSet<>();
        for (Result r : results) {
            for (Variable v : r.modified().keySet()) {
                if (!variables.contains(v)) continue;
                if (r.deepOnlyModified().contains(v)) deepOnly.add(v);
                else structural.add(v);
            }
        }
        deepOnly.removeAll(structural);
        return deepOnly;
    }

    /** the deep-only subset of {@code variables}, taken from the summary they came from (#25) */
    private static Set<Variable> deepOnlyAmong(MethodLinkedVariables mlv, Set<Variable> variables) {
        if (mlv.deepOnlyModified().isEmpty()) return Set.of();
        return variables.stream().filter(mlv.deepOnlyModified()::contains).collect(Collectors.toUnmodifiableSet());
    }

    // the raw root of a variable, descending through both field scopes (including real, non-virtual fields, unlike
    // Util.primary) and array accesses; e.g. this.v -> this, this.a.b -> this, arr[0].f -> arr
    private static Variable rootVariable(Variable variable) {
        Variable v = variable;
        while (true) {
            if (v instanceof FieldReference fr && fr.scopeVariable() != null) v = fr.scopeVariable();
            else if (v instanceof DependentVariable dv && dv.arrayVariable() != null) v = dv.arrayVariable();
            else return v;
        }
    }

    private @NotNull Result inlineConditional(InlineConditional ic, VariableData variableData, Stage stage) {
        Result rc = visit(ic.condition(), variableData, stage);
        Result rt = visit(ic.ifTrue(), variableData, stage);
        Result rf = visit(ic.ifFalse(), variableData, stage);
        Variable newPrimary = IntermediateVariable.inlineCondition(runtime, variableCounter.getAndIncrement(),
                ic.parameterizedType());
        // we must link the new primary to both rt and rf links
        AssignLinksToLocal atl = new AssignLinksToLocal(runtime);
        Links rtChanged = atl.go(newPrimary, rt.links());
        Links rfChanged = atl.go(newPrimary, rf.links());
        Links newLinks = rtChanged.merge(rfChanged);
        // the condition's own links go to `extra`: `with(newLinks)` below replaces the main links, and a pattern
        // binding in the condition (`o instanceof T t ? t : …`) IS the condition's main links, `o → t`. Replaced, `t`
        // linked to nothing and the ternary's value lost `o` (CodeLaser/maddi#79); an `if` statement's condition keeps them.
        Result merge = rc.moveLinksToExtra().merge(rt).merge(rf);
        // ⛔ `new Result(links, extra)` keeps only those two and resets the other five — including `modified`,
        // the record of which variables a call in here modifies. It threw away every modification made by the
        // condition and both arms, so `cond ? c.add(t) : 0` left `c` unmodified. `with()` replaces the links
        // and preserves the rest, which is what `switchExpression` two methods down already did. `evaluated`
        // goes back to `ic`: `merge` inherits it from the leftmost operand, and the ternary's value is the
        // ternary, not its condition.
        return merge.with(newLinks).setEvaluated(ic);
    }

    // this part of the switchExpression code is very similar to inlineConditional
    private @NotNull Result switchExpression(SwitchExpression se, VariableData variableData, Stage stage) {
        Result rc = visit(se.selector(), variableData, stage);
        Links newLinks = null;
        Result merge = rc;
        Variable primary = IntermediateVariable.returnValue(javaInspector.runtime(),
                variableCounter.getAndIncrement(), se.parameterizedType());
        for (SwitchEntry entry : se.entries()) {
            ResultVd resultVd = evaluateSwitchEntry(entry, variableData, stage, primary);
            if (newLinks == null) newLinks = resultVd.result.links();
            else newLinks = newLinks.merge(resultVd.result.links());
            merge = merge.merge(resultVd.result);
        }

        assert newLinks != null;
        return merge.with(newLinks);
    }

    private record ResultVd(Result result, VariableData variableData) {
    }

    // this part of the switchExpression code copies from instanceof and ...
    private ResultVd evaluateSwitchEntry(SwitchEntry entry, VariableData variableData, Stage stage, Variable primary) {
        Result rc = Stream.concat(entry.conditions().stream(), Stream.ofNullable(entry.whenExpression()))
                .map(e -> visit(e, variableData, stage))
                .reduce(EMPTY, Result::merge);
        if (entry.statement() instanceof ExpressionAsStatement eas) {
            Result r = visit(eas.expression(), variableData, stage);
            Links.Builder linksBuilder = new LinksImpl.Builder(primary);
            if (r.links().primary() != null) {
                linksBuilder.add(primary, LinkNatureImpl.IS_ASSIGNED_FROM, r.links().primary());
            }
            // ⛔ the same drop as in `inlineConditional`: `new Result(links, extra)` kept the arm's links and
            // threw away its `modified`, so `case 0 -> c.add(t)` left `c` unmodified. The BLOCK arm below never
            // had the bug — it re-adds `d.modified` explicitly.
            // `evaluated` back to null as the two-arg constructor left it: switchExpression's merge keeps the
            // SELECTOR's evaluated expression, and an arm's must not displace it.
            return new ResultVd(r.with(linksBuilder.build()).merge(rc).setEvaluated(null), null);
        }
        sourceMethodComputer.startSwitchExpression(primary);
        VariableData vd = sourceMethodComputer.doBlock(entry.statementAsBlock(), variableData);
        Links yieldResult = sourceMethodComputer.endSwitchExpression();
        D d = copyLinksFromSwitchExpressionBlock(variableData, vd);

        return new ResultVd(new Result(yieldResult, new LinkedVariablesImpl(d.map))
                .merge(rc)
                .addModified(d.modified, d.deepOnly, null)
                .addCasts(d.casts), vd);
    }

    private record D(Map<Variable, Links> map, Set<Variable> modified, Set<Variable> deepOnly,
                     Map<Variable, Set<TypeInfo>> casts) {
    }

    private D copyLinksFromSwitchExpressionBlock(VariableData outer, VariableData vd) {
        Map<Variable, Links> map = new HashMap<>();
        Set<Variable> modified = new HashSet<>();
        Set<Variable> deepOnly = new HashSet<>();
        Map<Variable, Set<TypeInfo>> casts = new HashMap<>();
        for (VariableInfo vi : vd.variableInfoIterable()) {
            // don't copy: variables local to the switch expression block
            boolean accept = !(vi.variable() instanceof LocalVariable)
                             || outer != null && outer.isKnown(vi.variable().fullyQualifiedName());
            if (accept) {
                Links links = vi.linkedVariablesOrEmpty();
                if (links.primary() != null) {
                    map.merge(links.primary(), links, Links::merge);
                }
                if (vi.isModified()) {
                    modified.add(vi.variable());
                    if (vi.isStructurallyUnmodified()) deepOnly.add(vi.variable());
                }
                Set<TypeInfo> castTypes = vi.downcast();
                if (!castTypes.isEmpty()) {
                    casts.computeIfAbsent(vi.variable(), _ -> new HashSet<>()).addAll(castTypes);
                }
            }
        }
        return new D(map, modified, deepOnly, casts);
    }

    private @NotNull Result variableExpression(VariableExpression ve, VariableData variableData, Stage stage) {
        Variable v = ve.variable();
        LinkedVariables extra = LinkedVariablesImpl.EMPTY;
        if (v instanceof DependentVariable dv) {
            Result rIndex = visit(dv.indexExpression(), variableData, stage).copyLinksToExtra();
            Result rArray = visit(dv.arrayExpression(), variableData, stage);
            DependentVariable newDv;
            if (rIndex.getEvaluated() != dv.indexExpression()
                || rArray.getEvaluated() != dv.arrayExpression()
                   // but do not use the evaluated version if it is not of array type (see TestArrayVariable)
                   && rArray.getEvaluated().parameterizedType().arrays() > 0) {
                newDv = runtime.newDependentVariable(rArray.getEvaluated(), rIndex.getEvaluated());
            } else {
                newDv = dv;
            }
            extra = extra.merge(rIndex.extra());
            if (rArray.getEvaluated() instanceof VariableExpression ve2) {
                v = ve2.variable();

                Links vLinks = new LinksImpl.Builder(newDv).add(LinkNatureImpl.IS_ELEMENT_OF, v).build();
                extra = extra.merge(new LinkedVariablesImpl(Map.of(newDv, vLinks)));
            }
            Links.Builder builder = new LinksImpl.Builder(ve.variable());
            Result res = new Result(builder.build(), extra).merge(rArray).merge(rIndex);
            VariableExpression newVe = dv == newDv ? ve : runtime.newVariableExpression(newDv);
            return res.setEvaluated(newVe);
        }
        VariableExpression newVE;
        Variable expressionScopePrimary = null;
        if (v instanceof FieldReference fr) {
            Result r = visit(fr.scope(), variableData, stage);
            extra = extra.merge(r.extra());
            if (fr.scopeVariable() == null && r.links() != null && r.links().primary() != null) {
                // the scope is an expression ('requireNonNull(tail).next', 'self().next'): keep what it evaluates
                // to, and its links, so that an assignment to the field can modify it
                expressionScopePrimary = r.links().primary();
                extra = extra.merge(new LinkedVariablesImpl(Map.of(expressionScopePrimary, r.links())));
            }
            if (r.getEvaluated() != fr.scope()) {
                newVE = runtime.newVariableExpression(runtime.newFieldReference(fr.fieldInfo(), r.getEvaluated(),
                        fr.parameterizedType()));
            } else {
                newVE = ve;
            }
            if (expressionScopePrimary != null && !NO_EXPRESSION_SCOPE_FIELD
                && !Util.scopeVariables(newVE.variable()).contains(expressionScopePrimary)) {
                // and it holds the field: a modification of 'parent.self().data' ('.add(x)') reaches the result,
                // and through its links 'parent'. The variable's scope chain stops at the call, so without this
                // link the modification stopped there (CodeLaser/maddi-mod#72). Not when the scope already is that
                // result's variable (an inlined getter, 'parent.next.data'). Gate NOEXPRSCOPEFIELD.
                Links holds = new LinksImpl.Builder(expressionScopePrimary)
                        .add(LinkNatureImpl.CONTAINS_AS_FIELD, newVE.variable()).build();
                extra = extra.merge(new LinkedVariablesImpl(Map.of(expressionScopePrimary, holds)));
            }
        } else {
            newVE = ve;
        }

        Links.Builder builder = new LinksImpl.Builder(newVE.variable());
        if (newVE.variable().parameterizedType().isFunctionalInterface()
            && variableData != null && variableData.isKnown(newVE.variable().fullyQualifiedName())) {
            VariableInfo vi = variableData.variableInfo(newVE.variable(), stage);
            Links links = Objects.requireNonNullElse(vi.linkedVariables(), LinksImpl.EMPTY);
            links.forEach(l -> builder.add(l.from(), l.linkNature(), l.to()));
        }
        return new Result(builder.build(), extra).setEvaluated(newVE).setExpressionScopePrimary(expressionScopePrimary);
    }

    private Result assignment(VariableData variableData, Stage stage, Assignment a) {
        Result rValue = visit(a.value(), variableData, stage);
        Result rTarget = visit(a.target(), variableData, stage);
        Links.Builder builder = new LinksImpl.Builder(((VariableExpression) rTarget.getEvaluated()).variable());
        if (rValue.links() != null && rValue.links().primary() != null) {
            // cast-mediated assignment 'x = (II) o' (task #39): the cast is link-transparent, so the
            // mediation provenance is recovered from the casts side-record of the value's evaluation
            boolean mediated = rValue.casts().containsKey(rValue.links().primary());
            ((LinksImpl.Builder) builder).add(LinkNatureImpl.IS_ASSIGNED_FROM, rValue.links().primary(), mediated);
        }
        Result result = new Result(builder.build(), LinkedVariablesImpl.EMPTY);
        result.addErase(a.variableTarget());
        // assigning an @IgnoreModifications field does not modify its owner: the disclaimed stratum
        // (road §050) covers the slot as well as the content -- symmetric with the isIgnoreModifications
        // filter MethodModification.go applies to call-through modification. No-op without the annotation.
        // A lazily cached value or view (LazyCacheField, maddi-mod#136) disclaims the slot write only.
        Set<Variable> scopeVariables = a.variableTarget() instanceof FieldReference fr
                                       && LazyCacheField.isMemoSlot(fr.fieldInfo())
                ? Set.of()
                : Util.scopeVariables(a.variableTarget());
        // a field of an expression's result ('requireNonNull(tail).next = node'): no scope VARIABLE, but the
        // expression's result is modified, and through its links whatever it stands for (guava LinkedListMultimap)
        if (rTarget.expressionScopePrimary() != null && scopeVariables.isEmpty()
            && !(a.variableTarget() instanceof FieldReference fr2 && LazyCacheField.isMemoSlot(fr2.fieldInfo()))) {
            scopeVariables = Set.of(rTarget.expressionScopePrimary());
        }
        return result
                .merge(rValue)
                .merge(rTarget)
                .addModified(scopeVariables, null)
                .setEvaluated(rValue.getEvaluated() != a.value() || rTarget.getEvaluated() != a.target()
                        ? runtime.newAssignment((VariableExpression) rTarget.getEvaluated(), rValue.getEvaluated()) : a);
    }

    private Result constructorCall(VariableData variableData, Stage stage, ConstructorCall cc) {
        if (cc.object() != null && !cc.object().isEmpty()) {
            // qualified inner-class creation 'outer.new Inner(...)' (activemq first contact): linking the
            // outer instance into the creation is NYI — evaluate the outer for its own links/modifications,
            // conservatively mark its scope chain modified, and treat the creation as a fresh object (the
            // constructor's summary does not model the outer instance).
            Result outer = visit(cc.object(), variableData, stage);
            LocalVariable lvOuter = IntermediateVariable.newObject(runtime, variableCounter.getAndIncrement(),
                    cc.parameterizedType());
            Result fresh = new Result(new LinksImpl.Builder(lvOuter).build(), LinkedVariablesImpl.EMPTY);
            Variable outerPrimary = outer.links() == null ? null : outer.links().primary();
            Set<Variable> modified = outerPrimary == null ? Set.of() : Set.of(outerPrimary);
            return fresh.merge(outer).addModified(modified, null);
        }

        LocalVariable lv = IntermediateVariable.newObject(runtime, variableCounter.getAndIncrement(),
                cc.parameterizedType());
        Result object = new Result(new LinksImpl.Builder(lv).build(), LinkedVariablesImpl.EMPTY);

        // only translate wrt concrete type
        MethodLinkedVariables mlv0 = recurseIntoLinkComputer(cc.constructor());
        if (mlv0 == null) {
            // No data for an EXTERNAL constructor (unannotated library type, 'new URL(...)'): the sound
            // minimal result is still a FRESH OBJECT — return the new-object intermediate with no links, so
            // the assignment records 'x ← $__oc' and handleReturnVariable substitutes the '← $_v' marker (a
            // fresh unanalyzable return value must not look like a parameter identity, @Identity verdicts).
            // For SOURCE constructors the null means 'not yet analyzed' (recursion prevention) — keep the old
            // EMPTY short-circuit there: fabricating intermediates mid-cycle floods deeply recursive
            // structures (TestParSeqLinkBench 0.9s -> 19s).
            if (cc.constructor().typeInfo().compilationUnit().externalLibrary()) return object;
            return EMPTY; // cannot do anything at the moment, no data
        }
        MethodLinkedVariables mlv = mlv0.removeSomeValue();
        MethodLinkedVariables mlvTranslated1;
        if (mlv.virtual()) {
            VirtualFieldComputer.VfTm vfTm = virtualFieldComputer.compute(cc.parameterizedType(), true);
            mlvTranslated1 = vfTm.formalToConcrete() == null ? mlv : mlv.translate(vfTm.formalToConcrete());
        } else {
            mlvTranslated1 = mlv;
        }
        // NOTE translation with respect to parameters happens in LMC.methodCall()
        List<Result> params = cc.parameterExpressions().stream()
                .map(e -> visit(e, variableData, stage))
                .toList();
        if (linkComputerOptions.trackObjectCreations() && !params.isEmpty()) {
            List<Links> links = params.stream()
                    .map(r -> r.links().primary() == null ? r.links()
                            : r.links().removeIfTo(v -> !LinkVariable.acceptForLinkedVariables(v)))
                    .toList();
            LinkComputer.ListOfLinks list = new LinkComputerImpl.ListOfLinksImpl(links);
            // LAST-WRITE-WINS, not TolerantWrite: ListOfLinks equality is primary-only, so
            // first-arrival retention froze whichever derivation (precise vs incomplete-context
            // fallback) happened to arrive first — run-to-run nondeterministic, and invisible
            // everywhere except the shadow pass's E1 edges (see ListOfLinksImpl). The final write
            // of any run is the settled-state derivation.
            writeArgumentLinks(cc.analysis(), list);
        }
        Set<Variable> extraModified = params.stream().flatMap(p ->
                p.modified().keySet().stream()).collect(Collectors.toUnmodifiableSet());
        Result created = new LinkMethodCall(javaInspector, runtime, linkComputerOptions, virtualFieldComputer,
                variableCounter, currentMethod, variableData, stage)
                .constructorCall(cc, object, params, mlvTranslated1)
                .addModified(extraModified, deepOnlyAmong(params, extraModified), null)
                .addVariablesRepresentingConstant(params)
                .addVariablesRepresentingConstant(object);
        return linkInnerClassToOuterInstance(cc, created);
    }

    /*
    CodeLaser/maddi-mod#43, the creation site: an anonymous object (not a lambda-like one) was no object at all, its
    links EMPTY. It is a fresh object, and it holds every variable it captured, in a synthetic final
    field (javac's val$x). Link it so: newObject ≻ captured, as a field assignment in a constructor would. Without it
    the new object read independent of what it captured: guava's 'new ForwardingDeque<T>() { delegate() { return
    delegate; } }' left wrap's parameter @Independent, and fernflower's SFormsFastMapDirect.entryList(), anonymous
    entries over the map's own sets, was independent of the map, so StackVarsProcessor.replaceSingleVar modified its
    'ssau' unseen (the run-time check). The captured variables: the enclosing method's parameters and the locals known
    at this point, read in the anonymous class's code and not declared there. Primitives hold nothing.
     */
    private Result linkAnonymousClassToCaptured(ConstructorCall cc, VariableData variableData) {
        TypeInfo anonymous = cc.anonymousClass();
        LocalVariable newObject = IntermediateVariable.newObject(runtime, variableCounter.getAndIncrement(),
                cc.parameterizedType());
        if (NO_ANONYMOUS_CAPTURE) return new Result(LinksImpl.EMPTY, LinkedVariablesImpl.EMPTY);
        Set<Variable> declaredInside = new HashSet<>();
        Set<Variable> captured = new LinkedHashSet<>();
        java.util.function.Predicate<Element> collect = e -> {
            if (e instanceof LocalVariableCreation lvc) lvc.localVariableStream().forEach(declaredInside::add);
            if (e instanceof VariableExpression ve) {
                Variable v = ve.variable();
                if (v instanceof ParameterInfo pi && pi.methodInfo() == currentMethod
                    || v instanceof LocalVariable && variableData != null
                       && variableData.isKnown(v.fullyQualifiedName())) {
                    captured.add(v);
                }
            }
            return true;
        };
        // method bodies, and field initializers: fernflower's entry captures in 'private final ... val = ent;'
        anonymous.recursiveSubTypeStream().forEach(t -> {
            t.constructorAndMethodStream().filter(mi -> mi.methodBody() != null)
                    .forEach(mi -> mi.methodBody().visit(collect));
            t.fields().stream().filter(f -> f.initializer() != null && !f.initializer().isEmpty())
                    .forEach(f -> f.initializer().visit(collect));
        });
        captured.removeAll(declaredInside);
        captured.removeIf(v -> v.parameterizedType() == null || v.parameterizedType().isPrimitiveExcludingVoid());
        LinksImpl.Builder builder = new LinksImpl.Builder(newObject);
        // and its modification component is that of what it captured, as 'hold.d.§m ≡ 0:d.§m' after a field
        // assignment: a method of the anonymous object that modifies the captured object modifies it
        VirtualFields vfAnonymous = virtualFieldComputer.compute(anonymous.asParameterizedType(), false).virtualFields();
        FieldReference objectM = vfAnonymous == null || vfAnonymous.mutable() == null ? null
                : runtime.newFieldReference(vfAnonymous.mutable(), runtime.newVariableExpression(newObject),
                vfAnonymous.mutable().type());
        for (Variable v : captured) {
            builder.add(newObject, LinkNatureImpl.CONTAINS_AS_FIELD, v);
            if (objectM == null) continue;
            VirtualFields vf = virtualFieldComputer.compute(v.parameterizedType(), false).virtualFields();
            if (vf == null || vf.mutable() == null) continue;
            FieldReference capturedM = runtime.newFieldReference(vf.mutable(), runtime.newVariableExpression(v),
                    vf.mutable().type());
            builder.add(objectM, LinkNatureImpl.makeIdenticalTo(null), capturedM);
        }
        return new Result(builder.build(), LinkedVariablesImpl.EMPTY);
    }

    // CodeLaser/maddi-mod#43: the anonymous object as an object (a fresh one, holding what it captured); gate for A/B
    private static final boolean NO_ANONYMOUS_CAPTURE = System.getenv("NOANONYMOUSCAPTURE") != null;

    /*
    new Inner(...) of a non-static inner class, with the implicit outer instance E.this: the new object holds E.this,
    and those of its methods that modify E.this modify the creating object. Link the two modification components,
    passing exactly those methods: newObject.§m ☷{P} this.§m, P = the methods of Inner whose summaries modify E.this,
    plus every method they override (so a call through the declared type, IntIter.remove, matches Iter.remove).
    This is the shape of the JDK's Iterable.iterator() @Independent(hc = true, except = "remove"), computed instead of
    declared: an iterator's next() moves its own cursor, its remove() removes from the collection (EC work list O4:
    Mutable<P>Collection.removeIf through booleanIterator()). P empty: no link, the object stays independent of
    E.this (the book's ImmutableArray.IteratorImpl, §080). Qualified 'outer.new Inner()' and anonymous classes are
    not covered here.
     */
    private Result linkInnerClassToOuterInstance(ConstructorCall cc, Result created) {
        TypeInfo inner = cc.constructor().typeInfo();
        if (!inner.isInnerClass() || currentMethod.isStatic() || created.links() == null
            || created.links().primary() == null) {
            return created;
        }
        TypeInfo outer = inner.compilationUnitOrEnclosingType().getRight();
        if (currentMethod.typeInfo() != outer) return created;
        Set<MethodInfo> pass = new HashSet<>();
        for (MethodInfo methodInfo : inner.methods()) {
            if (methodInfo.isStatic() || methodInfo.isAbstract()) continue;
            MethodLinkedVariables mlv = linkComputer.recurseMethod(methodInfo);
            if (mlv == null) continue;
            boolean modifiesOuter = mlv.modified().stream()
                    .map(Util::primary)
                    .anyMatch(v -> v instanceof This thisVar && thisVar.typeInfo() == outer);
            if (modifiesOuter) {
                pass.add(methodInfo);
                pass.addAll(methodInfo.overrides());
            }
        }
        if (pass.isEmpty()) return created;
        VirtualFields vfInner = virtualFieldComputer.compute(inner.asParameterizedType(), false).virtualFields();
        VirtualFields vfOuter = virtualFieldComputer.compute(outer.asParameterizedType(), false).virtualFields();
        if (vfInner == null || vfOuter == null || vfInner.mutable() == null || vfOuter.mutable() == null) {
            return created;
        }
        Variable newObject = created.links().primary();
        FieldReference objectM = runtime.newFieldReference(vfInner.mutable(), runtime.newVariableExpression(newObject),
                vfInner.mutable().type());
        FieldReference thisM = runtime.newFieldReference(vfOuter.mutable());
        Links links = new LinksImpl.Builder(created.links())
                .add(objectM, LinkNatureImpl.makeIdenticalTo(pass), thisM)
                .build();
        return created.with(links);
    }

    // last-write-wins for the element-internal call-site argument links; same monitor discipline as
    // TolerantWrite (PropertyValueMapImpl's own methods synchronize on the map)
    private static void writeArgumentLinks(PropertyValueMap analysis,
                                           LinkComputer.ListOfLinks list) {
        synchronized (analysis) {
            if (analysis.haveAnalyzedValueFor(LinkComputerImpl.LINKED_VARIABLES_ARGUMENTS)) {
                analysis.overwrite(LinkComputerImpl.LINKED_VARIABLES_ARGUMENTS, list);
            } else {
                analysis.set(LinkComputerImpl.LINKED_VARIABLES_ARGUMENTS, list);
            }
        }
    }

    // Capture a lambda 'x -> …' as a functional-interface value: wrap its body's summary (links + modified) in a
    // FunctionalInterfaceVariable. doesNotBelongToLambda filters out what is internal to the lambda (its own
    // parameters, the void SAM's return variable, its 'this') so only external captures leak. See manual §7.1.
    private @NotNull Result lambda(VariableData vd, Lambda lambda) {
        MethodLinkedVariables mlv = linkComputer.recurseMethod(lambda.methodInfo());
        ParameterizedType concreteObjectType = lambda.concreteReturnType();
        MethodLinkedVariables mlvTranslated;
        if (mlv.virtual()) {
            VirtualFieldComputer.VfTm vfTm = virtualFieldComputer.compute(concreteObjectType, true);
            mlvTranslated = vfTm.formalToConcrete() == null ? mlv : mlv.translate(vfTm.formalToConcrete());
        } else {
            mlvTranslated = mlv;
        }
        Set<Variable> modifiedInLambda = mlv.modified().stream()
                .filter(v -> doesNotBelongToLambda(v, lambda.methodInfo()))
                .collect(Collectors.toUnmodifiableSet());
        Set<Variable> deepOnlyInLambda = deepOnlyAmong(mlv, modifiedInLambda);

        int i = 0;
        Map<Variable, Links> map = new HashMap<>();
        for (Links paramLinks : mlvTranslated.ofParameters()) {
            map.put(lambda.methodInfo().parameters().get(i), paramLinks);
            ++i;
        }
        // use the filtered modified set (external captures only), not the raw one: the raw set also contains
        // variables internal to the lambda (its parameters, the SAM's own markers such as 'accept'), which must
        // not leak into the caller's modified set when the functional interface is applied (e.g. via forEach).
        // Mirrors anonymousClassAsLambda. See TestStreamForEachSpec.
        Result wrapped = new Result(mlvTranslated.ofReturnValue(), new LinkedVariablesImpl(map))
                .addModified(modifiedInLambda, deepOnlyInLambda, null);

        FunctionalInterfaceVariable fiv = new FunctionalInterfaceVariable(
                runtime,
                variableCounter.getAndIncrement(),
                lambda.concreteFunctionalType(),
                wrapped);
        Links links = new LinksImpl.Builder(fiv).build();
        return new Result(links, LinkedVariablesImpl.EMPTY).addModified(modifiedInLambda, deepOnlyInLambda, null);
    }

    private boolean doesNotBelongToLambda(Variable v, MethodInfo methodInfo) {
        Variable base = Util.primary(v);
        if (base instanceof ParameterInfo pi && pi.methodInfo() == methodInfo) {
            return false;
        }
        // the lambda's own return variable is internal (present even for a void SAM such as Consumer.accept);
        // it must not leak into the caller's modified set. See TestStreamForEachSpec.
        if (base instanceof ReturnVariable rv && rv.methodInfo() == methodInfo) {
            return false;
        }
        if (base instanceof FieldReference fr
            && fr.scopeVariable() instanceof This thisVar
            && thisVar.typeInfo() == methodInfo.typeInfo()) {
            return false;
        }
        if (base instanceof This thisVar && thisVar.typeInfo() == methodInfo.typeInfo()) {
            return false;
        }
        return true;
    }

    private Result anonymousClass(VariableData variableData, Stage stage, ConstructorCall cc) {
        TypeInfo anonymousTypeInfo = cc.anonymousClass();
        MethodInfo sami = anonymousTypeImplementsFunctionalInterface(anonymousTypeInfo);
        if (sami != null) {
            return anonymousClassAsLambda(cc, sami);
        }
        return anonymousClassAsClass(anonymousTypeInfo, cc, variableData);
    }

    // the modifications its methods make, and a fresh object holding what it captured (linkAnonymousClassToCaptured)
    private Result anonymousClassAsClass(TypeInfo anonymousTypeInfo, ConstructorCall cc, VariableData variableData) {
        Result result = linkAnonymousClassToCaptured(cc, variableData);
        for (MethodInfo methodInfo : anonymousTypeInfo.constructorsAndMethods()) {
            MethodLinkedVariables mlv = linkComputer.recurseMethod(methodInfo);
            Set<Variable> modifiedInMethod = mlv.modified().stream()
                    .filter(v -> doesNotBelongToLambda(v, methodInfo))
                    .collect(Collectors.toUnmodifiableSet());
            // per method, so that a variable one method modifies structurally stays structural
            result.addModified(modifiedInMethod, deepOnlyAmong(mlv, modifiedInMethod), null);
        }
        return result;
    }

    private Result anonymousClassAsLambda(ConstructorCall cc, MethodInfo sami) {
        MethodLinkedVariables mlv = linkComputer.recurseMethod(sami);
        MethodLinkedVariables mlvTranslated;
        if (mlv.virtual()) {
            VirtualFieldComputer.VfTm vfTm = virtualFieldComputer.compute(cc.parameterizedType(), true);
            mlvTranslated = mlv.translate(vfTm.formalToConcrete());
        } else {
            mlvTranslated = mlv;
        }
        Set<Variable> modifiedInLambda = mlv.modified().stream()
                .filter(v -> doesNotBelongToLambda(v, sami))
                .collect(Collectors.toUnmodifiableSet());
        int i = 0;
        Map<Variable, Links> map = new HashMap<>();
        for (Links paramLinks : mlvTranslated.ofParameters()) {
            map.put(sami.parameters().get(i), paramLinks);
            ++i;
        }
        return new Result(mlvTranslated.ofReturnValue(), new LinkedVariablesImpl(map))
                .addModified(modifiedInLambda, deepOnlyAmong(mlv, modifiedInLambda), null);
    }

    private MethodInfo anonymousTypeImplementsFunctionalInterface(TypeInfo typeInfo) {
        if (!typeInfo.parentClass().isJavaLangObject()) return null;
        if (!typeInfo.interfacesImplemented().isEmpty()) {
            if (typeInfo.interfacesImplemented().size() > 1) return null;
            if (!typeInfo.interfacesImplemented().getFirst().isFunctionalInterface()) return null;
        }
        List<MethodInfo> methods = typeInfo.methods();
        if (methods.size() != 1) return null;
        return methods.getFirst();
    }


    private Result methodCall(VariableData variableData, Stage stage, MethodCall mc) {
        // recursion, translation
        Result object = visit(mc.object(), variableData, stage);
        Variable objectPrimary = object.links().primary();

        Value.FieldValue fv = mc.methodInfo().getSetField();
        if (fv.field() != null) {
            if (!fv.setter() && mc.parameterExpressions().isEmpty() && objectPrimary != null) {
                VariableExpression ve = runtime.newVariableExpression(
                        runtime.newFieldReference(fv.field(),
                                runtime.newVariableExpression(objectPrimary), mc.concreteReturnType()));
                Result getter = variableExpression(ve, variableData, stage);
                // a getter on a call result ('parent.getWrapper().getH()', inlined as '$__rv0.h'): keep what the
                // receiver evaluation knows, its links ('$__rv0 ≺ 0:parent') and its modifications. Dropped, a
                // modification through the field stopped at the result (CodeLaser/maddi-mod#80). Gate NOGETTERRECEIVER
                if (!NO_GETTER_RECEIVER && !(mc.object() instanceof VariableExpression)) return getter.merge(object);
                return getter;
            }
        }

        MethodLinkedVariables mlv0 = recurseIntoLinkComputer(mc.methodInfo());
        if (mlv0 == null) return EMPTY; // cannot do anything at the moment, no data
        MethodLinkedVariables mlv = mlv0.removeSomeValue();
        // translate conditionally wrt concrete type, evaluated object
        MethodLinkedVariables mlvTranslated2;
        if (FunctionTypes.isSAMOfStandardFunctionalInterface(mc.methodInfo())) {
            mlvTranslated2 = mlv; // no point doing anything
        } else if (objectPrimary != null) {
            This thisVar = runtime.newThis(mc.methodInfo().typeInfo().asParameterizedType());
            MethodLinkedVariables mlvTranslated1;
            if (!thisVar.equals(objectPrimary)) {
                VariableTranslationMap tm = new VariableTranslationMap(runtime).put(thisVar, objectPrimary);
                if (!NO_SUPER_THIS) {
                    // the callee's code reaches its supertypes' fields through their 'this' ('fix() { p.set(2); }' in
                    // a subclass of the type declaring p: this{Base}.p): the same object, as LinkMethodCall's
                    // addThisHierarchyToObjectPrimaryToTmBuilder and MethodModification (#126) already take it.
                    // Left untranslated, MethodModification translated it itself, and dropped the result as unknown
                    // in the caller: 's.fix()' did not modify what 's.p' holds (CodeLaser/maddi-mod#150, the
                    // fixpoint's mkSub). Gate NOSUPERTHIS (presence): the callee's own type only, as before.
                    for (TypeInfo superType : mc.methodInfo().typeInfo().superTypesExcludingJavaLangObject()) {
                        tm.put(runtime.newThis(superType.asParameterizedType()), objectPrimary);
                    }
                }
                mlvTranslated1 = mlv.translate(tm);
            } else {
                mlvTranslated1 = mlv;
            }
            if (!currentMethod.typeInfo().isEqualToOrInnerClassOf(mc.methodInfo().typeInfo())) {
                ParameterizedType concreteObjectType = objectPrimary.parameterizedType();
                if (mlvTranslated1.virtual()) {
                    VirtualFieldComputer.VfTm vfTm = virtualFieldComputer.compute(concreteObjectType, true);
                    TranslationMap tm2;
                    if (!mc.methodInfo().typeParameters().isEmpty() && vfTm.formalToConcrete() != null) {
                        // when the return type parameter agrees with the input type parameter, also translate that one!
                        // TP#0 in Optional->Map.Entry.XY[], but U (method TP in .map(...)) needs translating too:
                        // map.§u ⊆ 0:mapper must become map.§xys ⊆ ...
                        tm2 = new VirtualFieldTranslationMapForMethodParameters(virtualFieldComputer, javaInspector().runtime())
                                .go(vfTm.formalToConcrete(), mc);
                    } else {
                        tm2 = vfTm.formalToConcrete();
                    }
                    mlvTranslated2 = tm2 == null ? mlvTranslated1 : mlvTranslated1.translate(tm2);
                } else {
                    mlvTranslated2 = mlvTranslated1;
                }
            } else {
                // no transformation needed
                mlvTranslated2 = mlvTranslated1;
            }
        } else {
            // static method, without object; but there may be method type parameters involved
            TranslationMap vfTm = new VirtualFieldTranslationMapForMethodParameters(virtualFieldComputer, runtime)
                    .staticCall(mc);
            mlvTranslated2 = mlv.translate(vfTm);
        }

        // NOTE translation with respect to parameters happens in LMC.methodCall()
        List<Result> params = mc.parameterExpressions().stream()
                .map(e -> visit(e, variableData, stage))
                .toList();
        if (linkComputerOptions.trackObjectCreations() && !params.isEmpty()) {
            List<Links> links = params.stream()
                    .map(r -> r.links().primary() == null ? r.links()
                            : r.links().removeIfTo(v -> !LinkVariable.acceptForLinkedVariables(v)))
                    .toList();
            LinkComputer.ListOfLinks list = new LinkComputerImpl.ListOfLinksImpl(links);
            // LAST-WRITE-WINS, not TolerantWrite: see the constructor-call twin above
            writeArgumentLinks(mc.analysis(), list);
        }

        // handle all matters 'linking'

        Result r = new LinkMethodCall(javaInspector, runtime, linkComputerOptions, virtualFieldComputer, variableCounter,
                currentMethod, variableData, stage)
                .methodCall(mc.methodInfo(), mc.concreteReturnType(), object, params, mlvTranslated2);
        // the callee's summary says its result is a field of its receiver, or of an object reachable from it
        // ('getWrapper ≺ this.parent' when it walks a parent chain): genuine containment, which the containment
        // filter must keep for the local holding the result, as it does for a pattern binding. Unlike an accessor
        // copy ('o ← 0:r.object', TestCast), the callee itself asserted it. Dropped, 'parent.getWrapper().x = 1'
        // did not reach 'parent' (fernflower ClassNode.getWrapper, CodeLaser/maddi-mod#80)
        if (!NO_CALL_RESULT_FIELD && r.links().primary() != null) {
            for (Link link : r.links().linkSet()) {
                if (link.linkNature() == LinkNatureImpl.IS_FIELD_OF && link.from().equals(r.links().primary())) {
                    sourceMethodComputer.followGraph.graph().markPatternBinding(link.to(), link.from());
                }
            }
        }
        MethodModification.Modified modified = new MethodModification(runtime, variableData, stage, mc, currentMethod,
                sourceMethodComputer::recordModifiedThroughPassedFunction,
                implementation -> linkComputer.recordSummaryConsumption(currentMethod, implementation))
                .go(objectPrimary, params, mlvTranslated2);
        // own-field slot assignments propagate transitively: the callee summary's assigned faces were
        // rehomed this->objectPrimary above, so a face that still sits on a recursively-this scope chain
        // is an own field of THIS method's receiver ('inc()' -> this.i, 'd.incJ()' -> this.d.j). Faces
        // rehomed onto a parameter or local are not own fields and are dropped.
        if (objectPrimary != null && !FunctionTypes.isSAMOfStandardFunctionalInterface(mc.methodInfo())) {
            for (Variable av : mlvTranslated2.assigned()) {
                if (av instanceof FieldReference fr && fr.scopeIsRecursivelyThis()) {
                    sourceMethodComputer.assignedInCallees.add(av);
                }
            }
        }
        Set<Variable> extraModified = params.stream().flatMap(p ->
                p.modified().keySet().stream()).collect(Collectors.toUnmodifiableSet());
        // Carry the object sub-expression's own modifications forward through the chain. Without this, a terminal
        // call drops side effects accumulated earlier in the same expression: 'list.stream().peek(target::add).toList()'
        // would lose peek's modification of target (a bare non-terminal 'peek(...)' keeps it). Propagate only
        // genuine, caller-visible side effects: (1) skip synthetic intermediates ('$_ce', '$_fi'/'$_afi' markers,
        // all LinkVariable); (2) skip any variable that is consumed into the chained object, i.e. one that carries its
        // own outgoing links in the object's graph (an 'extra' primary) — an argument stored into a builder
        // ('setVariable(1,t)') is over-approximated as modified upstream, but that flows into the builder and must not
        // surface as a fresh modification here (see TestStaticValuesRecord builder chains). A pure side-channel
        // capture like peek's target only appears as a link partner, never as an owning primary. See
        // TestStreamForEachSpec.
        Set<Variable> consumedIntoObject = object.extra().map().keySet().stream()
                .map(Util::primary).filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        Set<Variable> objectModified = object.modified().keySet().stream()
                .filter(v -> !(Util.primary(v) instanceof LinkVariable))
                .filter(v -> {
                    Variable p = Util.primary(v); // null (expression-based array access) cannot be consumed
                    return p == null || !consumedIntoObject.contains(p);
                })
                .collect(Collectors.toUnmodifiableSet());
        Set<Variable> elementOwners = NO_ELEMENT_OWNER ? Set.of()
                : ownersOfModifiedElement(objectPrimary, object.links(), modified.all(), variableData, stage);
        // CodeLaser/maddi-mod#114: 'm.computeIfAbsent(k, ..).add(e)'. computeIfAbsent modifies its receiver m
        // structurally, but m has links of its own in the object's result, so the filter above, meant for arguments
        // consumed into a builder, drops it; add's modification then reaches m only through its element (∋), deep.
        // The receiver of the inner call is no consumed argument: carry its structural modification forward.
        Set<Variable> innerReceiver = NO_CHAIN_STRUCTURAL ? Set.of() : innerReceiverModified(mc, object, objectModified);
        Set<Variable> lazyStageElements = MethodModification.lazyStageModifiedLambdaParameters(mc);
        if (!lazyStageElements.isEmpty()) r = withElementLinks(r, objectPrimary, lazyStageElements);
        // #159: an owner of the modified elements is modified in its hidden content; a scope of it whose declared type
        // holds the elements accessibly ('this' of 'this.lvs' in 'lvs.stream().findFirst().ifPresent(..)'),
        // structurally (MethodModification.structuralHolders)
        Set<Variable> ownerHolders = new HashSet<>();
        List<ParameterizedType> elementTypes = objectPrimary == null || !modified.deepOnly().contains(objectPrimary)
                ? List.of() : MethodModification.handedElementTypes(objectPrimary);
        if (!elementTypes.isEmpty()) {
            Set<Variable> owners = new HashSet<>(elementOwners);
            // a call chain's temporaries ('lvs.stream().findFirst().ifPresent(..)'): '$rv1.§$ ∈ $rv0.§$s', and in the
            // object's extras '$rv0.§$s ⊆ this.lvs.§$s'; the real containers at the end of the element links
            owners.addAll(elementContainers(objectPrimary, object));
            for (Variable owner : owners) {
                ownerHolders.addAll(MethodModification.structuralHolders(owner, elementTypes));
            }
        }
        return r.addModified(modified.all(), modified.deepOnly(), mc.methodInfo())
                .addModified(lazyStageElements, lazyStageElements, mc.methodInfo())
                .addModified(elementOwners, elementOwners, mc.methodInfo())
                .addModified(ownerHolders, Set.of(), mc.methodInfo())
                .addModified(extraModified, deepOnlyAmong(params, extraModified), null)
                .addModified(objectModified, object.deepOnlyAmong(objectModified), null)
                .addModified(innerReceiver, Set.of(), null)
                .add(new WriteMethodCall(mc, object.links()))
                .addVariablesRepresentingConstant(params)
                .addVariablesRepresentingConstant(object);
    }

    /*
     #159 (MethodModification.structuralHolders): the real variables whose hidden content holds the object's elements,
     following element links ('∈', '⊆', '~' between hidden contents) from the object through the call chain's
     temporaries, whose links the object's extras keep. Bounded.
     */
    private static Set<Variable> elementContainers(Variable objectPrimary, Result object) {
        Set<Variable> out = new HashSet<>();
        if (MethodModification.NO_ELEMENT_FUNCTION_REACH) return out;
        Map<Variable, Links> extra = object.extra().map();
        Deque<Links> todo = new ArrayDeque<>();
        Set<Variable> seen = new HashSet<>();
        if (object.links() != null) todo.add(object.links());
        while (!todo.isEmpty() && seen.size() < 16) {
            for (Link link : todo.poll()) {
                LinkNature ln = link.linkNature();
                if (ln != LinkNatureImpl.IS_ELEMENT_OF && ln != LinkNatureImpl.IS_SUBSET_OF
                    && ln != LinkNatureImpl.SHARES_ELEMENTS) continue;
                if (!Util.virtual(link.to()) || Util.isVirtualModification(link.to())) continue;
                Variable container = Util.firstRealVariable(link.to());
                if (container == null || container.equals(objectPrimary) || !seen.add(container)) continue;
                Links next = extra.get(container);
                if (container instanceof IntermediateVariable || container instanceof ReturnVariable) {
                    if (next != null) todo.add(next);
                } else {
                    out.add(container);
                }
            }
        }
        return out;
    }

    /*
     #115: the lambda parameters a lazy stream stage hands the receiver's elements to are those elements: 'stream.§$s ∋
     0:h'. Filter's function linking already says so; map's and anyMatch's do not. With the link, the parameter's
     modification reaches the stream's source through the ∋ completion.
     */
    private Result withElementLinks(Result r, Variable objectPrimary, Set<Variable> elements) {
        if (objectPrimary == null) return r;
        VirtualFields vf = virtualFieldComputer.compute(objectPrimary.parameterizedType(), false).virtualFields();
        FieldInfo hiddenContent = vf == null ? null : vf.hiddenContent();
        if (hiddenContent == null) return r;
        Variable hc = runtime.newFieldReference(hiddenContent, runtime.newVariableExpression(objectPrimary),
                hiddenContent.type());
        Links.Builder builder = new LinksImpl.Builder(objectPrimary);
        for (Variable element : elements) builder.add(hc, LinkNatureImpl.CONTAINS_AS_MEMBER, element);
        return r.merge(new Result(LinksImpl.EMPTY, new LinkedVariablesImpl(Map.of(objectPrimary, builder.build()))));
    }

    // the receiver variable of the call that is mc's object, and its scopes, as far as that call modified them structurally
    private static Set<Variable> innerReceiverModified(MethodCall mc, Result object, Set<Variable> alreadyCarried) {
        Expression o = mc.object();
        while (o instanceof EnclosedExpression ee) o = ee.inner();
        if (!(o instanceof MethodCall inner)) return Set.of();
        Expression io = inner.object();
        while (io instanceof EnclosedExpression ee) io = ee.inner();
        if (!(io instanceof VariableExpression ve)) return Set.of();
        return Util.variableAndScopes(ve.variable())
                .filter(v -> object.modified().containsKey(v) && !object.deepOnlyModified().contains(v)
                             && !alreadyCarried.contains(v))
                .collect(Collectors.toUnmodifiableSet());
    }

    /*
     CodeLaser/maddi-mod#127: the empty summary stands for a call back into the recursion only. RECURSIVE_METHOD on the
     current method alone (7c2a827e: a lambda calling its enclosing method) emptied EVERY call in a recursive body,
     library calls included: 'component.push(t)' in a recursive 'visit(t)' linked nothing. A callee that is not itself
     recursive cannot be in the current method's cycle, so it keeps its summary. There is no cycle identity, so a
     recursive callee of another cycle still gets the empty one. Gate NONARROWRECURSION restores the old rule.
     */
    static final boolean NO_NARROW_RECURSION = System.getenv("NONARROWRECURSION") != null;
    /*
     #127: inside a recursive method, only callees WITHOUT a source body (library, JDK) keep their summary: those are
     what the issue is about (Deque.push, Map.put, List.getFirst), and they are cheap. Keeping the summaries of source
     callees too (gate RECURSIONSOURCESUMMARIES) pushed Guava from 0 to 18+ and Fernflower from 5 to 12 methods over
     the work ceiling, and made a read-only Fernflower cycle (GenericType.appendCastName...) modifying.
     */
    static final boolean RECURSION_SOURCE_SUMMARIES = System.getenv("RECURSIONSOURCESUMMARIES") != null;

    /*
     #127: an abstract method of the sources has no body either. Its summary is the shallow one, as cheap as a
     library method's, and it carries what its implementations do (their verdicts' union). fernflower's recursive
     collector 'Exprent.getAllExprents(boolean, List)' calls the abstract 'getAllExprents(List)', which every
     subclass implements by adding its subexpressions: with the empty summary the list was independent of the
     expression, and so was what 'getAllExprents(true)' returns. Gate NORECURSIONABSTRACT: library callees only.
     */
    static final boolean NO_RECURSION_ABSTRACT = System.getenv("NORECURSIONABSTRACT") != null;

    /*
     #127, case 1: a recursive walker reads its argument through a source callee with a body
     ('for (obj : stat.getExprentsOrSequentialObjects())' in fernflower's setVersionsToNull). With the empty summary
     what the callee returns is linked to nothing, so the walk's modifications of it do not reach the parameter.
     The bounded summary keeps the callee's return-value links only: no parameter links, no modified set. Those two
     are what made RECURSIONSOURCESUMMARIES costly and turned a read-only fernflower cycle modifying. The callee is
     not recursive itself (that case keeps the empty summary), so computing it cannot re-enter the cycle.
     On since 2026-11-18. Fernflower: contradicted 2a claims 26 -> 23 (cleanUpUnreachableBlocks,
     simplifyStackVarsStatement, lowClosures cleared; four more lose their deep contradiction), verdict run +11%.
     Guava: run +26%, TypeToken.isSubtypeOf over the work ceiling. Gate NORECURSIONRETURNSUMMARIES: the empty summary.
     */
    static final boolean RECURSION_RETURN_SUMMARIES = System.getenv("NORECURSIONRETURNSUMMARIES") == null;
    // diagnostics: one line per bounded summary handed out (size, caller, callee, return type, links)
    static final boolean RECURSION_RETURN_DUMP = System.getenv("RECURSIONRETURNDUMP") != null;

    /*
     The bound: a callee handing back more return links than this gets the empty summary, as before. Measured with
     RECURSIONRETURNDUMP (2026-11-18): fernflower's case-1 callees hand back 1 to 6 (getStats 1,
     getExprentsOrSequentialObjects 5, getMinContinueClosure 6); Guava's TreeMultiset.AvlNode helpers 33 to 86
     (rebalance, addLeftChild, addRightChild), and those took four recursive AvlNode methods over the work ceiling.
     */
    static final int RECURSION_RETURN_CAP = Integer.parseInt(Objects.requireNonNullElse(
            System.getenv("RECURSIONRETURNCAP"), "8"));

    /*
     EXPERIMENT (CodeLaser/maddi-mod#158), gate CALLCYCLEEMPTY (presence). The bounded summary rests on "the callee is
     not recursive itself, so computing it cannot re-enter the cycle". RECURSIVE_METHOD marks a method that calls
     itself; a callee that reaches the current method through other methods (mutual recursion) is not marked, and
     its summary does depend on the current method's. The cap then makes that dependence a switch: guava's
     TypeToken.canonicalizeWildcardsInType (calls itself) reads canonicalizeWildcardsInParameterizedType, which
     reaches it again through canonicalizeTypeArg. With 8 return links the callee is read in full and the group's
     summaries grow; grown, it has more than 8, is read as empty, and they shrink again: two states, alternating
     for good, and the analysis certified on whichever it stood on (the uncounted overwrite, LATESTWINSCOUNTS).
     With the gate, a callee in the current method's call cycle is a call back into the recursion: the empty summary.
     */
    static final boolean CALL_CYCLE_EMPTY = System.getenv("CALLCYCLEEMPTY") != null;

    private static MethodLinkedVariables returnLinksOnly(MethodInfo methodInfo, MethodLinkedVariables full) {
        Links ofReturn = full == null ? LinksImpl.EMPTY : full.ofReturnValue();
        int n = 0;
        for (Link ignored : ofReturn) n++;
        if (n > RECURSION_RETURN_CAP) ofReturn = LinksImpl.EMPTY;
        return new MethodLinkedVariablesImpl(ofReturn,
                methodInfo.parameters().stream().map(_ -> LinksImpl.EMPTY).toList(), Set.of());
    }

    private static boolean withoutBody(MethodInfo methodInfo) {
        return methodInfo.typeInfo().compilationUnit().externalLibrary()
               || !NO_RECURSION_ABSTRACT && methodInfo.isAbstract();
    }

    private static boolean recursive(MethodInfo methodInfo) {
        return methodInfo.analysis().getOrDefault(PropertyImpl.RECURSIVE_METHOD, ValueImpl.BoolImpl.FALSE).isTrue();
    }

    /*
     #127: a call from 'current' back into its recursion (as far as RECURSIVE_METHOD can tell). RECURSIVE_METHOD marks
     a method that calls itself, or an enclosing method called from its own lambda or local class (ComputeCallGraph.
     isRecursion), so one recursion lives inside one outermost method. Both methods recursive is not enough: two
     self-recursive methods are two recursions, and an undecided verdict of the callee marked nothing, the caller then
     read it as unmodified, and which of the two was linked first is thread timing in the parallel first pass
     (CodeLaser/maddi-mod#103: fernflower's identifySecondaryFunctions(Statement, VarProcessor) hands varProc to its
     Exprent overload, which modifies it; unmodified in 2 of 6 runs). Gate NOSAMERECURSION: both recursive, as before.
     */
    private static final boolean NO_SAME_RECURSION = System.getenv("NOSAMERECURSION") != null;

    static boolean inRecursion(MethodInfo current, MethodInfo callee) {
        if (callee.equals(current)) return true;
        if (!recursive(current) || !recursive(callee)) return false;
        return NO_SAME_RECURSION || outermost(current).equals(outermost(callee));
    }

    private static MethodInfo outermost(MethodInfo methodInfo) {
        MethodInfo m = methodInfo;
        MethodInfo enclosing;
        while ((enclosing = m.typeInfo().enclosingMethod()) != null) m = enclosing;
        return m;
    }

    private MethodLinkedVariables recurseIntoLinkComputer(MethodInfo methodInfo) {
        if (methodInfo.equals(currentMethod)) {
            // #127: the call to itself links, in the second round, as the first round's summary says
            MethodLinkedVariables self = linkComputer.selfSummary(methodInfo);
            if (self != null) return self;
        }
        boolean returnLinksOnly = false;
        if (methodInfo.equals(currentMethod)
            || recursive(currentMethod) && (NO_NARROW_RECURSION || recursive(methodInfo)
                                            || CALL_CYCLE_EMPTY && linkComputer.sameCallCycle(currentMethod, methodInfo)
                                            || !RECURSION_SOURCE_SUMMARIES && !withoutBody(methodInfo)
                                               && !(returnLinksOnly = RECURSION_RETURN_SUMMARIES))) {
            // direct recursion, lambda to enclosing method
            return new MethodLinkedVariablesImpl(LinksImpl.EMPTY,
                    methodInfo.parameters().stream().map(_ -> LinksImpl.EMPTY).toList(), Set.of());
        }
        // worklist edge discovery: currentMethod's links are being computed FROM methodInfo's summary
        linkComputer.recordSummaryConsumption(currentMethod, methodInfo);
        if (returnLinksOnly) {
            MethodLinkedVariables bounded = returnLinksOnly(methodInfo, summaryOf(methodInfo));
            if (RECURSION_RETURN_DUMP) {
                int n = 0;
                for (Link ignored : bounded.ofReturnValue()) n++;
                System.out.println("RRS " + n + '\t' + currentMethod.fullyQualifiedName() + '\t'
                                   + methodInfo.fullyQualifiedName() + '\t' + methodInfo.returnType().detailedString()
                                   + '\t' + bounded.ofReturnValue());
            }
            return bounded;
        }
        return summaryOf(methodInfo);
    }

    private MethodLinkedVariables summaryOf(MethodInfo methodInfo) {
        RecursionPrevention.How how = recursionPrevention.contains(methodInfo);
        return switch (how) {
            case GET -> methodInfo.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class);
            case SHALLOW -> linkComputer.doMethodShallowDoNotWrite(methodInfo);
            case LOCK -> {
                if (linkComputer.lockComputeDisabled()
                    && !methodInfo.isAbstract() // abstract -> shallow compute, no nested locks: getOrCreate is safe
                    && !methodInfo.analysis().haveAnalyzedValueFor(METHOD_LINKS)) {
                    // parallel first iteration: computing another element's links while holding its analysis
                    // monitor (getOrCreate) could deadlock across worker threads. recurseMethod computes
                    // WITHOUT the monitor and WRITES the result (the plain doMethod(MethodInfo) does not
                    // write — using it here recomputed whole chains per touch, an exponential cascade in a
                    // large call cycle; and a shallow fallback froze first-impression verdict losses on
                    // graph-missed edges). Two threads may duplicate a computation; the value is
                    // deterministic and the write TolerantWrite-guarded, so the loser's write is a no-op.
                    // Cycles terminate via the same-thread SHALLOW fallback of RecursionPrevention.
                    yield linkComputer.recurseMethod(methodInfo);
                }
                if (methodInfo.typeInfo().enclosingMethod() != null) {
                    // enclosed (lambda/anonymous-class) method: NOT an analysis-order element, so the
                    // doType SLOT never recomputes it — getOrCreate here froze the FIRST on-demand
                    // computation, in whatever context that toucher had. Arrival-order dependent: the
                    // λ-target residue of docs/design/eventual-info-hierarchy.md §"The seed-order round"
                    // (which caller's lambda carries the value-mediated edge varied per run).
                    // Recompute and let canonical retention decide — the slot rule (LinkComputerImpl.
                    // doType) extended to non-order methods; the stored value is yielded, so caller
                    // and store agree when retention keeps a richer existing value.
                    TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), METHOD_LINKS,
                            linkComputer.doMethod(methodInfo), methodInfo);
                    yield methodInfo.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class);
                }
                yield methodInfo.analysis().getOrCreate(METHOD_LINKS, () -> linkComputer.doMethod(methodInfo));
            }
        };
    }

}
