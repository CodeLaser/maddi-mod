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

package io.codelaser.maddi.aapi.parser.archive;

import io.codelaser.maddi.aapi.parser.CommonTest;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class TestJavaUtilLogging extends CommonTest {

    // jdk/JavaUtilLogging: logging reads its arguments; it modifies the logger, but carries @IgnoreModifications
    // (2026-10-22, was @NotModified); configuring the logger is a modification that counts
    @Test
    public void testLogger() {
        TypeInfo logger = compiledTypesManager().typeIfLoaded(Logger.class);
        MethodInfo log = method(logger, "java.util.logging.Logger.log(java.util.logging.Level,String,Throwable)");
        assertTrue(log.isModifying());
        assertTrue(log.isIgnoreModification());
        assertFalse(log.parameters().get(2).isModified());
        MethodInfo logArray = method(logger, "java.util.logging.Logger.log(java.util.logging.Level,String,Object[])");
        assertFalse(logArray.parameters().get(2).isModified());

        MethodInfo setLevel = method(logger, "java.util.logging.Logger.setLevel(java.util.logging.Level)");
        assertTrue(setLevel.isModifying());
        assertFalse(setLevel.isIgnoreModification());
        assertTrue(method(logger, "java.util.logging.Logger.log(java.util.logging.LogRecord)").parameters().getFirst().isModified());
    }

    private static MethodInfo method(TypeInfo typeInfo, String fqn) {
        return typeInfo.methodStream().filter(m -> fqn.equals(m.fullyQualifiedName())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + fqn));
    }
}
