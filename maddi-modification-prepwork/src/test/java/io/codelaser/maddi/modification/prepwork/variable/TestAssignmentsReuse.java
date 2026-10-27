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

package io.codelaser.maddi.modification.prepwork.variable;

import io.codelaser.maddi.modification.prepwork.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class TestAssignmentsReuse extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.io.*;
            import java.net.HttpURLConnection;
            import java.net.URL;
            class X {
                public static String connect(String url_str, String oauth_header, String data) throws IOException {
                    URL url = new URL(url_str);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setAllowUserInteraction(true);
                    if (oauth_header != null || data != null) {
                        if (data != null) {
                            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
                            out.writeBytes(data);
                            out.flush();
                            out.close();
                        }
                    }
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String out = "";
                    String temp;
                    while ((temp = reader.readLine()) != null) {
                        out += temp;
                    }
                    return out;
                }
            }
            """;

    @DisplayName("variable is used 2x, with different types")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(ABX, INPUT1);
        MethodInfo method = X.findUniqueMethod("connect", 3);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doMethod(method);
        LocalVariableCreation reader = (LocalVariableCreation) method.methodBody().statements().get(4);
        Statement if300 = method.methodBody().statements().get(3).block().statements().getFirst();
        VariableData vd300 = VariableDataImpl.of(if300);
        assertFalse(vd300.isKnown("out"));
        Statement mc30003 = if300.block().statements().get(3);
        VariableData vd30003 = VariableDataImpl.of(mc30003);
        VariableInfo vi1 = vd30003.variableInfo("out");
        assertEquals("Type java.io.DataOutputStream", vi1.variable().parameterizedType().toString());

        VariableData vd4 = VariableDataImpl.of(reader);
        assertFalse(vd4.isKnown("out"));
        VariableData vdMethod = VariableDataImpl.of(method);
        VariableInfo vi = vdMethod.variableInfo("out");
        assertEquals("Type String", vi.variable().parameterizedType().toString());
        // in order of appearance:
        assertEquals("""
                [url, a.b.X.connect(String,String,String):0:url_str, conn, \
                a.b.X.connect(String,String,String):1:oauth_header, a.b.X.connect(String,String,String):2:data, \
                reader, out, temp, a.b.X.connect(String,String,String)]\
                """, vdMethod.knownVariableNames().toString());
    }

}
