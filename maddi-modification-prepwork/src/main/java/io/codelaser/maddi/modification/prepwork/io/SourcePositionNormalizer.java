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

package io.codelaser.maddi.modification.prepwork.io;

import java.util.regex.Pattern;

/**
 * Erases {@code compact2} source coordinates from the dump. {@code ExpressionCodec.encodeExpression} embeds every
 * expression's source as element [1] of its encoding, a quoted {@code "beginLine-beginPos:endLine-endPos"} string
 * (e.g. {@code "4-23:4-26"}, see {@code Source.compact2()}). Those coordinates leak into link summaries, so a purely
 * cosmetic, line-shifting edit (a comment added above, a reformat) changes them and would flip the fingerprint of an
 * otherwise-unchanged type. No analysis reads a callee's source coordinates, so blanking them is sound and only
 * widens the invariance.
 * <p>
 * String-level and anchored on the distinctive four-number, quote-delimited shape, which does not collide with
 * statement indices ({@code "1.0.0"}), fqns, or link markers. Blanks to {@code ""} (a stable placeholder) rather
 * than deleting, so the surrounding list structure is untouched.
 */
public class SourcePositionNormalizer implements FingerprintNormalizer {

    // "beginLine-beginPos:endLine-endPos", quote-delimited; e.g. "4-23:4-26". noSource() yields "0-0:0-0".
    private static final Pattern COMPACT2 = Pattern.compile("\"\\d+-\\d+:\\d+-\\d+\"");

    @Override
    public String name() {
        return "source-positions";
    }

    @Override
    public String normalize(String encoded) {
        return COMPACT2.matcher(encoded).replaceAll("\"\"");
    }
}
