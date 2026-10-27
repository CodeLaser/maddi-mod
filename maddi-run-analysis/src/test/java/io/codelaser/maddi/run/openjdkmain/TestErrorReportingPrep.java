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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Guards the error-reporting fix: a source file that fails to parse must be surfaced (openjdk diagnostics now flow
 * into the {@code Summary}) and the process must exit with the parser error code — <em>not</em> the previous
 * silent exit 0.
 */

/**
 * The test of {@code TestErrorReporting} (maddi-run-openjdk) that needs the prep step to run, moved here in split
 * stage 3; the parse-error exit code is decided before any step and stays tested there.
 */
public class TestErrorReportingPrep {
    /**
     * Prep-phase fault isolation: a failure while analyzing one method must be recorded and skipped, letting the
     * rest of the run complete, instead of an uncaught throwable aborting the process. The source below (a
     * qualified explicit {@code super()} in a class extending another class's inner class) currently trips an
     * assertion in downstream get/set analysis; whatever the outcome, the run must end with a clean exit code and
     * <em>never</em> the internal-exception code that an escaped throwable would produce. This asserts the isolation
     * happens inside {@code PrepAnalyzer} (exit ANALYZER_ERROR, or OK once the get/set bug is fixed upstream), not
     * merely that a run-level backstop caught it (which would be EXIT_INTERNAL_EXCEPTION).
     */
    @Test
    public void prepMethodFailureIsIsolatedNotFatal(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("p"));
        Files.writeString(src.resolve("p/A.java"), """
                package p;
                public class A {
                    public class Inner { }
                    static class Sub extends A.Inner {
                        Sub(A a) {
                            a.super();
                        }
                    }
                }
                """);

        int exit = Main.execute(new String[]{
                "--" + Main.SOURCE, src.toString(),
                "--" + Main.JMOD, "java.base",
                "--" + Main.ANALYSIS_STEPS, Main.AS_PREP});

        assertNotEquals(Main.EXIT_INTERNAL_EXCEPTION, exit,
                "a per-method prep failure must be isolated, not escape as an uncaught throwable");
    }
}
