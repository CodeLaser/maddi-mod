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
import io.codelaser.maddi.inspection.integration.ToolChain;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class TestRunAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestRunAnalyzer.class);

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.codelaser.maddi.shallow")).setLevel(Level.DEBUG);
    }

    @Disabled
    @Test
    public void test() throws IOException {
        File sourceDir = new File("../internal-util/src/main/java");
        assertTrue(sourceDir.isDirectory(), "Absolute = " + sourceDir.getAbsolutePath());

        Main.main(new String[]{
                "--debug=classpath",
                "--classpath=" + String.join(":", InputConfigurationImpl.DEFAULT_MODULES),
                "--classpath=" + String.join(":", ToolChain.CLASSPATH_JUNIT),
                "--classpath=" + String.join(":", ToolChain.CLASSPATH_SLF4J_LOGBACK),
                "--source=" + sourceDir.getPath(),
                "--analysis-results-dir=build/maddi",
                "--preload-analysis-results-dirs=../../maddi/maddi-aapi-archive/src/main/resources/json",
        });

        File output = new File("build/maddi/OrgE2ImmuUtilInternalUtil.json");
        String content = Files.readString(output.toPath());
        String expected = """
                [{"fqn": "Tio.codelaser.maddi.util.GetSetHelper", "data":{"hct":{"E":true}}},
                {"fqn": "Tio.codelaser.maddi.util.ThrowingBiConsumer", "data":{"hct":{"E":true,"M":2,0:"S",1:"T"}}},
                {"fqn": "Tio.codelaser.maddi.util.StringUtil", "data":{"hct":{"E":true}}},
                {"fqn": "Tio.codelaser.maddi.util.Trie", "data":{"hct":{"E":true,"M":1,0:"T"}}},
                {"fqn": "Mio.codelaser.maddi.util.Trie.recursivelyVisit(8)", "data":{"hct":{1:"T"}}},
                {"fqn": "Mio.codelaser.maddi.util.MapUtil.compareMaps(0)", "data":{"hct":{0:"T",1:"D"}}},
                {"fqn": "Mio.codelaser.maddi.util.MapUtil.compareKeys(1)", "data":{"hct":{0:"T"}}}]\
                """;
        assertEquals(expected, content);
    }
}
