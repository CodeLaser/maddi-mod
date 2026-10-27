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
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;


public class TestWarnForWrongIndependentHC extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import java.nio.ByteBuffer;
            import java.nio.CharBuffer;
            import java.nio.charset.Charset;
            import java.nio.charset.CharsetEncoder;
            import java.nio.charset.StandardCharsets;
            
            public class Function15634639_file755568 {
            
                public static String unicodeEncode(String str) {
                    try {
                        Charset charset = StandardCharsets.ISO_8859_1;
                        CharsetEncoder encoder = charset.newEncoder();
                        ByteBuffer bbuf = encoder.encode(CharBuffer.wrap(str));
                        return new String(bbuf.array());
                    } catch (Exception e) {
                        return ("Encoding problem");
                    }
                }
            }
            """;

    @DisplayName("issue in LinkHelper INDEPENDENT_HC while both target and source are byte[]")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse("Function15634639_file755568", INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.go(ao);
    }
}
