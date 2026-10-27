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
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl
import io.codelaser.maddi.cst.api.statement.LocalTypeDeclaration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kotlin port of `modification-prepwork` `TestLocalType.test2`: a method holds a local class whose method
 * captures the enclosing method's parameter (`t`) and reads a Java static field (`System.out`). The
 * `VariableData` of the local-type-declaration statement lists exactly the enclosing variables it captures.
 */
class TestLocalType : CommonKotlinPrep() {

    @Test
    fun test2() {
        val x = parse("X.kt", """
            package a.b
            class X {
                interface A { fun method(s: String) }
                fun make(t: String): A {
                    class C : A {
                        override fun method(s: String) {
                            System.out.println(s + t)
                        }
                    }
                    return C()
                }
            }
        """.trimIndent())
        PrepAnalyzer(runtime).doPrimaryType(x)

        val make = x.findUniqueMethod("make", 1)
        val ltd = make.methodBody().statements().first() as LocalTypeDeclaration
        val vd0 = VariableDataImpl.of(ltd)
        assertEquals("a.b.X.make(String):0:t, java.lang.System.out", vd0.knownVariableNamesToString())
    }
}
