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

import io.codelaser.maddi.modification.prepwork.variable.Stage
import io.codelaser.maddi.modification.prepwork.variable.VariableData
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin port of `modification-prepwork` `TestAssignmentsEmpty`: a for-each whose body declares a local and
 * contains an empty `if` block. The merge of the empty block still tracks the local `n`. A Kotlin
 * `Array<String>` is the JVM reified array `String[]`, so it renders identically to the Java oracle.
 */
class TestAssignmentsEmpty : CommonKotlinPrep() {

    private val input1 = """
        package a.b
        class X {
            fun method(strings: Array<String>) {
                for (s in strings) {
                    val n = s.length
                    if (n > 0) { }
                }
            }
        }
    """.trimIndent()

    @Test
    fun test1() {
        val x = parse("X.kt", input1)
        val method = x.findUniqueMethod("method", 1)
        doMethod(method)
        val vdMethod: VariableData = VariableDataImpl.of(method)
        assertNotNull(vdMethod)

        assertEquals("a.b.X.method(String[]):0:strings, s", vdMethod.knownVariableNamesToString())

        val s0 = method.methodBody().statements().first()
        val vd0 = VariableDataImpl.of(s0)
        assertEquals("a.b.X.method(String[]):0:strings, s", vd0.knownVariableNamesToString())

        val s001 = s0.block().statements()[1]
        val vd001 = VariableDataImpl.of(s001)
        assertEquals("a.b.X.method(String[]):0:strings, n, s", vd001.knownVariableNamesToString())
        val vicN = vd001.variableInfoContainerOrNull("n")
        assertTrue(vicN.hasMerge())
        assertTrue(vicN.hasEvaluation())
        assertSame(vicN.best(Stage.EVALUATION), vicN.best(Stage.MERGE))
    }
}
