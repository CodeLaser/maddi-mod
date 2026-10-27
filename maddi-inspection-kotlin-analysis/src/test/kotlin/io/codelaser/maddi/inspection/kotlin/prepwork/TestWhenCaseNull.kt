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
 * Kotlin port of `modification-prepwork` `TestAssignmentsSwitchNullGuard`, `testCaseNull`: a `when` on a nullable
 * selector with a `null` arm, a value arm, and an `else`, each arm block-bodied and returning. The three block
 * arms index `0.0.0` / `0.1.0` / `0.2.0` and merge at `0=M`; the selector is read once at evaluation (`0-E`).
 *
 * The Java suite's `testGuardedPattern` case (`case Integer i when i > 0`) is N/A: it uses an instanceof-pattern
 * variable, which Kotlin's `is` does not bind (it smart-casts the selector instead).
 */
class TestWhenCaseNull : CommonKotlinPrep() {

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

    @Test
    fun testCaseNull() {
        val vd = analyse("""
            package a.b
            class X {
                fun m(s: String?): Int {
                    when (s) {
                        null -> { return -1 }
                        "x" -> { return 1 }
                        else -> { return 0 }
                    }
                }
            }
        """.trimIndent())
        assertEquals("0-E", v(vd, "s").reads().toString())
        assertEquals("D:-, A:[0.0.0, 0.1.0, 0.2.0, 0=M]", rv(vd).assignments().toString())
    }
}
