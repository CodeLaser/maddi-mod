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

import io.codelaser.maddi.modification.prepwork.variable.VariableData
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl
import io.codelaser.maddi.cst.api.info.MethodInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kotlin port of `modification-prepwork` `TestAssignmentsConstructor`: `this(...)`/`super(...)` delegation
 * argument reads. Not ported: the `a = x; b = y` field-assignment case — Kotlin requires every property to
 * be initialized (at declaration or in the constructor), which would add an assignment the Java test does
 * not have.
 */
class TestAssignmentsConstructor : CommonKotlinPrep() {

    private fun analyse(constructor: MethodInfo): VariableData {
        doMethod(constructor)
        return VariableDataImpl.of(constructor)
    }

    private fun v(vd: VariableData, simpleName: String): VariableInfo =
        vd.variableInfoStream().filter { it.variable().simpleName() == simpleName }.findFirst().orElseThrow()

    @Test
    fun testThisDelegation() {
        // Java: `X(int a) { this(a, 0); } X(int a, int b) { }`
        val x = parse("X.kt", "package a.b\nclass X { constructor(a: Int) : this(a, 0); constructor(a: Int, b: Int) }")
        val vd = analyse(x.findConstructor(1))
        assertEquals("0", v(vd, "a").reads().toString())
    }

    @Test
    fun testSuperCall() {
        // Java: `static class P { P(int x) {} } static class C extends P { C(int a) { super(a); } }`
        val x = parse(
            "X.kt",
            """
            package a.b
            class X {
                open class P(x: Int)
                class C : P {
                    constructor(a: Int) : super(a)
                }
            }
            """.trimIndent()
        )
        val vd = analyse(x.findSubType("C", true).findConstructor(1))
        assertEquals("0", v(vd, "a").reads().toString())
    }
}
