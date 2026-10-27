package io.codelaser.maddi.modification.link.impl.linkgraph;

import io.codelaser.maddi.modification.link.impl.Gate;
import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.link.impl.graph.DegradedAnalysisException;
import io.codelaser.maddi.modification.link.impl.localvar.IntermediateVariable;
import io.codelaser.maddi.modification.link.impl.localvar.MarkerVariable;
import io.codelaser.maddi.modification.link.impl.translate.VariableTranslationMap;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;
import io.codelaser.maddi.modification.prepwork.variable.impl.LinksImpl;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.translate.TranslationMap;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class LinkGraph {
    final Runtime runtime;
    final boolean checkDuplicateNames;
    final Graph graph;
    final MakeGraph makeGraph;

    public LinkGraph(Runtime runtime,
                     boolean checkDuplicateNames,
                     Graph graph,
                     MakeGraph makeGraph) {
        this.checkDuplicateNames = checkDuplicateNames;
        this.graph = graph;
        this.makeGraph = makeGraph;
        this.runtime = runtime;
    }

    public Graph graph() {
        return graph;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(LinkGraph.class);

    // see TestModificationParameter, a return variable with the same name as a local variable
    private static String stringForDuplicate(Variable v) {
        if (v instanceof ReturnVariable) return "rv " + v;
        if (v instanceof ParameterInfo pi) return pi.methodInfo().name() + ":" + pi.index();
        if (v instanceof DependentVariable dv && dv.indexVariable() != null) {
            return stringForDuplicate(dv.arrayVariable()) + "[" + stringForDuplicate(dv.indexVariable()) + "]";
        }
        if (v instanceof FieldReference fr && fr.scopeVariable() != null) {
            return stringForDuplicate(fr.scopeVariable()) + "." + fr.fieldInfo().name();
        }
        return v.toString();
    }

    //-------------------------------------------------------------------------------------------------

    /*
     Write the links in terms of variables that we have, removing the temporarily created ones.
     Ensure that we use the variables and not their virtual fields.
     Ensure that v1 == v2 also means that v1.ts == v2.ts, v1.$m == v2.$m, so that these connections can be made.
     Filtering out ? links is done in followGraph.
     */
    // TEMPORARY (#72 4a diagnostic): OLDVALUETRACE=<method fqn substring>
    public String traceMethod;
    private String traceStatement;
    private static final String OLDVALUETRACE = System.getenv("OLDVALUETRACE");

    private void traceOldValue(String what, Variable target, Variable alias, Map<Variable, Links> before,
                               Map<Variable, Links> after) {
        if (OLDVALUETRACE == null || traceMethod == null || !traceMethod.contains(OLDVALUETRACE)) return;
        System.out.println("OLDVALUE " + what + " " + traceMethod + " @" + traceStatement + " " + target + " -> " + alias
                           + "\n  before: " + before + "\n  after:  " + after);
    }

    public void compute(String statementIndex,
                        Map<Variable, Links> newLinks,
                        Set<Variable> toRemove,
                        TranslationMap replaceConstants,
                        Map<Variable, Set<MethodInfo>> modifiedInThisEvaluation) {
        traceStatement = statementIndex;
        graph.dropDetached(toRemove);
        Set<Variable> allToRemove = graph.variables().stream()
                .filter(v -> toRemove.contains(Util.firstRealVariable(v)))
                .collect(Collectors.toUnmodifiableSet());
        if (!allToRemove.isEmpty()) {
            graph.remove(allToRemove);
        }
        Set<Variable> allToRemove2 = graph.eqVariables()
                .filter(v -> toRemove.contains(Util.firstRealVariable(v)))
                .collect(Collectors.toUnmodifiableSet());
        if (!allToRemove2.isEmpty()) {
            graph.removeEquivalence(allToRemove2);
        }

        Reduce r = reduceLinks(newLinks);
        if (r != null && r.clear != null) {
            graph.clear(r.clear, statementIndex);
        }
        Map<Variable, Links> newLinks2 = r == null ? respellSelfReassignment(newLinks) : r.newLinks;
        newLinks2.entrySet()
                .stream()
                .filter(e -> !(e.getKey() instanceof This))
                .filter(e -> e.getValue().primary() != null)
                .sorted(Map.Entry.comparingByKey(Variable::compareTo))
                .forEach(e -> e.getValue().translate(replaceConstants).forEach(link -> {
                    graph.addStatementLink(link.from(), link.linkNature(), link.to(), statementIndex,
                            link.mediated());
                }));

        boolean change = true;
        int cycleProtection = 0;
        while (change) {
            ++cycleProtection;
            if (cycleProtection > 30) {
                // NOTE: a class in the maddi code base needs >10 rounds, camel's generated bulk-converter
                // loaders >20. Keeping the partial graph is NOT an option (its size makes everything
                // downstream minutes-slow: measured 5s -> 30min on camel-base); the throw is caught at the
                // method level, which degrades to a SHALLOW summary (LinkComputerImpl.doMethod).
                throw new DegradedAnalysisException(DegradedAnalysisException.Reason.EXPANSION_ROUNDS);
            }
            change = makeGraph.expandGraph(statementIndex, modifiedInThisEvaluation.keySet());
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Bi-directional graph for local:\n{}\n{}", graph.engine().printClosure(),
                    graph.printEquivalence(LinkGraph::vertexPrinter));
        }
        assert !checkDuplicateNames || duplicateNames().isEmpty() : duplicateNameReport();
    }

    /*
     The duplicate-name check used to be written inline as a size-vs-distinct-count comparison, so when it
     failed it threw an AssertionError carrying NOTHING -- and the throw is caught per method
     (LinkComputerImpl.doMethod), which degrades that method to a SHALLOW summary without a word about why.
     Measured on the OpenSearch DAG corpus on 2026-08-26: 25,143 of 170,191 methods lost their link facts
     entirely and 177,379 statements degraded, every one here, every one reported as `null`. An assertion
     that fires fifty thousand times and names nothing cannot be acted on, so it now names the collision.
     */
    private Map<String, List<Variable>> duplicateNames() {
        Map<String, List<Variable>> byName = graph.variables().stream()
                .collect(Collectors.groupingBy(LinkGraph::stringForDuplicate));
        return byName.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private String duplicateNameReport() {
        return "graph.size()=" + graph.size() + ", distinct printed names="
               + graph.variables().stream().map(LinkGraph::stringForDuplicate).distinct().count()
               + "; colliding: " + duplicateNames().entrySet().stream()
                       .map(e -> e.getKey() + " <- " + e.getValue().stream().map(v -> v.getClass().getSimpleName()
                               + "(" + v + ")").collect(Collectors.joining(", ")))
                       .sorted().collect(Collectors.joining(" | "));
    }

    private final Set<IntermediateVariable> intermediateVariablesRemoved = new HashSet<>();

    /*
     The direct form of reduceLinks' old-value respelling: 'n = n.next' arrives as 'n ← n.next' (no intermediate to
     reduce), and mergeEdgeBi takes 'n' out of its group before it looks at 'n.next'. Every spelling of n's faces
     on a right-hand side, and every other entry about them, is the OLD value: respell those on a sibling of n's
     group. n's own left-hand faces ('n.§m' as a from) are the NEW value and stay.
     */
    private Map<Variable, Links> respellSelfReassignment(Map<Variable, Links> newLinks) {
        if (Gate.isSet("NOOLDVALUE")) return newLinks;
        Map<Variable, Links> res = newLinks;
        for (Map.Entry<Variable, Links> entry : newLinks.entrySet()) {
            Variable target = entry.getKey();
            boolean selfAssigned = false;
            for (Link l : entry.getValue().linkSet()) {
                selfAssigned |= l.linkNature() == LinkNatureImpl.IS_ASSIGNED_FROM && l.from().equals(target)
                                && !l.to().equals(target) && Util.isPartOf(target, l.to());
            }
            if (!selfAssigned) continue;
            Variable alias = graph.survivingAlias(target);
            if (alias == null) continue;
            VariableTranslationMap oldValue = new VariableTranslationMap(runtime).put(target, alias);
            Map<Variable, Links> next = new HashMap<>();
            for (Map.Entry<Variable, Links> e : res.entrySet()) {
                if (e.getKey().equals(target)) {
                    Links.Builder builder = new LinksImpl.Builder(target);
                    for (Link l : e.getValue().linkSet()) {
                        Variable to = Util.isPartOf(target, l.to()) ? oldValue.translateVariableRecursively(l.to())
                                : l.to();
                        builder.add(l.from(), l.linkNature(), to, l.mediated());
                    }
                    next.put(target, builder.build());
                } else {
                    Variable key = Util.isPartOf(target, e.getKey())
                            ? oldValue.translateVariableRecursively(e.getKey()) : e.getKey();
                    next.put(key, e.getValue().translate(oldValue));
                }
            }
            traceOldValue("direct", target, alias, res, next);
            res = next;
        }
        return res;
    }

    private record Reduce(Map<Variable, Links> newLinks, Variable clear) {
    }

    private Reduce reduceLinks(Map<Variable, Links> newLinks) {
        Variable source = null; // iis ← $__c0  == target ← source
        Variable target = null;
        Links skip = null;
        VariableTranslationMap vtm = new VariableTranslationMap(runtime);
        for (Map.Entry<Variable, Links> entry : newLinks.entrySet()) {
            Links links = entry.getValue();
            if (links.size() == 1) {
                Link link = links.link(0);
                if (link.linkNature() == LinkNatureImpl.IS_ASSIGNED_FROM
                    && link.to() instanceof IntermediateVariable iv
                    && intermediateVariablesRemoved.add(iv)) {
                    source = iv;
                    target = link.from();
                    skip = links;
                    vtm.put(source, target);
                    break;
                }
            }
        }
        if (source == null) return null;
        // 'node = node.next': until the reduction below, every spelling of 'target' (and of its faces) in this
        // statement's links is the OLD value, the new one is still the intermediate. compute() clears 'target'
        // before the links go in, so those spellings would land on fresh vertices, unrelated to anything: the
        // walk loses where it came from (CodeLaser/maddi-mod#72, 4a). Respell them on a sibling of target's
        // shared group, which survives the clear and carries the same facts.
        Variable oldValueAlias = Gate.isSet("NOOLDVALUE") ? null : graph.survivingAlias(target);
        VariableTranslationMap oldValue = oldValueAlias == null ? null
                : new VariableTranslationMap(runtime).put(target, oldValueAlias);
        Map<Variable, Links> res = new HashMap<>();
        for (Map.Entry<Variable, Links> entry : newLinks.entrySet()) {
            if (skip != entry.getValue()) {
                Variable key = entry.getKey();
                Links old = entry.getValue();
                if (oldValue != null && key != source && Util.isPartOf(target, key)) {
                    key = oldValue.translateVariableRecursively(key);
                }
                if (oldValue != null) old = old.translate(oldValue);
                Variable v = key == source ? target : key;
                Links links = old.translate(vtm);
                res.put(v, links);
            } else if (source instanceof IntermediateVariable iv && iv.isNewObject()
                       && Util.primary(target) instanceof ReturnVariable) {
                // the reduced pair 'method ← $__c(new URL(...))' carries the FRESH-OBJECT fact: a return
                // value that is a fresh (unanalyzable) object must not end up looking like a parameter
                // identity. Record it side-band for handleReturnVariable's '← $_v' marker — NOT as a graph
                // edge: a shared $_v graph vertex acts as a closure hub (TestParSeqLinkBench 0.9s -> 3-19s).
                graph.markFreshObjectReturn(Util.primary(target));
            }
        }
        if (oldValue != null) traceOldValue("reduce", target, oldValueAlias, newLinks, res);
        return new Reduce(res, target);
    }

    public static String vertexPrinter(Variable variable) {
        if (variable instanceof ParameterInfo pi) {
            return pi.isUnnamed() ? "_" : pi.index() + ":" + pi.name();
        }
        if (variable instanceof FieldReference fr && fr.scopeVariable() != null) {
            return vertexPrinter(fr.scopeVariable()) + "." + fr.fieldInfo().name();
        }
        return variable.simpleName();
    }

}
