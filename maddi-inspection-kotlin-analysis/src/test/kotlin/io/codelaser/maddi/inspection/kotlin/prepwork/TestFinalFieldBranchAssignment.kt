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

import io.codelaser.maddi.modification.prepwork.PrepAnalyzer
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl
import io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin counterpart of `modification-prepwork` `TestFinalFieldBranchAssignment`. That Java test proves that the
 * effectively-final computation detects an assignment made *outside construction* — in a `switch`-branch, a
 * lambda body, or an anonymous-object method — and demotes the field to non-final, in contrast with its control
 * (a private field assigned only in the constructor, which stays effectively final).
 *
 * A private `var` with default accessors has no setter on the JVM, and the K2 front end no longer synthesizes one
 * (maddi-mod#145). Before, that `setI` assigned the field outside construction, so every Kotlin `var` was non-final
 * whatever else happened to it, and the three cases below could not fail. Now they are the Java test's checks:
 * [testVarWithConstructorAssignmentOnly] is the control that stays final, and a `when` branch, a lambda and an
 * `object :` method each demote it.
 */
class TestFinalFieldBranchAssignment : CommonKotlinPrep() {

    private fun isFinal(input: String): Boolean {
        val x = parse("X.kt", input)
        PrepAnalyzer(runtime).doPrimaryType(x)
        val i = x.getFieldByName("i", true)
        assertTrue(i.analysis().haveAnalyzedValueFor(PropertyImpl.FINAL_FIELD), "FINAL_FIELD not computed")
        return i.analysis().getOrDefault(PropertyImpl.FINAL_FIELD, FALSE).isTrue
    }

    @Test
    fun testValIsFinal() {
        // control: a `val` synthesizes no setter, so nothing assigns it outside construction -> final
        assertTrue(isFinal("""
            package a.b
            class X {
                private val i: Int
                constructor(iv: Int) { this.i = iv }
            }
        """.trimIndent()))
    }

    @Test
    fun testVarWithConstructorAssignmentOnly() {
        // control: a private `var` assigned only in the constructor is effectively final, as the Java field is
        assertTrue(isFinal("""
            package a.b
            class X {
                private var i: Int
                constructor(iv: Int) { this.i = iv }
            }
        """.trimIndent()))
    }

    @Test
    fun testWhenExpressionBranch() {
        assertFalse(isFinal("""
            package a.b
            class X {
                private var i: Int
                constructor(iv: Int) { this.i = iv }
                fun mutate(v: Int) {
                    val x = when (v) { 1 -> { this.i = 1; 1 } else -> 2 }
                    println(x)
                }
            }
        """.trimIndent()))
    }

    @Test
    fun testLambdaBody() {
        assertFalse(isFinal("""
            package a.b
            class X {
                private var i: Int
                constructor(iv: Int) { this.i = iv }
                fun mutate() {
                    val r = Runnable { this.i = 1 }
                    r.run()
                }
            }
        """.trimIndent()))
    }

    @Test
    fun testAnonymousObjectMethod() {
        assertFalse(isFinal("""
            package a.b
            class X {
                private var i: Int
                constructor(iv: Int) { this.i = iv }
                fun mutate() {
                    val r = object : Runnable { override fun run() { i = 1 } }
                    r.run()
                }
            }
        """.trimIndent()))
    }
}
