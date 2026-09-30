/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
