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
import io.codelaser.maddi.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.EMPTY_PART_OF_CONSTRUCTION
import io.codelaser.maddi.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kotlin port of `modification-prepwork` `callgraph/TestComputePartOfConstruction` test3/test4: a method
 * (`update()`) called from the constructor AND from a lambda / anonymous-class body inside another method is
 * NOT part-of-construction (it is reachable outside construction). This directly probes whether the Kotlin
 * front-end represents the call made inside a lambda / anonymous body — if it does not, `update()` would
 * appear constructor-only and wrongly land in PART_OF_CONSTRUCTION.
 */
class TestComputePartOfConstruction : CommonKotlinPrep() {

    private fun partOfConstruction(source: String): String {
        val x = parse("X.kt", source)
        PrepAnalyzer(runtime).doPrimaryType(x)
        return x.analysis().getOrDefault(PART_OF_CONSTRUCTION, EMPTY_PART_OF_CONSTRUCTION).infoSet().toString()
    }

    @Test
    fun test3_lambda() {
        assertEquals(
            "[a.b.X.<init>()]",
            partOfConstruction(
                """
                package a.b
                class X {
                    private var i: Int = 0
                    constructor() { update() }
                    fun init() { val r = { update() }; r() }
                    private fun update() { ++i }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun test4_anonymous() {
        assertEquals(
            "[a.b.X.<init>()]",
            partOfConstruction(
                """
                package a.b
                class X {
                    private var i: Int = 0
                    constructor() { update() }
                    fun init() {
                        val r = object : Runnable { override fun run() { update() } }
                        r.run()
                    }
                    private fun update() { ++i }
                }
                """.trimIndent()
            )
        )
    }
}
