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

package io.codelaser.maddi.run.openjdkmain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code rewire-tests} end to end: RunRewireTests edits a source file on disk, asks the inspector what changed,
 * re-parses with the changed type INVALID and its dependents REWIRE, and restores the file — over and over.
 */
public class TestRewireTests {

    private static final Map<String, String> MAIN = Map.of(
            "p/Base.java", """
                    package p;
                    public class Base {
                        public String name() { return "base"; }
                    }
                    """,
            "p/Mid.java", """
                    package p;
                    public class Mid {
                        private final Base base = new Base();
                        public String name() { return base.name(); }
                    }
                    """,
            "p/Top.java", """
                    package p;
                    public class Top {
                        private final Mid mid = new Mid();
                        public String describe() { return mid.name(); }
                    }
                    """);

    // a second source set, which depends on the first: a change in main must have these REWIRED, not re-scanned
    private static final Map<String, String> TEST = Map.of(
            "q/UsesTop.java", """
                    package q;
                    import p.Top;
                    public class UsesTop {
                        public String go() { return new Top().describe(); }
                    }
                    """);

    @Test
    public void rewireTests(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path test = tmp.resolve("test");
        for (Map.Entry<String, String> e : MAIN.entrySet()) {
            Path p = src.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
        for (Map.Entry<String, String> e : TEST.entrySet()) {
            Path p = test.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }

        int exit = Main.execute(new String[]{
                "--" + Main.SOURCE, src.toString(),
                "--" + Main.TEST_SOURCE, test.toString(),
                "--" + Main.JMOD, "java.base",
                "--" + Main.ANALYSIS_STEPS, Main.AS_REWIRE_TESTS});

        assertEquals(Main.EXIT_OK, exit, "the rewire test loop must complete cleanly");

        // every file must be back exactly as it was: the loop edits and restores
        for (Map.Entry<String, String> e : MAIN.entrySet()) {
            assertEquals(e.getValue(), Files.readString(src.resolve(e.getKey())), e.getKey() + " must be restored");
        }
        for (Map.Entry<String, String> e : TEST.entrySet()) {
            assertEquals(e.getValue(), Files.readString(test.resolve(e.getKey())), e.getKey() + " must be restored");
        }
    }
}
