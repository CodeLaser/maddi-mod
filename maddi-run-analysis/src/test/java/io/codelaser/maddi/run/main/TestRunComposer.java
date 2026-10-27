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

package io.codelaser.maddi.run.main;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRunComposer {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestRunComposer.class);

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.codelaser.maddi.shallow")).setLevel(Level.DEBUG);
    }

    @Disabled
    @Test
    public void test() {
        File file = new File("build/test-aapi-skeleton");
        if (file.delete()) {
            LOGGER.info("Deleting {}", file);
        }
        Main.main(new String[]{
                "--classpath=jmod:java.base",
                "--source=none",
                "--hints-packages=java.util.",
                "--updated-hints-package=io.codelaser.maddi.aapi",
                "--updated-hints-dir=" + file.getAbsolutePath()
        });
        assertTrue(file.canRead());
        File pkg = new File(file, "io/codelaser/maddi/aapi");
        File javaIo = new File(pkg, "JavaIo.java");
        assertFalse(javaIo.canRead());
        File javaUtil = new File(pkg, "JavaUtil.java");
        assertTrue(javaUtil.canRead());
        File javaUtilConcurrent = new File(pkg, "JavaUtilConcurrent.java");
        assertTrue(javaUtilConcurrent.canRead());
    }
}
