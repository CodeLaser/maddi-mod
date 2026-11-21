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

package io.codelaser.maddi.modification.analyzer.impl;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.modification.common.util.TolerantWrite;
import io.codelaser.maddi.modification.link.impl.Gate;

/**
 * The eventual family's writes. Through {@link TolerantWrite}, so that the written element is a changed target and
 * the worklist wakes its dependents; a plain {@code set()} only counted the change, and the propagation then waited
 * for the next full verification pass, one hop per pass. Gate {@code NOEVENTUALWAKE} restores the plain write (the
 * A/B baseline).
 */
final class EventualWrite {
    private static final boolean NO_WAKE = Gate.isSet("NOEVENTUALWAKE");

    private EventualWrite() {
    }

    static <V extends Value> void setOnce(PropertyValueMap analysis, Property property, V value, Object context) {
        if (NO_WAKE) analysis.set(property, value);
        else TolerantWrite.setOnce(analysis, property, value, context);
    }

    static <V extends Value> void setAllowControlledOverwrite(PropertyValueMap analysis, Property property, V value,
                                                              Object context) {
        if (NO_WAKE) analysis.setAllowControlledOverwrite(property, value);
        else TolerantWrite.setAllowControlledOverwrite(analysis, property, value, context);
    }
}
