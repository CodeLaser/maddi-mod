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
import io.codelaser.maddi.cst.api.info.MethodInfo
import io.codelaser.maddi.cst.api.info.TypeInfo
import io.codelaser.maddi.cst.api.runtime.Runtime
import io.codelaser.maddi.cst.impl.runtime.RuntimeImpl
import io.codelaser.maddi.inspection.resource.SourceSetImpl
import io.codelaser.maddi.kotlin.k2.KotlinScan
import java.net.URI

/**
 * Tier-1 cross-language validation: the Kotlin analogue of `modification-prepwork`'s `CommonTest`. Each
 * subclass ports a Java prep-analyzer test — the SAME behaviour expressed in Kotlin source — and asserts the
 * SAME [io.codelaser.maddi.modification.prepwork.variable.VariableData] result. The Java test's assertion
 * strings (e.g. `"D:0, A:[0, 1]"`) are the oracle: if the Kotlin front-end produces a faithful CST (same
 * statement indices, same reads/assignments), the analyzer yields the identical string.
 *
 * A ported method usually becomes `class X { fun method(...) { … } }` (a Java `static` method needs no
 * receiver, but the instance form is fine — the assignment-string assertions ignore the extra `this`).
 */
abstract class CommonKotlinPrep {
    protected val runtime: Runtime = RuntimeImpl()
    private val sourceSet = SourceSetImpl.Builder().setName("source").setUri(URI.create("file:/")).build()

    /** Parse Kotlin [source]; return the first (primary) type. */
    protected fun parse(fileName: String, source: String): TypeInfo =
        KotlinScan(runtime, sourceSet).parse(fileName, source).first()

    /** Run the prep analyzer on a single [method] (mirrors the Java tests' `analyzer.doMethod(method)`). */
    protected fun doMethod(method: MethodInfo) = PrepAnalyzer(runtime).doMethod(method)
}
