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
    api("io.codelaser:maddi-analysis-api:$maddiVersion")  // AnalysisValueFeed, in IteratingAnalyzer's signature (split stage 3)
    implementation("io.codelaser:maddi-annotation:$maddiVersion")
    implementation("io.codelaser:maddi-cst-api:$maddiVersion")
    implementation("io.codelaser:maddi-support:$maddiVersion")
    implementation("io.codelaser:maddi-callgraph:$maddiVersion")  // ComputeCallGraph & co., moved out of prepwork (split stage 2)
    api("io.codelaser:maddi-inspection-api:$maddiVersion")
    implementation("io.codelaser:maddi-graph:$maddiVersion")
    implementation("io.codelaser:maddi-util:$maddiVersion")
    implementation("io.codelaser:maddi-cst-analysis:$maddiVersion")
    implementation(project(":maddi-modification-common"))
    implementation(project(":maddi-modification-prepwork"))
    implementation(project(":maddi-modification-link"))

    testImplementation("io.codelaser:maddi-cst-impl:$maddiVersion")
    testImplementation("io.codelaser:maddi-cst-io:$maddiVersion")
    testImplementation("io.codelaser:maddi-cst-print:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-parser:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-integration:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-resource:$maddiVersion")
    // TestMockitoVerify: Mockito on the class path of that one test (CommonTest.extraClassPathParts), for its hints
    testImplementation("org.mockito:mockito-core:5.23.0")
    // TestJacksonCommonsIo, the same
    testImplementation("commons-io:commons-io:2.20.0")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
    testImplementation("io.codelaser:maddi-java-bytecode:$maddiVersion")
    testImplementation("io.codelaser:maddi-java-parser:$maddiVersion")

    testImplementation("io.codelaser:maddi-inspection-openjdk:$maddiVersion")
    testImplementation("io.codelaser:maddi-java-openjdk:$maddiVersion")

    testImplementation(testFixtures(project(":maddi-modification-common")))

    testRuntimeOnly("io.codelaser:maddi-aapi-archive:$maddiVersion")
    // the Kotlin front end, flat on the test class path, and the mixed parse that feeds it the JDK: package
    // `kotlin` analyzes Kotlin input and compares its verdicts with the Java twin of each fixture. Test-only.
    testImplementation("io.codelaser:maddi-kotlin-k2:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-mixed:$maddiVersion")
}
tasks.withType<Test> {
    // pass the clone-bench corpus location through to the test JVM, as run-openjdk does for test.oss.root
    System.getProperty("testarchive.root")?.let { systemProperty("testarchive.root", it) }
    System.getenv("TESTARCHIVE_ROOT")?.let { environment("TESTARCHIVE_ROOT", it) }

    maxHeapSize = "2G"
    maxParallelForks = 4

    // forward the TestCloneBench parallelism knob to the forked test JVM (each worker builds its own inspector/
    // runtime; keep it modest so the 2G heap is not exceeded). Default lives in the test itself.
    // TestCloneBench is now @Tag("slow"): the default `test` task skips it, `slowTest` runs it.
    System.getProperty("clonebench.parallelism")?.let { systemProperty("clonebench.parallelism", it) }

    jvmArgs(
        "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )
}
