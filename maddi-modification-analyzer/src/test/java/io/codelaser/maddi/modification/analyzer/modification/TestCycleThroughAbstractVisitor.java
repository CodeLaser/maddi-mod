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

package io.codelaser.maddi.modification.analyzer.modification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#140: Exposed's {@code QueryBuilder.append(expr) = expr.toQueryBuilder(this)}. The abstract
 * {@code Expression.toQueryBuilder} has implementations that hand their sub-expressions back to {@code append}, so
 * the modification of {@code append}'s parameter runs through a cycle over an abstract method. No implementation
 * modifies an expression; the builder is the only object written.
 */
public class TestCycleThroughAbstractVisitor extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class Q {
                static abstract class Expression {
                    abstract void toQueryBuilder(QueryBuilder qb);
                }
                static final class QueryBuilder {
                    private final StringBuilder sb = new StringBuilder();
                    QueryBuilder append(Expression expr) { expr.toQueryBuilder(this); return this; }
                    QueryBuilder append(String s) { sb.append(s); return this; }
                }
                static final class Literal extends Expression {
                    private final String value;
                    Literal(String value) { this.value = value; }
                    @Override void toQueryBuilder(QueryBuilder qb) { qb.append(value); }
                }
                static final class EqOp extends Expression {
                    private final Expression left;
                    private final Expression right;
                    EqOp(Expression left, Expression right) { this.left = left; this.right = right; }
                    @Override void toQueryBuilder(QueryBuilder qb) { qb.append(left); qb.append("="); qb.append(right); }
                }
                static final class IsNull extends Expression {
                    private final Expression e;
                    IsNull(Expression e) { this.e = e; }
                    @Override void toQueryBuilder(QueryBuilder qb) { qb.append(e).append(" IS NULL"); }
                }
            }
            """;

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @DisplayName("a modification cycle through an abstract method leaves the expressions unmodified")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.Q", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        TypeInfo qb = X.findSubType("QueryBuilder");
        TypeInfo expression = X.findSubType("Expression");
        var appendExpr = qb.methods().stream().filter(m -> "append".equals(m.name())
                && m.parameters().getFirst().parameterizedType().typeInfo() == expression).findFirst().orElseThrow();
        String s = "append.M=" + appendExpr.isNonModifying()
                   + " append.expr=" + appendExpr.parameters().getFirst().isUnmodified()
                   + " Expression.M=" + method(expression, "toQueryBuilder").isNonModifying()
                   + " Expression.qb=" + method(expression, "toQueryBuilder").parameters().getFirst().isUnmodified()
                   + " EqOp.M=" + method(X.findSubType("EqOp"), "toQueryBuilder").isNonModifying()
                   + " IsNull.M=" + method(X.findSubType("IsNull"), "toQueryBuilder").isNonModifying();
        assertEquals("append.M=false append.expr=true Expression.M=true Expression.qb=false EqOp.M=true IsNull.M=true", s);
    }
}
