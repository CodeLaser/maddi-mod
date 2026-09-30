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
    `java-test-fixtures`
}

// maddi (base) and maddi-mod modules are reached by coordinate; settings.gradle.kts includes their builds
val maddiVersion: String by project

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
dependencies {
    api("io.codelaser:maddi-inspection-api:$maddiVersion")
    implementation("io.codelaser:maddi-graph:$maddiVersion")
    implementation("io.codelaser:maddi-util:$maddiVersion")
    implementation("io.codelaser:maddi-cst-analysis:$maddiVersion")
    implementation("io.codelaser:maddi-cst-print:$maddiVersion")

    testImplementation("io.codelaser:maddi-java-openjdk:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-resource:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-openjdk:$maddiVersion")
    testImplementation("org.slf4j:slf4j-api")
    testImplementation("org.jetbrains:annotations")
    testImplementation("org.junit.jupiter:junit-jupiter-api")

    testFixturesImplementation("io.codelaser:maddi-java-openjdk:$maddiVersion")
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
