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

import io.codelaser.maddi.modification.prepwork.callgraph.ComputePartOfConstructionFinalField;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;
import io.codelaser.maddi.modification.prepwork.variable.impl.ReturnVariableImpl;
import io.codelaser.maddi.cst.api.analysis.Codec;
import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyProviderImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.cst.io.CodecImpl;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class PrepWorkCodec {

    private final Codec.TypeProvider typeProvider;
    private final Codec.DecoderProvider decoderProvider;
    private final Codec.PropertyProvider propertyProvider;
    private final Runtime runtime;
    private final SourceSet sourceSetOfRequest;

    public PrepWorkCodec(Runtime runtime, SourceSet sourceSetOfRequest) {
        // complain=false: a type whose module is not on the classpath resolves to null (getOrLoad returns null
        // without loading). LoadAnalysisResults uses that null to SKIP the analysis hints for that type, so hints
        // are loaded only for the modules actually present on the classpath (e.g. no java.desktop -> no swing hints).
        this.typeProvider = fqn -> runtime.getFullyQualified(fqn, false, sourceSetOfRequest);
        decoderProvider = new D();
        this.propertyProvider = new P();
        this.runtime = runtime;
        this.sourceSetOfRequest = sourceSetOfRequest;
    }

    public Codec codec() {
        return new C(runtime);
    }

    class C extends CodecImpl {
        public C(Runtime runtime) {
            super(runtime, propertyProvider, decoderProvider, typeProvider, sourceSetOfRequest);
        }

        // the prep-work introduces the ReturnVariable, which the base codec (CST-only) does not know; encode it
        // as ["R", <methodInfo>] and decode symmetrically, so linked-variable results round-trip
        @Override
        public EncodedValue encodeVariable(Context context, Variable variable) {
            if (variable instanceof ReturnVariable rv) {
                return encodeList(context, List.of(encodeString(context, "R"),
                        encodeInfoOutOfContext(context, rv.methodInfo())));
            }
            return super.encodeVariable(context, variable);
        }

        @Override
        protected Variable decodeVariable(Context context, String s, List<EncodedValue> list) {
            if ("R".equals(s)) {
                return new ReturnVariableImpl((MethodInfo) decodeInfoOutOfContext(context, list.get(1)));
            }
            return super.decodeVariable(context, s, list);
        }
    }

    private static final Map<String, Property> PROPERTY_MAP = Map.of(
            ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION.key(), ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION);

    static class P implements Codec.PropertyProvider {
        @Override
        public Property get(String propertyName) {
            Property inMap = PROPERTY_MAP.get(propertyName);
            if (inMap != null) return inMap;
            return PropertyProviderImpl.get(propertyName);
        }
    }

    static class D implements Codec.DecoderProvider {

        @Override
        public BiFunction<Codec.DI, Codec.EncodedValue, Value> decoder(Class<? extends Value> clazz) {
            // part of construction uses "set of info", which is in ValueImpl.
            return ValueImpl.decoder(clazz);
        }
    }

}

