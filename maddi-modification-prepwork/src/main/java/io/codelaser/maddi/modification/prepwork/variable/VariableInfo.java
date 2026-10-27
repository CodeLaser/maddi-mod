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

import io.codelaser.maddi.modification.prepwork.variable.impl.Assignments;
import io.codelaser.maddi.modification.prepwork.variable.impl.Reads;
import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.variable.Variable;

import java.util.Set;

public interface VariableInfo {

    // FIRST PASS DATA, assigned in the prep-work stage during construction

    // for later
    //Set<Integer> readAtStatementTimes();

    /*
    Information about assignments to this variable.
     */
    Assignments assignments();

    boolean isVariableInClosure();

    Links linkedVariablesOrEmpty();

    Reads reads();

    /*
    is there a definite value for this variable at the end of this statement?

    when this is the return variable:
    return true when the method's flow cannot go beyond index.
    e.g. true when at index, there is a return or throws, or an if in which both blocks exit.
     */
    boolean hasBeenDefined(String index);

    Variable variable();

    // SECOND PASS DATA, assigned in the modification stage; eventually immutable

    Links linkedVariables();

    PropertyValueMap analysis();

    // for later
    //int modificationTime();

    boolean isUnmodified();

    // for testing only
    default boolean isModified() {
        return !isUnmodified();
    }

    /**
     * The structural twin of {@link #isUnmodified()} (CodeLaser/maddi-mod#25): the object the variable holds is not
     * modified itself -- no mutator is called on it, it is not handed to a structurally modified parameter, and no
     * variable holding the same object is -- though the objects in its hidden content (a collection's elements) may
     * be. Implied by unmodified, which is the fallback when the structural verdict is absent.
     */
    boolean isStructurallyUnmodified();

    Set<TypeInfo> downcast();
}
