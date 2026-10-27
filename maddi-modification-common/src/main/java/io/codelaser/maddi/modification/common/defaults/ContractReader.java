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

package io.codelaser.maddi.modification.common.defaults;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.runtime.Runtime;

import java.util.Map;

/**
 * Reads the contracts a user wrote in source code: the annotations on an element, translated to property
 * values. Contracts are re-derived on demand from the CST rather than persisted, so the guard can always
 * distinguish "what the user promised" (this reader) from "what the analyzer computed" ({@code analysis()}).
 */
public class ContractReader extends AnnotationToProperty {

    public ContractReader(Runtime runtime) {
        super(runtime, Element::annotations);
    }

    /** The user-written contracts on this element, as property values; empty when not annotated. */
    public Map<Property, Value> contracts(Info info) {
        return annotationsToMap(info, annotationProvider.annotations(info));
    }
}
