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

package io.codelaser.maddi.modification.common;

import io.codelaser.maddi.util.corpus.Corpora;

import java.nio.file.Path;

/**
 * Where a clone-bench project's sources are inside the {@code testarchive} corpus.
 *
 * <p>⚠ THIS RESOLVES NOTHING. Finding the corpus is {@link Corpora}'s job, and this class exists only
 * to hold one fact about that corpus's shape: it is a single repository containing many small
 * projects, each with its sources at {@code <project>/src/main/java}. That is knowledge about
 * testarchive, not about how to locate a corpus, and it is used by three test classes in two modules
 * that are not downstream of each other ({@code maddi-modification-prepwork} is UPSTREAM of
 * {@code maddi-modification-analyzer}, so it cannot borrow the analyzer's test classes).
 *
 * <p>It used to resolve the root itself, through {@code -Dtestarchive.root} / {@code TESTARCHIVE_ROOT}
 * and a fallback of {@code ../../testarchive}. That fallback only holds when the checkout sits directly
 * under a workspace that also contains the corpus, which is why maddi's clone-bench tests were
 * silently skipping: the corpus is at {@code ~/git/testarchive} while {@code ../../testarchive} from a
 * maddi module points inside the workspace. {@link Corpora} walks up the directory hierarchy instead,
 * so these tests now find it. The two old overrides are still honoured, by {@code Corpora}.
 */
public class CloneBenchCorpus {

    // The corpus name and its two older overrides live HERE, with the code that knows this corpus --
    // not in Corpora, which is in the public part of maddi and needs no list of corpus names.
    private static final Corpora.Corpus TESTARCHIVE =
            Corpora.codeLaser("testarchive", "testarchive.root", "TESTARCHIVE_ROOT");

    /** The corpus checkout. May not exist; {@link #assumeAvailable()} is the check. */
    public static final Path ROOT = TESTARCHIVE.dir();

    /** The source directory of one clone-bench project, e.g. {@code <root>/switch_pure_compiles/src/main/java}. */
    public static Path sourceDirectory(String project) {
        return ROOT.resolve(project).resolve("src/main/java");
    }

    /**
     * Skip the calling test when the corpus is absent — or fail, under
     * {@code -D}{@value io.codelaser.maddi.util.corpus.Corpora#REQUIRED_PROPERTY}.
     *
     * <p>Use this rather than an assertion. An absent corpus must be indistinguishable from a
     * deliberate skip, never from a regression -- and, just as importantly, never from a pass:
     * TestCloneBench used to iterate an empty directory list and report success having analyzed 0
     * types, which is the one outcome that makes a proving ground actively misleading.
     */
    public static void assumeAvailable() {
        TESTARCHIVE.assumeAvailable();
    }
}
