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
    `java-test-fixtures`
}

// maddi (base) and maddi-mod modules are reached by coordinate; settings.gradle.kts includes their builds
val maddiVersion: String by project

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
dependencies {
    // CloneBenchCorpus asks Corpora where the testarchive corpus is
    testFixturesImplementation(testFixtures("io.codelaser:maddi-util:$maddiVersion"))

    api("io.codelaser:maddi-inspection-api:$maddiVersion")
    testFixturesImplementation("io.codelaser:maddi-annotation:$maddiVersion")
    implementation("io.codelaser:maddi-annotation:$maddiVersion")
    testFixturesImplementation("io.codelaser:maddi-cst-api:$maddiVersion")
    implementation("io.codelaser:maddi-cst-api:$maddiVersion")
    testFixturesImplementation("io.codelaser:maddi-support:$maddiVersion")
    implementation("io.codelaser:maddi-support:$maddiVersion")
    implementation("io.codelaser:maddi-graph:$maddiVersion")
    implementation("io.codelaser:maddi-util:$maddiVersion")
    implementation("io.codelaser:maddi-cst-analysis:$maddiVersion")
    implementation("io.codelaser:maddi-cst-print:$maddiVersion")

    testImplementation("io.codelaser:maddi-inspection-resource:$maddiVersion")
    testImplementation("org.jetbrains:annotations")
    testImplementation("org.junit.jupiter:junit-jupiter-api")

    testFixturesImplementation("io.codelaser:maddi-inspection-resource:$maddiVersion")
    testFixturesImplementation("io.codelaser:maddi-inspection-openjdk:$maddiVersion")
    testFixturesImplementation("org.slf4j:slf4j-api")
    testFixturesImplementation("org.jetbrains:annotations")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api")
}
tasks.withType<Test> {
    maxHeapSize = "2G"
    maxParallelForks = 4


    jvmArgs(
        "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )

}
