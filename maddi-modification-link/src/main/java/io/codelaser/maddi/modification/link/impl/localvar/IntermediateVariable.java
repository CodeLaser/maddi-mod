package io.codelaser.maddi.modification.link.impl.localvar;

import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.info.InfoMapView;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.variable.LocalVariableImpl;
import io.codelaser.maddi.modification.link.impl.LinkVariable;

public class IntermediateVariable extends LocalVariableImpl implements LocalVariable, LinkVariable {
    public static final String PREFIX = "$__";

    public static final String LABEL_RETURN_VALUE = "rv";
    public static final String LABEL_NEW_OBJECT = "c";
    public static final String LABEL_INLINE_CONDITION = "ic";

    private static final String RV_PREFIX = PREFIX + LABEL_RETURN_VALUE;
    private static final String NEW_PREFIX = PREFIX + LABEL_NEW_OBJECT;

    IntermediateVariable(String name, ParameterizedType parameterizedType, Expression assignmentExpression) {
        super(PREFIX + name, parameterizedType, assignmentExpression);
    }

    public boolean isNewObject() {
        return simpleName().startsWith(NEW_PREFIX);
    }

    public boolean isReturnVariable() {
        return simpleName().startsWith(RV_PREFIX);
    }

    public static IntermediateVariable returnValue(Runtime runtime, int index, ParameterizedType parameterizedType) {
        return new IntermediateVariable(LABEL_RETURN_VALUE + index, parameterizedType, runtime.newEmptyExpression());
    }

    public static IntermediateVariable parameterValue(int index, ParameterizedType parameterizedType, Expression assignmentExpression) {
        return new IntermediateVariable(LABEL_RETURN_VALUE + index, parameterizedType, assignmentExpression);
    }

    public static IntermediateVariable newObject(Runtime runtime, int index, ParameterizedType parameterizedType) {
        return new IntermediateVariable(LABEL_NEW_OBJECT + index, parameterizedType, runtime.newEmptyExpression());
    }

    // also used for switch expressions
    public static IntermediateVariable inlineCondition(Runtime runtime, int index, ParameterizedType parameterizedType) {
        return new IntermediateVariable(LABEL_INLINE_CONDITION + index, parameterizedType, runtime.newEmptyExpression());
    }

    @Override
    public boolean acceptForLinkedVariables() {
        return false;
    }

    // an intermediate stays one on a rewired type; see MarkerVariable.rewire
    @Override
    public Variable rewire(InfoMapView infoMap) {
        return infoMap.rewired(this, () -> new IntermediateVariable(simpleName().substring(PREFIX.length()),
                parameterizedType().rewire(infoMap),
                assignmentExpression() == null ? null : assignmentExpression().rewire(infoMap)));
    }
}
