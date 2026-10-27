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


plugins {
    id("java-library-conventions")
}

// maddi (base) and maddi-mod modules are reached by coordinate; settings.gradle.kts includes their builds
val maddiVersion: String by project
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
dependencies {
    api("io.codelaser:maddi-cst-api:$maddiVersion")
    implementation("io.codelaser:maddi-support:$maddiVersion")
    api("io.codelaser:maddi-inspection-api:$maddiVersion")
    implementation("io.codelaser:maddi-graph:$maddiVersion")
    implementation("io.codelaser:maddi-util:$maddiVersion")
    implementation("io.codelaser:maddi-cst-analysis:$maddiVersion")
    implementation("io.codelaser:maddi-cst-io:$maddiVersion")
    implementation("io.codelaser:maddi-cst-impl:$maddiVersion")

    implementation(project(":maddi-modification-prepwork"))
    implementation(project(":maddi-modification-common"))
    implementation("io.codelaser:maddi-inspection-parser:$maddiVersion")

    testRuntimeOnly("io.codelaser:maddi-aapi-archive:$maddiVersion")
    testImplementation("io.codelaser:maddi-cst-print:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-parser:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-integration:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-resource:$maddiVersion")
    testImplementation("io.codelaser:maddi-java-bytecode:$maddiVersion")
    testImplementation("io.codelaser:maddi-java-parser:$maddiVersion")
    testImplementation(project(":maddi-aapi-parser"))

    testImplementation("io.codelaser:maddi-inspection-openjdk:$maddiVersion")
    testImplementation("io.codelaser:maddi-java-openjdk:$maddiVersion")

    testImplementation(testFixtures(project(":maddi-modification-common")))
    // the Kotlin front end, flat on the test class path, and the mixed parse that feeds it the JDK: package
    // `kotlin` links Kotlin input and compares it with the Java twin of each fixture. Test-only.
    testImplementation("io.codelaser:maddi-kotlin-k2:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-mixed:$maddiVersion")
}

tasks.withType<Test> {
    maxHeapSize = "2G"
    maxParallelForks = (findProperty("testForks") as String?)?.toInt() ?: 4
    // forkEvery=0 (gradle default): one JVM per fork runs all its classes. The test suite is deterministic in this
    // mode — verified by three identical parallel runs plus serial==monolith==isolated (0 flips). An earlier
    // forkEvery=1 (fresh JVM per class) was added on the belief the suite was order-unstable; that was a
    // measurement artifact (inconsistent HTML-entity decoding when diffing two runs), and forkEvery=1 only cost
    // ~20% wall time (261s vs 216s). Keep it configurable in case the known intermittent javac SharedNameTable
    // issue (see -XDuseUnsharedTable below) ever needs a per-class reset: -PforkEvery=1.
    forkEvery = (findProperty("forkEvery") as String?)?.toLong() ?: 0L

    jvmArgs(
        "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )
}
