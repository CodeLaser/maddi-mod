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

import io.codelaser.maddi.modification.common.util.TolerantWrite;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.TypeContainerAnalyzer;
import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;

public class TypeContainerAnalyzerImpl extends CommonAnalyzerImpl implements TypeContainerAnalyzer {

    protected TypeContainerAnalyzerImpl(IteratingAnalyzer.Configuration configuration, AtomicInteger propertiesChanged, List<Message> analyzerMessages) {
        super(configuration, propertiesChanged, analyzerMessages);
    }

    @Override
    public void go(TypeInfo typeInfo) {
        Value.Bool container = typeInfo.analysis().getOrDefault(PropertyImpl.CONTAINER_TYPE, FALSE);
        if (container.isTrue()) {
            return; // no point
        }
        boolean isContainer = typeInfo.constructorAndMethodStream()
                .filter(mi -> !mi.access().isPrivate())
                .flatMap(mi -> mi.parameters().stream())
                .allMatch(ParameterInfo::isUnmodified);
        if (TolerantWrite.setAllowControlledOverwrite(typeInfo.analysis(), PropertyImpl.CONTAINER_TYPE,
                ValueImpl.BoolImpl.from(isContainer), typeInfo)) {
            DECIDE.debug("TC: Decide container of type {} = {}", typeInfo, isContainer);
            propertyChanges.incrementAndGet();
        }
    }
}
