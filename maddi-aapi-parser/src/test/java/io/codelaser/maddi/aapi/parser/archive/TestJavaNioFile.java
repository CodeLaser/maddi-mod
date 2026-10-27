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
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/*
RULES FOR THE FILES OBJECT

Almost all methods are @AllowsInterrupt.
We treat the file system as the instance, and ignore the 'static' on the methods.
Files that do not change the file system are marked @NotModified.
Files that change the file system are marked @Modified.

Path is immutable.
 */
public class TestJavaNioFile extends CommonTest {

    @Test
    public void testFilesWalkFileTree() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Files.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("walkFileTree", 4);
        assertTrue(methodInfo.allowsInterrupts());
        assertFalse(methodInfo.isModifying());
        ParameterInfo p3 = methodInfo.parameters().get(3);
        assertEquals("Type java.nio.file.FileVisitor<? super java.nio.file.Path>",
                p3.parameterizedType().toString());
        assertFalse(p3.isIgnoreModifications());
    }

    @Test
    public void testFilesCreateFile() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Files.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("createFile", 2);
        assertTrue(methodInfo.allowsInterrupts());
        assertTrue(methodInfo.isModifying());
    }

    @Test
    public void testFilesExists() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Files.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("exists", 2);
        assertTrue(methodInfo.allowsInterrupts());
        assertFalse(methodInfo.isModifying());
    }

}
