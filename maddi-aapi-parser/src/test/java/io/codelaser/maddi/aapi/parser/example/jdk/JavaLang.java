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

package io.codelaser.maddi.aapi.parser.example.jdk;

import io.codelaser.maddi.annotation.*;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;

public class JavaLang {

    final static String PACKAGE_NAME = "java.lang";

    @Container
    interface Iterable$<T> {
        /*
         Because action is of functional interface type in java.util.function, it is @IgnoreModifications by default.
         The forEach method communicates hidden content to the outside world.
         Finally, we do not allow action to be null, but obviously action's single abstract method (accept()) is allowed
         to return null values.
         */
        @NotModified
        void forEach(@NotNull @Independent(hc = true) Consumer<? super T> action);

        // implicitly @Dependent, has `remove()`
        @NotNull
        Iterator<T> iterator();

        /*
        The spliterator cannot change the iterable, but it does communicate hidden content.
         */
        @NotNull
        @Independent(hc = true)
        Spliterator<T> spliterator();
    }

    /*
    The archetype for a non-abstract type with hidden content.
     */
    @ImmutableContainer(hc = true)
    @Independent
    interface Object$ {
        @NotNull
        Object clone();

        default boolean equals$Value(Object object, boolean retVal) {
            return object != null && (this == object || retVal);
        }

        default boolean equals$Invariant(Object object) {
            return (this.equals(object)) == object.equals(this);
        }

        // @NotModified implicit on method and parameter
        boolean equals(Object object);

        // final, cannot override to add annotations, so added a $ as a general convention that you can drop that at the end??
        @NotNull
        Class<?> getClass$();

        @NotNull
        String toString();

        // does not exist, causes warning
        class SubInObject {

        }
    }

    // does not exist, causes warning
    interface Strings$ {

    }

    class String$ {

        @Independent
        String$(char[] chars) {
        }
    }
}
