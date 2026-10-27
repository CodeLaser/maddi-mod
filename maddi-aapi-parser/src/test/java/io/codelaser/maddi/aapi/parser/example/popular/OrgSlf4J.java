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

package io.codelaser.maddi.aapi.parser.example.popular;

import io.codelaser.maddi.annotation.*;

public class OrgSlf4J {
    public static final String PACKAGE_NAME = "org.slf4j";

    @ImmutableContainer
    interface Logger$ {

        @NotModified
        void info(@NotNull String s, Object... objects);

        @NotModified
        void warn(@NotNull String s, Object... objects);

        @NotModified
        void error(@NotNull String s);

        @NotModified
        void error(@NotNull String s, Object object);

        @NotModified
        void error(@NotNull String s, Object object1, Object object2);

        @NotModified
        void error(@NotNull String s, Object... objects);

        @NotModified
        void debug(@NotNull String s, Object object);

        @NotModified
        void debug(@NotNull String s, Object object1, Object object2);

        @NotModified
        void debug(@NotNull String s, Object... objects);

        @NotModified
        void debug(@NotNull String s);
    }

    @Container
    @Independent
    interface ILoggerFactory$ {
        @NotNull
        @NotModified
        @ImmutableContainer
        org.slf4j.Logger getLogger(String name);
    }

    @Container
    @Independent
    interface LoggerFactory$ {
        @NotNull
        @NotModified
        @ImmutableContainer
        org.slf4j.Logger getLogger(@NotNull Class<?> clazz);

        @NotNull
        @ImmutableContainer
        @NotModified
        org.slf4j.Logger getLogger(@NotNull String string);

        @NotNull
        @NotModified
        org.slf4j.ILoggerFactory getILoggerFactory();
    }

    @Container
    interface Marker$ {

    }
}
