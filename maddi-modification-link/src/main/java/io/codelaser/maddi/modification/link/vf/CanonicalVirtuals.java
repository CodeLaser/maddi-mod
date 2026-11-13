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
import java.util.ArrayList;
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
 * Nothing of a model is reachable from here. A key holds its objects (an owner or container type, a field, a type
 * parameter) through weak references and compares them by identity, a type by its {@code detailedString()} and the
 * identity of what it names; a value is weak too. The first version held its keys strongly: a key's owner type
 * reached, through its analysis, the very fields the entry's weak value referred to, so no entry was ever cleared, and
 * the refactor server kept a dropped analysed model of 8.6 GB alive through this class (2026-11-13). An entry whose
 * key or value is collected is removed at the next use of the cache; until then it holds nothing but strings.
 * <p>
 * Static rather than per model or run: the virtual field computer is made per method, and the translations that
 * re-own fields are static; there is no object of the run they all reach.
 * <p>
 * Gate {@code NOCANONICALVF} (presence): every call makes a new object, the behaviour before 2026-11-13.
 */
public final class CanonicalVirtuals {
    private static final boolean OFF = System.getenv("NOCANONICALVF") != null;

    private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
    private static final Map<Key, Ref> CACHE = new ConcurrentHashMap<>();

    // a weak reference that knows the entry it belongs to, so that its clearing removes that entry
    private static final class Ref extends WeakReference<Object> {
        final Key key;

        Ref(Object referent, Key key) {
            super(referent, QUEUE);
            this.key = key;
        }
    }

    /*
     A key: strings, and objects held weakly and compared by identity. Its hash is computed once, from the strings and
     the identity hashes, so that an entry can be found (and removed) after its objects are gone; two keys are equal
     only while all their objects are alive and the same.
     */
    private static final class Key {
        private final String text;
        private final Ref[] objects;
        private final int hash;

