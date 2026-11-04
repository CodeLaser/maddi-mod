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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;

public abstract class CommonAnalyzerImpl {
    public static final Logger DECIDE = LoggerFactory.getLogger("maddi.modanalyzer.decide");
    public static final Logger UNDECIDED = LoggerFactory.getLogger("maddi.modanalyzer.delay");

    protected final IteratingAnalyzer.Configuration configuration;
    protected final AtomicInteger propertyChanges;
    // shared across all analyzers of one iteration, same injection pattern as propertyChanges; synchronized list
    protected final List<Message> analyzerMessages;

    protected CommonAnalyzerImpl(IteratingAnalyzer.Configuration configuration, AtomicInteger propertyChanges,
                                 List<Message> analyzerMessages) {
        this.configuration = configuration;
        this.propertyChanges = propertyChanges;
        this.analyzerMessages = analyzerMessages;
    }

    protected static String highlight(String content) {
        return "\033[31;1;4m" + content + "\033[0m";
    }
    protected static final String CYCLE_BREAKING = highlight("cycle breaking");
}
