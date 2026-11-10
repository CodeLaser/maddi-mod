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

package io.codelaser.maddi.modification.analyzer.link;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.RepeatedTest;

import io.codelaser.maddi.callgraph.ComputeCallGraph;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.graph.G;

/**
 * What an analysis leaves must be what a recomputation gives: once it has ended, computing a method's links again
 * on the state it left reproduces the summary and the links at the method's last statement. On fernflower
 * (2026-11-10) six methods did not, all in recursive groups, and the result differed from run to run. This is the
 * shape of one group, {@code GenericType.parse(String, int)} and {@code parseArgumentsList}, which call each other,
 * with {@code parse} also calling itself, and the callers in {@code GenericMain}.
 */
public class TestRecursiveGroupReproduces extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            import java.util.ArrayList;
            import java.util.Collections;
            import java.util.List;

            public class A {
              static class VarType {
                private final int type;
                private final int arrayDim;
                private final String value;
                VarType(int type, int arrayDim, String value) {
                  this.type = type;
                  this.arrayDim = arrayDim;
                  this.value = value;
                }
                int getType() { return type; }
                String getValue() { return value; }
              }

              static class GenericType extends VarType {
                public static final int WILDCARD_EXTENDS = 1;
                public static final int WILDCARD_SUPER = 2;
                public static final int WILDCARD_UNBOUND = 3;
                public static final int WILDCARD_NO = 4;
                static final int TYPE_OBJECT = 8;
                static final int TYPE_GENVAR = 18;

                private final VarType parent;
                private final List<VarType> arguments;
                private final int wildcard;

                GenericType(int type, int arrayDim, String value, VarType parent, List<VarType> arguments, int wildcard) {
                  super(type, arrayDim, value);
                  this.parent = parent;
                  this.arguments = arguments == null ? Collections.emptyList() : arguments;
                  this.wildcard = wildcard;
                }

                public static VarType parse(String signature) {
                  return parse(signature, WILDCARD_NO);
                }

                public static VarType parse(String signature, int wildcard) {
                  int type = 0;
                  int arrayDim = 0;
                  String value = null;
                  List<VarType> params = null;
                  VarType parent = null;

                  int index = 0;
                  loop:
                  while (index < signature.length()) {
                    switch (signature.charAt(index)) {
                      case '[':
                        arrayDim++;
                        break;

                      case 'T':
                        type = TYPE_GENVAR;
                        value = signature.substring(index + 1, signature.length() - 1);
                        break loop;

                      case 'L':
                        type = TYPE_OBJECT;
                        signature = signature.substring(index + 1, signature.length() - 1);
                        String cl = getNextClassSignature(signature);

                        if (cl.length() == signature.length()) {
                          int argStart = cl.indexOf('<');
                          if (argStart >= 0) {
                            value = cl.substring(0, argStart);
                            params = parseArgumentsList(cl.substring(argStart + 1, cl.length() - 1));
                          }
                          else {
                            value = cl;
                          }
                        }
                        else {
                          StringBuilder name_buff = new StringBuilder();
                          while (!signature.isEmpty()) {
                            String name = cl;
                            String args = null;

                            int argStart = cl.indexOf('<');
                            if (argStart >= 0) {
                              name = cl.substring(0, argStart);
                              args = cl.substring(argStart + 1, cl.length() - 1);
                            }

                            if (!name_buff.isEmpty()) {
                              name_buff.append('$');
                            }
                            name_buff.append(name);

                            value = name_buff.toString();
                            params = args == null ? null : parseArgumentsList(args);

                            if (cl.length() == signature.length()) {
                              break;
                            }
                            else {
                              if (parent == null && params == null) {
                                parent = parse("L" + value + ";");
                              }
                              else {
                                parent = new GenericType(TYPE_OBJECT, 0, value, parent, params, wildcard);
                              }

                              signature = signature.substring(cl.length() + 1);
                            }
                            cl = getNextClassSignature(signature);
                          }
                        }
                        break loop;

                      default:
                        value = signature.substring(index, index + 1);
                        type = value.charAt(0);
                    }

                    index++;
                  }

                  if (type == TYPE_GENVAR) {
                    return new GenericType(type, arrayDim, value, null, null, wildcard);
                  }
                  else if (type == TYPE_OBJECT) {
                    if (parent == null && params == null && wildcard == WILDCARD_NO) {
                      return new VarType(type, arrayDim, value);
                    }
                    else {
                      return new GenericType(type, arrayDim, value, parent, params, wildcard);
                    }
                  }
                  else {
                    return new VarType(type, arrayDim, value);
                  }
                }

                private static String getNextClassSignature(String value) {
                  int counter = 0;
                  int index = 0;

                  loop:
                  while (index < value.length()) {
                    switch (value.charAt(index)) {
                      case '<' -> counter++;
                      case '>' -> counter--;
                      case '.' -> {
                        if (counter == 0) {
                          break loop;
                        }
                      }
                    }

                    index++;
                  }

                  return value.substring(0, index);
                }

                private static List<VarType> parseArgumentsList(String value) {
                  if (value == null) {
                    return null;
                  }

                  List<VarType> args = new ArrayList<>();

                  while (!value.isEmpty()) {
                    String typeStr = getNextType(value);
                    int len = typeStr.length();
                    int wildcard = switch (typeStr.charAt(0)) {
                      case '*' -> WILDCARD_UNBOUND;
                      case '+' -> WILDCARD_EXTENDS;
                      case '-' -> WILDCARD_SUPER;
                      default -> WILDCARD_NO;
                    };

                    if (wildcard != WILDCARD_NO) {
                      typeStr = typeStr.substring(1);
                    }

                    args.add(typeStr.isEmpty() ? null : parse(typeStr, wildcard));

                    value = value.substring(len);
                  }

                  return args;
                }

                public static String getNextType(String value) {
                  int counter = 0;
                  int index = 0;
                  while (index < value.length()) {
                    char c = value.charAt(index);
                    if (c == '<') counter++;
                    else if (c == '>') counter--;
                    else if (c == ';' && counter == 0) { index++; break; }
                    index++;
                  }
                  return value.substring(0, index);
                }

                public VarType getParent() { return parent; }
                public List<VarType> getArguments() { return arguments; }
                public int getWildcard() { return wildcard; }
              }

              static class GenericMain {
                private final List<VarType> superclasses = new ArrayList<>();
                private final List<VarType> parameters = new ArrayList<>();
                private VarType returnType;

                void parseClassSignature(String signature, String qualifiedName) {
                  String superclass = GenericType.getNextType(signature);
                  superclasses.add(GenericType.parse(superclass));
                  signature = signature.substring(superclass.length());
                  while (!signature.isEmpty()) {
                    String superinterface = GenericType.getNextType(signature);
                    superclasses.add(GenericType.parse(superinterface));
                    signature = signature.substring(superinterface.length());
                  }
                }

                void parseMethodSignature(String signature) {
                  int to = signature.indexOf(')');
                  String params = signature.substring(1, to);
                  while (!params.isEmpty()) {
                    String par = GenericType.getNextType(params);
                    parameters.add(GenericType.parse(par));
                    params = params.substring(par.length());
                  }
                  returnType = GenericType.parse(signature.substring(to + 1));
                }

                VarType getReturnType() { return returnType; }
              }
            }
            """;

    @RepeatedTest(10)
    public void test() {
        TypeInfo A = javaInspector.parse("A", INPUT);
        IteratingAnalyzerImpl iterating = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder()
                        .setMaxIterations(30)
                        .setModificationViaReachability(true)
                        .build());
        List<Info> order = prepWork(A);
        G<Info> graph = new ComputeCallGraph(runtime, A).go().graph();
        iterating.analyze(order, graph);
        assertEquals(0, iterating.linkRecheck(order), "see the LINKRECHECK lines of the log");
    }
}