        Key(String text, Object... objects) {
            this.text = text;
            this.objects = new Ref[objects.length];
            int h = text.hashCode();
            for (int i = 0; i < objects.length; i++) {
                this.objects[i] = objects[i] == null ? null : new Ref(objects[i], this);
                h = 31 * h + System.identityHashCode(objects[i]);
            }
            this.hash = h;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key k) || k.hash != hash || !k.text.equals(text)
                || k.objects.length != objects.length) {
                return false;
            }
            for (int i = 0; i < objects.length; i++) {
                Object a = objects[i] == null ? null : objects[i].get();
                Object b = k.objects[i] == null ? null : k.objects[i].get();
                if (a != b || a == null && (objects[i] != null || k.objects[i] != null)) return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private CanonicalVirtuals() {
    }

    // a type: its text, and the type or type parameter it names by identity (two containers of one name are two)
    private static void type(ParameterizedType type, StringBuilder text, List<Object> objects) {
        text.append('|').append(type.detailedString());
        objects.add(type.typeInfo());
        objects.add(type.typeParameter());
    }

    static FieldInfo field(TypeInfo owner, String name, ParameterizedType type, Supplier<FieldInfo> make) {
        return field('C', owner, name, type, make);
    }

    static TypeInfo container(TypeInfo enclosing, String name, List<FieldInfo> components, Supplier<TypeInfo> make) {
        return container('C', enclosing, name, components, make);
    }

    /** a virtual field made by a translation (method call, varargs), one per owner, name and type */
    public static FieldInfo translatedField(TypeInfo owner, String name, ParameterizedType type,
                                            Supplier<FieldInfo> make) {
        return field('T', owner, name, type, make);
    }

    /** a container made by a translation, one per enclosing type, name and fields */
    public static TypeInfo translatedContainer(TypeInfo enclosing, String name, List<FieldInfo> components,
                                               Supplier<TypeInfo> make) {
        return container('T', enclosing, name, components, make);
    }

    /**
     * A field as seen through another owner ({@link FieldInfo#withOwner}), one per field and owner. A translation that
     * changes a field reference's scope re-owns its field to the scope's type, and withOwner makes a new field each time:
     * on fernflower 16M of them, 255k still live at the end of the analysis, each a distinct object under equal link
     * ends (2026-11-13). Every field, not only a virtual one, as every field is re-owned there.
     */
    public static FieldInfo reowned(FieldInfo field, TypeInfo newOwner) {
        if (OFF || field.owner() == newOwner) return field.withOwner(newOwner);
        return get(new Key("R", field, newOwner), () -> field.withOwner(newOwner));
    }

    private static FieldInfo field(char kind, TypeInfo owner, String name, ParameterizedType type,
                                   Supplier<FieldInfo> make) {
        if (OFF) return make.get();
        StringBuilder text = new StringBuilder().append(kind).append('|').append(name);
        List<Object> objects = new ArrayList<>(3);
        objects.add(owner);
        type(type, text, objects);
        return get(new Key(text.toString(), objects.toArray()), make);
    }

    private static TypeInfo container(char kind, TypeInfo enclosing, String name, List<FieldInfo> components,
                                      Supplier<TypeInfo> make) {
        if (OFF) return make.get();
        StringBuilder text = new StringBuilder().append(kind).append("C|").append(name);
        List<Object> objects = new ArrayList<>(1 + 3 * components.size());
        objects.add(enclosing);
        for (FieldInfo component : components) {
            text.append("|f:").append(component.name());
            objects.add(component.owner());
            type(component.type(), text, objects);
        }
        return get(new Key(text.toString(), objects.toArray()), make);
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Key key, Supplier<T> make) {
        expunge();
        Ref ref = CACHE.get(key);
        T known = ref == null ? null : (T) ref.get();
        if (known != null) return known;
        // made outside the map's lock: making may make other virtual fields and containers (re-entrant)
        T made = make.get();
        Ref raced = CACHE.compute(key, (_, current) -> {
            Object value = current == null ? null : current.get();
            return value != null ? current : new Ref(made, key);
        });
        T winner = (T) raced.get();
        return winner != null ? winner : made;
    }

    private static void expunge() {
        java.lang.ref.Reference<?> r;
        while ((r = QUEUE.poll()) != null) {
            if (r instanceof Ref ref) CACHE.remove(ref.key);
        }
    }

    /** for tests: entries left after removing those whose objects are collected */
    public static int size() {
        expunge();
        return CACHE.size();
    }

    /** for tests: whether some entry's key or value holds one of these objects, or a field or type they own */
    public static boolean holdsAny(Object... os) {
        expunge();
        List<Object> list = List.of(os);
        for (Map.Entry<Key, Ref> e : CACHE.entrySet()) {
            if (refersTo(e.getValue().get(), list)) return true;
            for (Ref part : e.getKey().objects) {
                if (part != null && refersTo(part.get(), list)) return true;
            }
        }
        return false;
    }

    /**
     * for tests: the entries {@link #holdsAny} would find, as opaque handles. A handle holds nothing of the model (a key
     * refers to its objects weakly) and keeps finding its entry after they are collected: the key's hash is fixed.
     */
    public static List<Object> entriesHolding(Object... os) {
        expunge();
        List<Object> list = List.of(os);
        List<Object> keys = new ArrayList<>();
        for (Map.Entry<Key, Ref> e : CACHE.entrySet()) {
            boolean holds = refersTo(e.getValue().get(), list);
            for (Ref part : e.getKey().objects) {
                if (holds) break;
                holds = part != null && refersTo(part.get(), list);
            }
            if (holds) keys.add(e.getKey());
        }
        return keys;
    }

    /** for tests: how many of these handles ({@link #entriesHolding}) still have their entry */
    public static int stillHeld(List<Object> handles) {
        expunge();
        int n = 0;
        for (Object h : handles) if (h instanceof Key k && CACHE.containsKey(k)) n++;
        return n;
    }

    private static boolean refersTo(Object o, List<Object> list) {
        if (o == null) return false;
        for (Object x : list) {
            if (o == x || o instanceof FieldInfo fi && fi.owner() == x
                || o instanceof TypeInfo ti && ti.compilationUnitOrEnclosingType().isRight()
                   && ti.compilationUnitOrEnclosingType().getRight() == x) {
                return true;
            }
        }
        return false;
    }
}
