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

package io.codelaser.maddi.modification.analyzer;

import io.codelaser.maddi.annotation.Modified;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;

import java.util.Set;

/*
Level 2:

is a field @Modified?

- we need to know the @Modified value for the field in each method of the primary type

Is a field @Independent?

- we need to know if the field links to any of the parameters of each method in the primary type.
- so we compute this linking first, then write the @Independent property if all data is present

Independence and modification of parameters is directly influenced by the values computed here,
but the actual computation for parameters is done in the next phase.

This analyzer does not concern itself with solving internal cycles.
It writes out results, if any, in the field's analysis() object.
 */
public interface FieldAnalyzer {

    void go(@Modified FieldInfo fieldInfo, boolean cycleBreakingActive);
}
