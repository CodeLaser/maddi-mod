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
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.junit.jupiter.api.Test;

import javax.swing.*;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.COMMUTABLE_METHODS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestJavaxSwing extends CommonTest {

    @Test
    public void testDefaultComboBoxRemoveAllElements() {
        TypeInfo typeInfo = compiledTypesManager().type(DefaultComboBoxModel.class, mainSources());
        MethodInfo methodInfo = typeInfo.findUniqueMethod("removeAllElements", 0);
        assertTrue(methodInfo.isModifying());
    }

    @Test
    public void testDefaultComboBoxAddAll() {
        TypeInfo typeInfo = compiledTypesManager().type(DefaultComboBoxModel.class, mainSources());
        MethodInfo methodInfo = typeInfo.findUniqueMethod("addAll", 2);
        assertTrue(methodInfo.isModifying());
    }
    @Test
    public void testJLabelSetText() {
        TypeInfo typeInfo = compiledTypesManager().type(JLabel.class, mainSources());
        MethodInfo methodInfo = typeInfo.findUniqueMethod("setText", 1);
        assertTrue(methodInfo.isModifying());
    }

    @Test
    public void testJComboBoxAddActionListener() {
        TypeInfo typeInfo = compiledTypesManager().type(JComboBox.class, mainSources());
        MethodInfo methodInfo = typeInfo.findUniqueMethod("addActionListener", 1);
        assertTrue(methodInfo.isModifying());
    }

    @Test
    public void testAbstractButtonAddActionListener() {
        TypeInfo typeInfo = compiledTypesManager().type(AbstractButton.class, mainSources());
        MethodInfo methodInfo = typeInfo.findUniqueMethod("addActionListener", 1);
        assertTrue(methodInfo.isModifying());
    }

    @Test
    public void testJTableSetDefaultRenderer() {
        TypeInfo typeInfo = compiledTypesManager().type(JTable.class, mainSources());
        MethodInfo methodInfo = typeInfo.findUniqueMethod("setDefaultRenderer", 2);
        assertTrue(methodInfo.isModifying());
        Value.CommutableData cd = methodInfo.analysis().getOrNull(COMMUTABLE_METHODS, ValueImpl.CommutableData.class);
        assertEquals("", cd.par());
        assertEquals("class,0", cd.seq());
    }

    @Test
    public void testJTableSetFillsViewportHeight() {
        TypeInfo typeInfo = compiledTypesManager().type(JTable.class, mainSources());
        MethodInfo methodInfo = typeInfo.findUniqueMethod("setFillsViewportHeight", 1);
        assertTrue(methodInfo.isModifying());
        testCommutable(methodInfo);
    }

    @Test
    public void setJTableRowSelectionAllowed() {
        TypeInfo typeInfo = compiledTypesManager().type(JTable.class, mainSources());
        MethodInfo methodInfo = typeInfo.findUniqueMethod("setRowSelectionAllowed", 1);
        assertTrue(methodInfo.isModifying());
        testCommutable(methodInfo);
    }

    private void testCommutable(MethodInfo methodInfo) {
        Value.CommutableData cd = methodInfo.analysis().getOrNull(COMMUTABLE_METHODS, ValueImpl.CommutableData.class);
        assertTrue(cd.isBlankMultiParSeq());
    }

}
