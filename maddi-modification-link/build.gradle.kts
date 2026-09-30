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
    api("io.codelaser:maddi-cst-api:$maddiVersion")
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
