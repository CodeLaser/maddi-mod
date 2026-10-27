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

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class TestHCTNotYetLoaded extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import java.math.BigInteger;
            
            public class Function17122361_file1119884 {
              private static long vecVecMultModM(long[] v, long[] w, BigInteger modul) {
                BigInteger result = BigInteger.ZERO;
                for (int i = 0; i < v.length; i++) {
                  result = multModM(v[i], w[i], modul).add(result).mod(modul);
                }
                return result.longValue();
              }

              private static BigInteger multModM(long r, long s, BigInteger modul) {
                return BigInteger.valueOf(r).multiply(BigInteger.valueOf(s)).mod(modul);
              }
            }
            """;

    @DisplayName("test HCT not yet loaded")
    @org.junit.jupiter.api.Test
    public void test1() {
        TypeInfo B = javaInspector.parse("Function17122361_file1119884", INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.go(ao);
    }
}
