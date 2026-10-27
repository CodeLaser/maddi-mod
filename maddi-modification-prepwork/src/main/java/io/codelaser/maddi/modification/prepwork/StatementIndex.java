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

package io.codelaser.maddi.modification.prepwork;

import java.util.regex.Pattern;

public class StatementIndex {

    // pre-digits+dot: +,-.
    // post-digits: :;<=>?@ and at the end |~

    public static final char PLUS = '+';
    public static final char DASH = '-';
    public static final char DOT = '.';
    public static final char COLON = ':';
    public static final char SEMICOLON = ';';
    public static final char EQUALS = '=';
    public static final char END = '~';

    public static final String INIT = PLUS + "I"; // name of the stage +I
    public static final String EVAL_INIT = PLUS + "E"; // initializer in for/forEach +E
    public static final String EVAL = DASH + "E";  // normal expression; condition/iterable for loops -E
    public static final String EVAL_UPDATE = COLON + "E"; // still part of the loop, but after the statements :E
    public static final String EVAL_AFTER_UPDATE = SEMICOLON + "E"; // repeat of condition in for
    public static final String MERGE = EQUALS + "M"; // at the end =M

    public static final Pattern STAGE_PATTERN = Pattern.compile("(.*)(\\+I|[+-=]E|=M)");

    private StatementIndex() {
    }


    public static final String DOT_ZERO = DOT + "0";
    public static final String BEFORE_METHOD = "" + DASH;
    public static final String ENCLOSING_METHOD = "" + PLUS;
    public static final String END_OF_METHOD = "" + END;


    public static boolean seenBy(String s, String index) {
        int dot = s.lastIndexOf(StatementIndex.DOT);
        if (dot < 0) {
            return s.compareTo(index) < 0;
        }
        String sDot = s.substring(0, dot);
        return index.startsWith(sDot) && s.compareTo(index) < 0;
    }
}
