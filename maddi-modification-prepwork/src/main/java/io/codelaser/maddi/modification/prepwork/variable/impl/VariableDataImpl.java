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
    // carried on a rewire: see rewire() below
    public static final PropertyImpl VARIABLE_DATA = new PropertyImpl("variableData",
            new VariableDataImpl(new LinkedHashMap<>()), Property.AnalysisTier.INTRINSIC, true);

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

        /*
         A builder is kept as a value in one place: VARIABLES_OF_ENCLOSING_METHOD, the enclosing method's variables
         as an anonymous type saw them. Rewired, it becomes a built copy, its containers the same rewired objects the
         statements' VariableData hold (InfoMapView#rewired).
         */
        @Override
        public Value rewire(InfoMapView infoMap) {
            return infoMap.rewired(this, () -> build().rewire(infoMap));
        }

        @Override
        public String toString() {
            return build().toString();
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
    The intrinsic carry (docs/design/rewiring.md): computed from the type's own body, which a REWIRE type by
    definition did not change, so the rewired type keeps it and prep skips the type. The keys are fully qualified
    names and survive; every container underneath is rewired once per rewire (InfoMapView#rewired), which is what
    the containers' identity assertions and their links to a previous statement's container need: a method's
    VariableData is its last statement's, and every statement's container chain points back through the earlier ones.
     */
    @Override
    public Value rewire(InfoMapView infoMap) {
        return infoMap.rewired(this, () -> {
            LinkedHashMap<String, VariableInfoContainer> map = new LinkedHashMap<>();
            vicByFqn.forEach((fqn, vic) -> map.put(fqn, ((VariableInfoContainerImpl) vic).rewire(infoMap)));
            return new VariableDataImpl(map);
        });
    }

    /**
     * A canonical description: per variable, in order, its nature and the assignments and reads of its best
     * information, with no identities. What the rewire driver's oracle compares between a warm and a cold prep.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("VD{");
        vicByFqn.forEach((fqn, vic) -> sb.append(fqn).append('=').append(((VariableInfoContainerImpl) vic).describe())
                .append("; "));
        return sb.append('}').toString();
    }

    @Override
    public boolean isKnown(String fullyQualifiedName) {
        return vicByFqn.containsKey(fullyQualifiedName);
    }

    @Override
    public VariableInfoContainer variableInfoContainerOrNull(String fullyQualifiedName) {
        return vicByFqn.get(fullyQualifiedName);
    }

    /** {@link VariableInfoContainerImpl#clearDerived} on every container. */
    public void clearDerived(java.util.function.Predicate<io.codelaser.maddi.cst.api.analysis.Property> derived) {
        vicByFqn.values().forEach(vic -> {
            if (vic instanceof VariableInfoContainerImpl impl) impl.clearDerived(derived);
        });
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
