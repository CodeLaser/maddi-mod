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

import ch.qos.logback.classic.Level;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRunShallowAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestRunShallowAnalyzer.class);

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.codelaser.maddi.shallow")).setLevel(Level.DEBUG);
    }

    @Disabled
    @Test
    public void test() {
        File aapiSources = new File("../../maddi/maddi-aapi-archive/src/main/java");
        assertTrue(aapiSources.isDirectory(), "Absolute = " + aapiSources.getAbsolutePath());

        File file = new File("build/test-shallow-analyzer");
        if (file.isDirectory()) {
            //noinspection ALL
            Arrays.stream(file.listFiles()).forEach(File::delete);
        }
        if (file.mkdirs()) {
            LOGGER.info("Created {}", file);
        }
        Main.main(new String[]{
                "--debug=classpath",
                "--classpath=" + String.join(":", InputConfigurationImpl.DEFAULT_MODULES),
       //         "--classpath=" + String.join(":", ToolChain.CLASSPATH_SLF4J_LOGBACK),
                "--source=" + aapiSources.getPath(),
                "--source-packages=io.codelaser.maddi.shallow.aapi.log",
                "--analyzed-annotated-api-dirs=src/test/resources/json",
                "--analyzed-annotated-api-target-dir=" + file.getAbsolutePath()
        });
        assertTrue(file.canRead());

        File orgSlf4j = new File(file, "OrgSlf4j.json");
        assertTrue(orgSlf4j.canRead());
        File chQosLogbackClassic = new File(file, "ChQosLogbackClassic.json");
        assertTrue(chQosLogbackClassic.canRead());

        File javaLang = new File(file, "JavaLang.json");
        assertFalse(javaLang.canRead());
        File javaIo = new File(file, "JavaIo.json");
        assertFalse(javaIo.canRead());
    }
}
