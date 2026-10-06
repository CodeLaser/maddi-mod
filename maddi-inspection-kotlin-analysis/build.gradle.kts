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

/*
 * TEST-ONLY host (split stage 3): the maddi-inspection-kotlin tests that run the prep analyzer on Kotlin CST --
 * ports of the Java prep-analyzer tests (Kotlin source in, the same VariableData assertion strings out) and the
 * analyzer smoke test. They test that the Kotlin CST feeds the analyzer faithfully, a
 * claim about both tiers; maddi-inspection-kotlin is base and cannot have maddi-mod on its test class path.
 * The test JVM is maddi-inspection-kotlin's: the flat front end (FlatFrontEndTestBootstrap), not the realm.
 * The two printer tests moved to maddi's maddi-inspection-kotlin (2026-10-06): they never needed the prep analyzer.
 */
plugins {
    kotlin("jvm") version "2.4.0"
}

// maddi (base) and maddi-mod modules are reached by coordinate; settings.gradle.kts includes their builds
val maddiVersion: String by project

group = "io.codelaser"

dependencies {
    testImplementation("io.codelaser:maddi-inspection-kotlin:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-api:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-resource:$maddiVersion")
    testImplementation("io.codelaser:maddi-kotlin-api:$maddiVersion")
    testImplementation("io.codelaser:maddi-kotlin-k2:$maddiVersion")
    testImplementation("io.codelaser:maddi-cst-api:$maddiVersion")
    testImplementation("io.codelaser:maddi-cst-impl:$maddiVersion")
    testImplementation("io.codelaser:maddi-cst-analysis:$maddiVersion")
    testImplementation("io.codelaser:maddi-cst-print:$maddiVersion")
    testImplementation("io.codelaser:maddi-cst-print-kotlin:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-openjdk:$maddiVersion")
    testImplementation("io.codelaser:maddi-callgraph:$maddiVersion")
    testImplementation(project(":maddi-modification-prepwork"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.3")
    testImplementation("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(
        "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25) }
}
