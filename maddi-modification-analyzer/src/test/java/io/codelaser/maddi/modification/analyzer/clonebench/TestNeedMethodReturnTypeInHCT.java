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

package io.codelaser.maddi.modification.analyzer.clonebench;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.*;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.IMMUTABLE_HC;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;


public class TestNeedMethodReturnTypeInHCT extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import java.io.*;
            import java.util.LinkedList;
            
            public class Function17439301_file1261893 {
              public String[] getMRUFileList() {
                if (size() == 0) {
                  return null;
                }
                String[] ss = new String[size()];
                for (int i = 0; i < size(); i++) {
                  Object o = getFile(i);
                  if (o instanceof File) {
                    ss[i] = ((File) o).getAbsolutePath();
                  } else {
                    ss[i] = o.toString();
                  }
                }
                return ss;
              }
            
              private LinkedList _mruFileList;
            
              /** Gets the size of the MRU file list. */
              public int size() {
                return _mruFileList.size();
              }
            
              /** Returns a particular file name stored in a MRU file list based on an index value. */
              public Object getFile(int index) {
                if (index < size()) {
                  return _mruFileList.get(index);
                }
                return null;
              }
            }
            """;

    @DisplayName("fails when method return type is not in method HCT")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse("Function17439301_file1261893", INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.go(ao);

        MethodInfo getFile = B.findUniqueMethod("getFile", 1);
        Statement last = getFile.methodBody().lastStatement();
        VariableData vd = VariableDataImpl.of(last);
        VariableInfo vi = vd.variableInfo(getFile.fullyQualifiedName());

        assertSame(INDEPENDENT, runtime.objectTypeInfo().analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT));
        assertSame(IMMUTABLE_HC, runtime.objectTypeInfo().analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE));

        // because objects are independent, this should not be "*-2-0:_mruFileList"
        assertEquals("getFile←$_ce2,getFile∈this._mruFileList.§es", vi.linkedVariables().toString());
        assertSame(INDEPENDENT_HC, getFile.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT));
    }
}
