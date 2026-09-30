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
 * TEST-ONLY host (split stage 3): the maddi-run-kotlin tests that run the analysis. maddi-run-kotlin is base and
 * cannot have maddi-mod on even its test class path; these tests keep their package and run against the K2
 * realm exactly as they did there. The test JVM below is maddi-run-kotlin's, verbatim.
 */
plugins {
    id("java-library-conventions")
}

// maddi (base) and maddi-mod modules are reached by coordinate; settings.gradle.kts includes their builds
val maddiVersion: String by project

java {
    // 26, like maddi-run-kotlin: the Kotlin front-end modules are compiled to the daemon JDK's bytecode version
    sourceCompatibility = JavaVersion.VERSION_26
    targetCompatibility = JavaVersion.VERSION_26
}

// the jars that go INSIDE the realm; see maddi-run-kotlin/build.gradle.kts
val k2Runtime: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    k2Runtime("io.codelaser:maddi-kotlin-k2:$maddiVersion")

    testImplementation("io.codelaser:maddi-run-kotlin:$maddiVersion")
    testImplementation(project(":maddi-run-analysis"))
    testImplementation("io.codelaser:maddi-analysis-api:$maddiVersion")
    testImplementation("io.codelaser:maddi-callgraph:$maddiVersion")
    // the moved tests name these directly (the verdicts they compare, the codecs they round-trip)
    testImplementation(project(":maddi-modification-common"))
    testImplementation(project(":maddi-modification-prepwork"))
    testImplementation(project(":maddi-modification-analyzer"))
    testImplementation(project(":maddi-modification-link"))
    testImplementation("io.codelaser:maddi-run-openjdk:$maddiVersion")
    testImplementation("io.codelaser:maddi-run-config:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-api:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-resource:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-mixed:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-kotlin:$maddiVersion")
    testImplementation("io.codelaser:maddi-kotlin-api:$maddiVersion")
    testImplementation("io.codelaser:maddi-kotlin-realm:$maddiVersion")
    testImplementation("io.codelaser:maddi-cst-api:$maddiVersion")
    testImplementation("io.codelaser:maddi-cst-impl:$maddiVersion")
    testImplementation("io.codelaser:maddi-cst-analysis:$maddiVersion")
    testImplementation("io.codelaser:maddi-graph:$maddiVersion")
    testImplementation("io.codelaser:maddi-util:$maddiVersion")
    testImplementation(testFixtures("io.codelaser:maddi-util:$maddiVersion"))  // Corpora, the corpus locator
    testImplementation("org.junit.platform:junit-platform-launcher")   // K2RealmTestBootstrap
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("commons-cli:commons-cli")
}

val javacAddExports = listOf(
    "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
)

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(javacAddExports)
    System.getProperty("test.oss.root")?.let { systemProperty("test.oss.root", it) }
    System.getenv("TEST_OSS_ROOT")?.let { environment("TEST_OSS_ROOT", it) }
    System.getProperty("maddi.verdictDump")?.let { systemProperty("maddi.verdictDump", it) }
    System.getenv("MADDI_VERDICT_DUMP")?.let { systemProperty("maddi.verdictDump", it) }
    System.getProperty("maddi.placeholderDump")?.let { systemProperty("maddi.placeholderDump", it) }
    System.getenv("MADDI_PLACEHOLDER_DUMP")?.let { systemProperty("maddi.placeholderDump", it) }
    System.getProperty("maddi.libraryCallDump")?.let { systemProperty("maddi.libraryCallDump", it) }
    System.getProperty("maddi.memberVerdictDump")?.let { systemProperty("maddi.memberVerdictDump", it) }
    // ⭐ against the REALM, as the shipped CLI does: the compiler is never on the test JVM's own class path
    inputs.files(k2Runtime).withPropertyName("k2Runtime").withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dmaddi.k2.classpath=" + k2Runtime.asPath) })
    jvmArgs("-Xmx" + (System.getenv("TESTXMX") ?: "4G"))
}
