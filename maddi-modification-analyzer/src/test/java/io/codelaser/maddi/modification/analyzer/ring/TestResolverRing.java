package io.codelaser.maddi.modification.analyzer.ring;

import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.graph.G;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/*
 CodeLaser/maddi-mod#158, the shape of Guava's TypeResolver: resolveType dispatches to resolveParameterizedType and
 resolveGenericArrayType, which call resolveType again on the parts and build a new object from the results. The
 parts (ownerType, rawType) are types again, so the access paths go round a cycle of fields; the created wildcard
 of subtypeOf travels along it. With MONOTABLE=sim, Guava alternates between two summaries of these methods.

 NOT A REPRODUCTION YET (2026-11-26): on its own this settles in two passes, with and without MONOTABLE=sim. Its
 summaries hold none of the 'ownerType'/'rawType' paths that Guava's hold, so the ring needs more of Guava than
 these methods.
 */
@EnabledIfEnvironmentVariable(named = "LINKCHURN", matches = ".+", disabledReason = "CodeLaser/maddi-mod#158: a reproduction that prints; run with LATESTWINSCOUNTS=1 LINKCHURN=1")
public class TestResolverRing extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.lang.reflect.GenericArrayType;
            import java.lang.reflect.ParameterizedType;
            import java.lang.reflect.Type;
            import java.lang.reflect.TypeVariable;
            import java.lang.reflect.WildcardType;
            import java.util.HashMap;
            import java.util.Map;
            public class R {
                static final class ParameterizedTypeImpl implements ParameterizedType {
                    private final Type ownerType;
                    private final Type[] argumentsList;
                    private final Class<?> rawType;
                    ParameterizedTypeImpl(Type ownerType, Class<?> rawType, Type[] typeArguments) {
                        this.ownerType = ownerType;
                        this.rawType = rawType;
                        this.argumentsList = typeArguments;
                    }
                    @Override public Type[] getActualTypeArguments() { return argumentsList.clone(); }
                    @Override public Type getRawType() { return rawType; }
                    @Override public Type getOwnerType() { return ownerType; }
                }
                static final class GenericArrayTypeImpl implements GenericArrayType {
                    private final Type componentType;
                    GenericArrayTypeImpl(Type componentType) { this.componentType = componentType; }
                    @Override public Type getGenericComponentType() { return componentType; }
                }
                static final class WildcardTypeImpl implements WildcardType {
                    private final Type[] lowerBounds;
                    private final Type[] upperBounds;
                    WildcardTypeImpl(Type[] lowerBounds, Type[] upperBounds) {
                        this.lowerBounds = lowerBounds;
                        this.upperBounds = upperBounds;
                    }
                    @Override public Type[] getLowerBounds() { return lowerBounds.clone(); }
                    @Override public Type[] getUpperBounds() { return upperBounds.clone(); }
                }
                static WildcardType subtypeOf(Type upperBound) {
                    return new WildcardTypeImpl(new Type[0], new Type[] {upperBound});
                }
                static WildcardType supertypeOf(Type lowerBound) {
                    return new WildcardTypeImpl(new Type[] {lowerBound}, new Type[] {Object.class});
                }
                static Type newArrayType(Type componentType) {
                    if (componentType instanceof WildcardType) {
                        WildcardType wildcard = (WildcardType) componentType;
                        Type[] lowerBounds = wildcard.getLowerBounds();
                        if (lowerBounds.length == 1) {
                            return supertypeOf(newArrayType(lowerBounds[0]));
                        } else {
                            Type[] upperBounds = wildcard.getUpperBounds();
                            return subtypeOf(newArrayType(upperBounds[0]));
                        }
                    }
                    return new GenericArrayTypeImpl(componentType);
                }
                static ParameterizedType newParameterizedTypeWithOwner(Type ownerType, Class<?> rawType, Type... arguments) {
                    if (ownerType == null) {
                        return newParameterizedType(rawType, arguments);
                    }
                    return new ParameterizedTypeImpl(ownerType, rawType, arguments);
                }
                static ParameterizedType newParameterizedType(Class<?> rawType, Type... arguments) {
                    return new ParameterizedTypeImpl(rawType.getEnclosingClass(), rawType, arguments);
                }

                static class TypeTable {
                    private final Map<TypeVariable<?>, Type> map = new HashMap<>();
                    final Type resolve(TypeVariable<?> var) {
                        TypeTable unguarded = this;
                        TypeTable guarded = new TypeTable() {
                            @Override
                            Type resolveInternal(TypeVariable<?> intermediateVar, TypeTable forDependent) {
                                if (intermediateVar.getGenericDeclaration().equals(var.getGenericDeclaration())) {
                                    return intermediateVar;
                                }
                                return unguarded.resolveInternal(intermediateVar, forDependent);
                            }
                        };
                        return resolveInternal(var, guarded);
                    }
                    Type resolveInternal(TypeVariable<?> var, TypeTable forDependants) {
                        Type type = map.get(var);
                        if (type == null) {
                            Type[] bounds = var.getBounds();
                            if (bounds.length == 0) {
                                return var;
                            }
                            Type[] resolvedBounds = new Resolver(forDependants).resolveTypes(bounds);
                            return resolvedBounds.length == 1 ? resolvedBounds[0] : var;
                        }
                        return new Resolver(forDependants).resolveType(type);
                    }
                }

                static final class Resolver {
                    private final TypeTable typeTable;
                    Resolver(TypeTable typeTable) { this.typeTable = typeTable; }

                    public Type resolveType(Type type) {
                        if (type instanceof TypeVariable) {
                            return typeTable.resolve((TypeVariable<?>) type);
                        } else if (type instanceof ParameterizedType) {
                            return resolveParameterizedType((ParameterizedType) type);
                        } else if (type instanceof GenericArrayType) {
                            return resolveGenericArrayType((GenericArrayType) type);
                        } else if (type instanceof WildcardType) {
                            return resolveWildcardType((WildcardType) type);
                        } else {
                            return type;
                        }
                    }
                    private Type[] resolveTypes(Type[] types) {
                        Type[] result = new Type[types.length];
                        for (int i = 0; i < types.length; i++) {
                            result[i] = resolveType(types[i]);
                        }
                        return result;
                    }
                    private WildcardType resolveWildcardType(WildcardType type) {
                        Type[] lowerBounds = type.getLowerBounds();
                        Type[] upperBounds = type.getUpperBounds();
                        return new WildcardTypeImpl(resolveTypes(lowerBounds), resolveTypes(upperBounds));
                    }
                    private Type resolveGenericArrayType(GenericArrayType type) {
                        Type componentType = type.getGenericComponentType();
                        Type resolvedComponentType = resolveType(componentType);
                        return newArrayType(resolvedComponentType);
                    }
                    private ParameterizedType resolveParameterizedType(ParameterizedType type) {
                        Type owner = type.getOwnerType();
                        Type resolvedOwner = (owner == null) ? null : resolveType(owner);
                        Type resolvedRawType = resolveType(type.getRawType());
                        Type[] args = type.getActualTypeArguments();
                        Type[] resolvedArgs = resolveTypes(args);
                        return newParameterizedTypeWithOwner(resolvedOwner, (Class<?>) resolvedRawType, resolvedArgs);
                    }
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.R", INPUT);
        G<Info> graph = prepAnalyzer.doPrimaryTypesReturnGraph(Set.of(x));
        List<Info> ao = new ComputeAnalysisOrder().go(graph);
        IteratingAnalyzerImpl a = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(60).build());
        a.analyze(ao, graph);
        System.out.println("RING certified=" + a.certifiedWithoutFrozenValues());
    }
}
