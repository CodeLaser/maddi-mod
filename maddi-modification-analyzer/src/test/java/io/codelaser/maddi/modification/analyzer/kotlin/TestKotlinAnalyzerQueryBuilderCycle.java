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

package io.codelaser.maddi.modification.analyzer.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.TypeInfo;

/**
 * CodeLaser/maddi-mod#140: Exposed's QueryBuilder/Expression cycle, in the form CodeLaser/maddi#98's front end
 * produces ({@code append(value) = value.toQueryBuilder(this)}). Implementations hand their sub-expressions back to
 * {@code append} from inside {@code queryBuilder { ... }}, a lambda with receiver that {@code invoke} calls.
 */
public class TestKotlinAnalyzerQueryBuilderCycle extends CommonKotlinAnalyzerTest {

    private static final String KOTLIN = """
            package k

            class QueryBuilder(val prepared: Boolean) {
                private val internalBuilder = StringBuilder()
                operator fun invoke(body: QueryBuilder.() -> Unit): Unit = body()
                fun append(value: String): QueryBuilder { internalBuilder.append(value); return this }
                fun append(value: Expression<*>): QueryBuilder { value.toQueryBuilder(this); return this }
                operator fun String.unaryPlus(): QueryBuilder = append(this)
                operator fun Expression<*>.unaryPlus(): QueryBuilder = append(this)
                override fun toString(): String = internalBuilder.toString()
            }

            abstract class Expression<T> {
                abstract fun toQueryBuilder(queryBuilder: QueryBuilder)
                override fun toString(): String = QueryBuilder(false).append(this).toString()
            }

            class Literal(val value: String) : Expression<String>() {
                override fun toQueryBuilder(queryBuilder: QueryBuilder) { queryBuilder.append(value) }
            }

            class NoOpConversion<T>(val expr: Expression<T>) : Expression<T>() {
                override fun toQueryBuilder(queryBuilder: QueryBuilder): Unit = queryBuilder { +expr }
            }

            class EqOp(val left: Expression<*>, val right: Expression<*>) : Expression<Boolean>() {
                override fun toQueryBuilder(queryBuilder: QueryBuilder) { queryBuilder.append(left); queryBuilder.append("="); queryBuilder.append(right) }
            }
            """;

    /*
     the seed, as in Exposed: CustomEnumerationColumnType.notNullValueToDB = toDb(value) invokes a function-typed field,
     which modifies it (as Java's Function.apply does), and the dispatch union makes IColumnType.valueToString modifying
     */
    private static final String SEED = """
            interface IColumnType {
                fun valueToString(value: Any?): String
            }

            class EnumColumnType(val toDb: (Any?) -> String) : IColumnType {
                override fun valueToString(value: Any?): String = toDb(value)
            }

            class LiteralOp(val columnType: IColumnType, val value: Any?) : Expression<Any>() {
                override fun toQueryBuilder(queryBuilder: QueryBuilder): Unit = queryBuilder { +columnType.valueToString(value) }
            }
            """;

    private static String verdicts(String kotlin, boolean seeded) {
        Analyzed a = analyze(List.of("X.kt", kotlin), List.of(), true);
        TypeInfo qb = find(a.kotlinTypes(), "k.QueryBuilder");
        String append = qb.methods().stream().filter(m -> "append".equals(m.name())
                        && "Expression".equals(m.parameters().getFirst().parameterizedType().typeInfo().simpleName()))
                .map(CommonKotlinAnalyzerTest::method).findFirst().orElseThrow();
        return append
               + "\n" + verdicts(find(a.kotlinTypes(), "k.Expression"), "toQueryBuilder")
               + "\n" + verdicts(find(a.kotlinTypes(), "k.NoOpConversion"), "toQueryBuilder", "expr")
               + "\n" + verdicts(find(a.kotlinTypes(), "k.EqOp"), "toQueryBuilder", "left")
               + (seeded ? "\n" + verdicts(find(a.kotlinTypes(), "k.LiteralOp"), "toQueryBuilder", "columnType") : "");
    }

    @DisplayName("the cycle through the abstract toQueryBuilder resolves to unmodified when no implementation modifies")
    @Test
    public void cycleResolves() {
        assertEquals("""
                method append: nonModifying=false @Independent | 0: unmodified=true @Independent
                type Expression: @Immutable(hc=true) @Independent
                method toQueryBuilder: nonModifying=true @Independent | 0: unmodified=false @Independent
                type NoOpConversion: @Immutable(hc=true) @Independent(hc=true)
                field expr: final=true unmodified=true @Independent(hc=true)
                method toQueryBuilder: nonModifying=true @Independent | 0: unmodified=false @Independent
                type EqOp: @Immutable(hc=true) @Independent(hc=true)
                field left: final=true unmodified=true @Independent(hc=true)
                method toQueryBuilder: nonModifying=true @Independent | 0: unmodified=false @Independent""",
                verdicts(KOTLIN, false));
    }

    /*
     PIN of #140's Exposed outcome: one implementation that modifies its receiver makes the abstract toQueryBuilder
     modifying, append's parameter with it, and through append every expression that holds a sub-expression. Sound
     under the current semantics of invoking a function value; changes only if that semantics does (#140).
     */
    @DisplayName("one modifying implementation carries through the cycle to every expression")
    @Test
    public void oneModifyingImplementation() {
        assertEquals("""
                method append: nonModifying=false @Independent | 0: unmodified=false @Independent
                type Expression: @FinalFields @Independent
                method toQueryBuilder: nonModifying=false @Independent | 0: unmodified=false @Independent
                type NoOpConversion: @FinalFields @Dependent
                field expr: final=true unmodified=false @Dependent
                method toQueryBuilder: nonModifying=false @Independent | 0: unmodified=false @Independent
                type EqOp: @FinalFields @Dependent
                field left: final=true unmodified=false @Dependent
                method toQueryBuilder: nonModifying=false @Independent | 0: unmodified=false @Independent
                type LiteralOp: @FinalFields @Dependent
                field columnType: final=true unmodified=false @Dependent
                method toQueryBuilder: nonModifying=false @Independent | 0: unmodified=false @Independent""",
                verdicts(KOTLIN.replace("class EqOp(", SEED + "\nclass EqOp("), true));
    }
}
