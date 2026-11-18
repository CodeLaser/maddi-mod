package io.codelaser.maddi.modification.link.impl.linkgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.impl.localvar.SharedVariable;

/**
 * A group representative in the INDEX of an array access ('this.names[$__sv_i]') is expanded to the members, as one in
 * the array base is. It reached the stored links of 19 hibernate-core fields, where the writer refused it.
 */
public class TestExpandRepInIndex extends CommonTest {

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", """
                package a.b;
                class X { String[] names; }
                """);
        FieldInfo names = X.getFieldByName("names", true);
        Variable namesRef = runtime.newFieldReference(names);
        LocalVariable i = runtime.newLocalVariable("i", runtime.intParameterizedType());
        LocalVariable k = runtime.newLocalVariable("k", runtime.intParameterizedType());
        SharedVariable rep = new SharedVariable(SharedVariable.PREFIX + "i", runtime.intParameterizedType(), runtime);
        rep.variables().add(i);
        rep.variables().add(k);
        DependentVariable dv = runtime.newDependentVariable(runtime.newVariableExpression(namesRef),
                runtime.newVariableExpression(rep), runtime.stringParameterizedType());
        List<String> expanded = Graph.expandDependent(runtime, dv, Stream::of).map(Variable::toString).toList();
        assertEquals(List.of("this.names[i]", "this.names[k]"), expanded);

        // no rep anywhere: itself
        DependentVariable plain = runtime.newDependentVariable(runtime.newVariableExpression(namesRef),
                runtime.newVariableExpression(i), runtime.stringParameterizedType());
        assertEquals(List.of(plain), Graph.expandDependent(runtime, plain, Stream::of).toList());
    }
}
