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

// maddi-mod: the modification analysis (prepwork, link, analyzer, the analysis-hints parser) and the
// implementation of maddi's AnalysisEngine service. It depends on maddi (base) only, which it builds from source
// through the sibling checkout ../maddi. See ../maddi/docs/roadmap/split-maddi-into-three-repositories.md.

pluginManagement {
    // java-library-conventions and maddi-tier-guard, shared with maddi and maddi-dist
    includeBuild("../maddi/build-logic")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Kotlin K2 Analysis API ('*-for-ide' artifacts, through maddi-kotlin-k2) -- not on Maven Central
        maven(url = "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        maven(url = "https://www.jetbrains.com/intellij-repository/releases")
        maven(url = "https://cache-redirector.jetbrains.com/intellij-third-party-dependencies")
    }
}

rootProject.name = "maddi-mod"

// maddi (base), from source: every io.codelaser:maddi-* coordinate below resolves to its project
includeBuild("../maddi")

include("maddi-modification-common")
include("maddi-modification-prepwork")
include("maddi-modification-link")
include("maddi-modification-analyzer")
include("maddi-aapi-parser")
include("maddi-run-analysis")
include("maddi-run-kotlin-analysis")
include("maddi-inspection-kotlin-analysis")
