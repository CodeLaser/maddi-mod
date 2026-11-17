package io.codelaser.maddi.modification.link.impl;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;

interface LinkComputerRecursion {
    void doAnonymousType(TypeInfo typeInfo);

    /** see {@link io.codelaser.maddi.modification.link.LinkComputer#setLockComputeDisabled(boolean)} */
    default boolean lockComputeDisabled() {
        return false;
    }

    /**
     * CodeLaser/maddi-mod#127: {@code method} calls itself while its links are being computed. Null in the first
     * round (the call links nothing), the first round's summary in the second.
     */
    default MethodLinkedVariables selfSummary(MethodInfo method) {
        return null;
    }

    /** see {@link io.codelaser.maddi.modification.link.LinkComputer#recordSummaryConsumption} */
    default void recordSummaryConsumption(MethodInfo consumer, MethodInfo consumed) {
        // no-op by default
    }

    MethodLinkedVariables doMethod(MethodInfo methodInfo);

    MethodLinkedVariables recurseMethod(MethodInfo methodInfo);

    MethodLinkedVariables doMethodShallowDoNotWrite(MethodInfo methodInfo);
}
