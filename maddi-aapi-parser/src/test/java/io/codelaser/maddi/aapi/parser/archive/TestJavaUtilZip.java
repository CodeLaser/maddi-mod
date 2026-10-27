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

import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestJavaUtilZip extends CommonTest {

    @Test
    public void testZipOutputStreamClose() {
        TypeInfo typeInfo = compiledTypesManager().type(ZipOutputStream.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("close", 0);

        // must be "false" when the class is not present in the AAPI, and true otherwise
        // if not present, then the shallow analyzer is never run, so we don't even have a value
        assertTrue(methodInfo.isModifying());
    }

    @Test
    public void testZipOutputStreamPutNextEntry() {
        TypeInfo typeInfo = compiledTypesManager().type(ZipOutputStream.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("putNextEntry", 1);
        assertTrue(methodInfo.isModifying());
    }
}
