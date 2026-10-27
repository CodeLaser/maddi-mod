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
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;

import javax.swing.text.JTextComponent;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestJavaxSwingText extends CommonTest {

    @Test
    public void testJTextComponentSetText() {
        TypeInfo typeInfo = compiledTypesManager().type(JTextComponent.class, mainSources());
        SourceSet set = typeInfo.compilationUnit().sourceSet();
        assertTrue(set.partOfJdk());
        assertTrue(set.externalLibrary());
        MethodInfo methodInfo = typeInfo.findUniqueMethod("setText", 1);
        assertTrue(methodInfo.isModifying());
    }

}
