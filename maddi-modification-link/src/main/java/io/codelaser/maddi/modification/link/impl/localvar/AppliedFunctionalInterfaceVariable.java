package io.codelaser.maddi.modification.link.impl.localvar;

import java.util.List;

import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.modification.link.impl.Gate;
import io.codelaser.maddi.modification.link.impl.LinkVariable;
import io.codelaser.maddi.modification.link.impl.Result;

public class AppliedFunctionalInterfaceVariable extends MarkerVariable implements LocalVariable, LinkVariable {
    public static final String LABEL = "afi";

    private final List<Result> params;
    private final ParameterInfo sourceOfFunctionalInterface;


    public AppliedFunctionalInterfaceVariable(Runtime runtime,
                                              int index,
                                              ParameterizedType parameterizedType,
                                              ParameterInfo sourceOfFunctionalInterface,
                                              List<Result> params) {
        super(PREFIX + LABEL + index, parameterizedType, runtime.newEmptyExpression());
        this.params = params;
        this.sourceOfFunctionalInterface = sourceOfFunctionalInterface;
    }

    public AppliedFunctionalInterfaceVariable(String name,
                                              ParameterizedType parameterizedType,
                                              Runtime runtime,
                                              ParameterInfo sourceOfFunctionalInterface,
                                              List<Result> params) {
        super(name, parameterizedType, runtime.newEmptyExpression());
        this.params = params;
        this.sourceOfFunctionalInterface = sourceOfFunctionalInterface;
    }

    @Override
    public boolean acceptForLinkedVariables() {
        return params().stream().allMatch(AppliedFunctionalInterfaceVariable::acceptForLinkedVariables);
    }

    /*
     Gate STRICTAFI: also require the primary and the source of every link to be accepted. The default looks at the
     targets of the links only, and a parameter's result whose links start at an engine-internal variable ($__rv1, the
     result of a call inside the lambda) passes it: the variable reaches the method's summary, where no caller can
     resolve it and no stored analysis can write it (guava: 22 summaries, which a checkpoint skips). Not the default:
     on guava (--parallel) it made two independence verdicts vary from run to run (TableCollectors
     .ImmutableTableCollectorState.table, CycleDetectingLockFactory.LockGraphNode.allowedPriorLocks; 2 of 4 runs,
     against 0 of 4 without it, 2026-11-12).
     */
    private static final boolean STRICT = Gate.isSet("STRICTAFI");

    private static boolean acceptForLinkedVariables(Result p) {
        if (STRICT) {
            return p.links() == null
                   || p.links().primary() == null
                   || LinkVariable.acceptForLinkedVariables(p.links().primary())
                      && p.links().stream().allMatch(l -> LinkVariable.acceptForLinkedVariables(l.from())
                                                          && LinkVariable.acceptForLinkedVariables(l.to()));
        }
        return p.links() == null
               || p.links().primary() == null
               || p.links().isEmpty() && LinkVariable.acceptForLinkedVariables(p.links().primary())
               || p.links().stream().allMatch(l -> LinkVariable.acceptForLinkedVariables(l.to()));
    }

    public List<Result> params() {
        return params;
    }

    public ParameterInfo sourceOfFunctionalInterface() {
        return sourceOfFunctionalInterface;
    }
}
