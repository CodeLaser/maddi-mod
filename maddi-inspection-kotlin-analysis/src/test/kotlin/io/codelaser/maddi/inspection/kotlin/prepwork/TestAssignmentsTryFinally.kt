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

package io.codelaser.maddi.inspection.kotlin.prepwork

import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable
import io.codelaser.maddi.modification.prepwork.variable.VariableData
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kotlin port of `modification-prepwork` `TestAssignmentsTryFinally`, `return`-in-try-with-`finally` case: the
 * finally reassigns `x`, so try body (1.0.0), finally body (1.1.0) and the merge (1=M) all appear, and the
 * return variable is assigned at the `return x` inside the try (1.0.1) plus the finally merge.
 *
 * The Java suite's other two cases are Kotlin-inapplicable: multi-catch (`catch (A | B e)`) has no Kotlin form,
 * and the labeled-break case uses a C-style `for (int i = 0; ...)` whose block structure Kotlin's `for (i in …)`
 * does not reproduce.
 */
class TestAssignmentsTryFinally : CommonKotlinPrep() {

    private fun analyse(input: String): VariableData {
        val x = parse("X.kt", input)
        val m = x.findUniqueMethod("m", 1)
        doMethod(m)
        return VariableDataImpl.of(m)
    }

    private fun v(vd: VariableData, simpleName: String): VariableInfo =
        vd.variableInfoStream().filter { it.variable().simpleName() == simpleName }.findFirst().orElseThrow()

    private fun rv(vd: VariableData): VariableInfo =
        vd.variableInfoStream().filter { it.variable() is ReturnVariable }.findFirst().orElseThrow()

    private val returnInTryFinally = """
        package a.b
        class X {
            fun m(n: Int): Int {
                var x = 0
                try {
                    x = n / n
                    return x
                } finally {
                    x = -1
                }
            }
        }
    """.trimIndent()

    @Test
    fun testReturnInTryFinally() {
        val vd = analyse(returnInTryFinally)
        val x = v(vd, "x")
        // 0: init, 1.0.0: try body, 1.1.0: finally body, 1=M: merge
        assertEquals("D:0, A:[0, 1.0.0, 1.1.0, 1=M]", x.assignments().toString())
        assertEquals("1.0.1", x.reads().toString()) // 'return x' inside the try
        assertEquals("D:-, A:[1.0.1, 1=M]", rv(vd).assignments().toString())
    }
}
