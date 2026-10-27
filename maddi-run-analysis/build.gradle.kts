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

/*
 * The implementation of maddi-analysis-api's AnalysisEngine (split stage 3): the one module of maddi-mod that
 * the other tiers load at run time. The base tier's drivers and the refactor engine never compile against it;
 * the ext tier ships it in runtimeOnly / shade configurations. It also hosts every test that runs the analysis
 * through a driver, because a base module cannot have maddi-mod on even its test class path.
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
    api("io.codelaser:maddi-analysis-api:$maddiVersion")
    testImplementation("io.codelaser:maddi-annotation:$maddiVersion")
    implementation("io.codelaser:maddi-cst-api:$maddiVersion")
    implementation("io.codelaser:maddi-inspection-api:$maddiVersion")
    testImplementation("io.codelaser:maddi-support:$maddiVersion")
    implementation("io.codelaser:maddi-util:$maddiVersion")
    implementation("io.codelaser:maddi-callgraph:$maddiVersion")
    implementation("io.codelaser:maddi-cst-analysis:$maddiVersion")
    implementation(project(":maddi-modification-common"))
    implementation(project(":maddi-modification-prepwork"))
    implementation(project(":maddi-modification-link"))
    implementation(project(":maddi-modification-analyzer"))
    implementation(project(":maddi-aapi-parser"))

    // ---- tests: every driver test that RUNS the analysis (split stage 3). They moved here from maddi-run-openjdk,
    // maddi-run-main and maddi-run-config, which are base and cannot have this module on even their test class
    // path; they keep their packages. The class path below is what they had there, plus this module.
    testImplementation("io.codelaser:maddi-run-openjdk:$maddiVersion")
    testImplementation(testFixtures("io.codelaser:maddi-util:$maddiVersion"))  // Corpora, the corpus locator
    testImplementation("io.codelaser:maddi-cst-impl:$maddiVersion")  // DeclaredNullability, the nullability oracle
    testImplementation("io.codelaser:maddi-run-main:$maddiVersion")
    testImplementation("io.codelaser:maddi-run-config:$maddiVersion")
    testImplementation("io.codelaser:maddi-graph:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-openjdk:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-resource:$maddiVersion")
    testImplementation("io.codelaser:maddi-inspection-integration:$maddiVersion")
    testRuntimeOnly("io.codelaser:maddi-aapi-archive:$maddiVersion")
    // TestJavaToKotlinFernflowerNullability: maddi's translation ratchet, with the NullabilityPass verdicts
    testImplementation(testFixtures("io.codelaser:maddi-run-openjdk:$maddiVersion"))
    testImplementation("io.codelaser:maddi-cst-print-kotlin:$maddiVersion")
    // TestJavaToCSharpFernflowerFacts: maddi's C# ratchet, with the analysis's facts
    testImplementation("io.codelaser:maddi-cst-print-csharp:$maddiVersion")
    testImplementation("commons-cli:commons-cli")
    testImplementation("ch.qos.logback:logback-classic")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
}

// ---- the test JVM of maddi-run-openjdk, verbatim, for the tests that moved from there

// TestEventualRatchet and the dogfood input configuration it analyses moved to maddi-gradleplugin (split stage 5):
// the dogfood build applies that plugin, and the ratchet reaches the analysis through the engine interface.

val javacAddExports = listOf(
    "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
)

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(javacAddExports)
}

tasks.test {
    useJUnitPlatform()
    // -PnoAssertions disables JVM -ea (production-like linking benchmarks)
    enableAssertions = !project.hasProperty("noAssertions")
    System.getProperty("test.oss.root")?.let { systemProperty("test.oss.root", it) }
    System.getenv("TEST_OSS_ROOT")?.let { environment("TEST_OSS_ROOT", it) }
    // 12G: the elasticsearch-server closure under PARALLEL=8; TESTXMX overrides (see maddi-run-openjdk)
    jvmArgs("-Xmx" + (System.getenv("TESTXMX") ?: "12G"))
    jvmArgs(javacAddExports)
    System.getenv("ASPROF")?.let {
        jvmArgs(
            "-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=$it",
            "-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints"
        )
    }
}

// TestJavaToKotlinFernflowerNullability: the Kotlin compiler judging the translation runs in a child JVM, from a
// configuration of its own, as in maddi-run-openjdk (see the comment there). 2.4.0: maddi-kotlin-k2's Kotlin.
val kotlinCompiler = configurations.create("kotlinCompiler") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies {
    kotlinCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.0")
}
// withType, not tasks.test: slowTest copies test's jvmArgs and system properties, not its argument providers.
tasks.withType<Test>().configureEach {
    inputs.files(kotlinCompiler).withPropertyName("kotlinCompiler")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dmaddi.test.kotlinCompilerClasspath=" + kotlinCompiler.asPath)
    })
}
// ... and the corpus's own tests, with JUnit's console launcher (see maddi-run-openjdk).
val junitConsole = configurations.create("junitConsole") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies {
    junitConsole("org.junit.platform:junit-platform-console:6.0.3")
    junitConsole("org.junit.jupiter:junit-jupiter-engine:6.0.3")
}
// JUnit 3 and 4 tests (guava's) need the vintage engine too, as in maddi-run-openjdk; without it the launcher finds
// none of guava's tests, and testsPassing reads 0
val junitVintage = configurations.create("junitVintage") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies {
    junitVintage("org.junit.vintage:junit-vintage-engine:6.0.3")
}
tasks.withType<Test>().configureEach {
    inputs.files(junitConsole).withPropertyName("junitConsole")
    inputs.files(junitVintage).withPropertyName("junitVintage")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dmaddi.test.junitConsoleClasspath=" + junitConsole.asPath,
               "-Dmaddi.test.junitVintageClasspath=" + junitVintage.asPath)
    })
}
// TEST_LANE=<name>: results and reports of their own, so that two Gradle builds can run ratchets side by side in one
// checkout (guava's J2K ratchet in one lane, the other corpora in a second) without writing each other's binary
// results. The ratchets' own output (build/j2k/<corpus>, the oracle's .tsv) is per corpus already.
System.getenv("TEST_LANE")?.let { lane ->
    tasks.withType<Test>().configureEach {
        binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/$name-$lane/binary"))
        reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/$name-$lane"))
        reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/$name-$lane"))
    }
}
// J2K_COMPILE_ONLY=true: the J2K ratchets translate and compile, and run none of the corpus's tests (a working
// cycle; see JavaToKotlinRatchet.COMPILE_ONLY)
tasks.withType<Test>().configureEach {
    systemProperty("maddi.j2k.compileOnly", System.getenv("J2K_COMPILE_ONLY") ?: "")
}

// TestJavaToCSharpFernflowerFacts: the judge of the C# is maddi's tools/csharp-check (see maddi-run-openjdk), which
// JavaToCSharpRatchet builds with `dotnet build` (a .NET 10 SDK on the PATH). Its directory, as a system property.
val csharpCheck = rootProject.file("../maddi/tools/csharp-check")
tasks.withType<Test>().configureEach {
    inputs.dir(csharpCheck).withPropertyName("csharpCheck").optional()
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dmaddi.test.csharpCheck=" + csharpCheck.absolutePath)
    })
}
