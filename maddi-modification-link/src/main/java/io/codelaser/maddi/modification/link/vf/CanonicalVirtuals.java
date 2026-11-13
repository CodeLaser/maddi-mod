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

package io.codelaser.maddi.modification.link.vf;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;

/**
 * One virtual field per owner, name and type, and one container type per definition. The virtual field computer is
 * made per method and made both afresh on every call: on fernflower, 367k virtual {@link FieldInfo}s stood for a few
 * thousand definitions (2026-11-13), and every link end naming one was a distinct object, so that the link-end
 * interner, which shares an end only with an end of the same field object, could not share them.
 * <p>
 * Keys hold their parts by identity (an owner or container type is one object per definition, as made here, and two
 * containers of one name are different definitions); values are weak, so an entry lives as long as some link or
 * variable still uses its field or container, and no older generation of the model stays reachable through this cache.
 * <p>
 * Gate {@code NOCANONICALVF} (presence): every call makes a new object, the behaviour before 2026-11-13.
 */
final class CanonicalVirtuals {
    private static final boolean OFF = System.getenv("NOCANONICALVF") != null;

    private record Id(Object o) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Id(Object p) && p == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(o);
        }
    }

    // a type, the object it names compared by identity as well: equal types of two containers of one name are not one
    private record TypeKey(ParameterizedType type, Id typeInfo) {
        static TypeKey of(ParameterizedType type) {
            return new TypeKey(type, new Id(type.typeInfo()));
        }
    }

    private record FieldKey(Id owner, String name, TypeKey type) {
    }

    private record ContainerKey(Id enclosing, String name, List<FieldKey> components) {
    }

    private static final class Ref<T> extends WeakReference<T> {
        final Object key;

        Ref(T referent, Object key, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.key = key;
        }
    }

    private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
    private static final Map<Object, Ref<?>> CACHE = new ConcurrentHashMap<>();

    private CanonicalVirtuals() {
    }

    static FieldInfo field(TypeInfo owner, String name, ParameterizedType type, Supplier<FieldInfo> make) {
        if (OFF) return make.get();
        return get(new FieldKey(new Id(owner), name, TypeKey.of(type)), make);
    }

    static TypeInfo container(TypeInfo enclosing, String name, List<FieldInfo> components, Supplier<TypeInfo> make) {
        if (OFF) return make.get();
        List<FieldKey> parts = components.stream()
                .map(fi -> new FieldKey(new Id(fi.owner()), fi.name(), TypeKey.of(fi.type())))
                .toList();
        return get(new ContainerKey(new Id(enclosing), name, parts), make);
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object key, Supplier<T> make) {
        expunge();
        Ref<?> ref = CACHE.get(key);
        T known = ref == null ? null : (T) ref.get();
        if (known != null) return known;
        // made outside the map's lock: making may make other virtual fields and containers (re-entrant)
        T made = make.get();
        Ref<?> raced = CACHE.compute(key, (_, current) -> {
            Object value = current == null ? null : current.get();
            return value != null ? current : new Ref<>(made, key, QUEUE);
        });
        T winner = (T) raced.get();
        return winner != null ? winner : made;
    }

    private static void expunge() {
        java.lang.ref.Reference<?> r;
        while ((r = QUEUE.poll()) != null) {
            if (r instanceof Ref<?> ref) CACHE.remove(ref.key, ref);
        }
    }
}
