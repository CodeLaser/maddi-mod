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

package io.codelaser.maddi.modification.prepwork.variable.impl;

import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfoContainer;
import io.codelaser.maddi.cst.api.analysis.Codec;
import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.InfoMap;
import io.codelaser.maddi.cst.api.info.InfoMapView;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.Stream;

public class VariableDataImpl implements VariableData {
    // INTRINSIC: prepwork rebuilds the per-variable/per-statement data from the method body on every run.
    public static final PropertyImpl VARIABLE_DATA = new PropertyImpl("variableData",
            new VariableDataImpl(new LinkedHashMap<>()), Property.AnalysisTier.INTRINSIC);

    public static class Builder implements VariableData {
        // we employ a linkedHashMap to keep the order of creation, with this first, then fields, then parameters,
        // followed by local variables
        private final LinkedHashMap<String, VariableInfoContainer> vicByFqn = new LinkedHashMap<>();

        @Override
        public Set<String> knownVariableNames() {
            // snapshot in CREATION order, not Set.copyOf: java.util immutable collections iterate in a
            // per-JVM SALTED order (ImmutableCollections.SALT), and every consumer walking the variables
            // would inherit that run-to-run nondeterminism — the LinkedHashMap exists precisely to give
            // this set a stable order (see the field comment above)
            return Collections.unmodifiableSet(new LinkedHashSet<>(vicByFqn.keySet()));
        }

        public boolean isKnown(String fqn) {
            return vicByFqn.containsKey(fqn);
        }

        @Override
        public boolean isDefault() {
            return vicByFqn.isEmpty();
        }

        public void put(Variable v, VariableInfoContainer vic) {
            vicByFqn.put(v.fullyQualifiedName(), vic);
        }

        public VariableData build() {
            return new VariableDataImpl(vicByFqn);
        }

        @Override
        public VariableInfoContainer variableInfoContainerOrNull(String fullyQualifiedName) {
            return vicByFqn.get(fullyQualifiedName);
        }

        @Override
        public Codec.EncodedValue encode(Codec codec, Codec.Context context) {
            throw new UnsupportedOperationException("Builder cannot be encoded, must be built first");
        }

        @Override
        public Value rewire(InfoMapView infoMap) {
            throw new UnsupportedOperationException("Builder cannot be rewired, must be built first");
        }

        @Override
        public Stream<VariableInfoContainer> variableInfoContainerStream() {
            return vicByFqn.values().stream();
        }
    }

    private final SequencedMap<String, VariableInfoContainer> vicByFqn;

    private VariableDataImpl(LinkedHashMap<String, VariableInfoContainer> map) {
        this.vicByFqn = Collections.unmodifiableSequencedMap(map);
    }

    @Override
    public boolean isDefault() {
        return vicByFqn.isEmpty();
    }

    @Override
    public Codec.EncodedValue encode(Codec codec, Codec.Context context) {
        return null;// not yet streamed
    }

    /*
    The map keys are fully qualified names and survive, but every VariableInfoContainer underneath holds Variable
    objects (and its own analysis), so a rewire has to descend into all of them. This is the one value worth
    carrying across a rewire -- it is computed from the type's own body, which a REWIRE type by definition did not
    change -- so this is the place to start when that is taken on. See docs/design/rewiring.md.
     */
    @Override
    public Value rewire(InfoMapView infoMap) {
        throw new UnsupportedOperationException("NYI");
    }

    @Override
    public boolean isKnown(String fullyQualifiedName) {
        return vicByFqn.containsKey(fullyQualifiedName);
    }

    @Override
    public VariableInfoContainer variableInfoContainerOrNull(String fullyQualifiedName) {
        return vicByFqn.get(fullyQualifiedName);
    }

    @Override
    public Stream<VariableInfoContainer> variableInfoContainerStream() {
        return vicByFqn.values().stream();
    }

    @Override
    public Set<String> knownVariableNames() {
        return vicByFqn.keySet();
    }

    public static VariableData of(Element element) {
        return element.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
    }

    /**
     * A back-reference-free copy: every container is {@link VariableInfoContainer#flattened()}, so this
     * VariableData no longer holds the chain of earlier statements' containers. Applied to a method's
     * consumed (last-statement) VD, this is what lets the intermediate statements' VARIABLE_DATA be
     * dropped and their container chains collected — see
     * {@code maddi-modification-analyzer/DESIGN-vardata-flatten.md}.
     */
    public VariableDataImpl flattened() {
        LinkedHashMap<String, VariableInfoContainer> flat = new LinkedHashMap<>();
        vicByFqn.forEach((fqn, vic) -> flat.put(fqn, vic.flattened()));
        return new VariableDataImpl(flat);
    }
}
