package io.codelaser.maddi.modification.prepwork.variable;

import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.translate.TranslationMap;
import io.codelaser.maddi.cst.api.variable.Variable;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.stream.Stream;


public interface Link extends Comparable<Link> {
    boolean containsVirtualFields();

    Variable from();

    LinkNature linkNature();

    Variable to();

    /** true when this DIRECT link was produced through a declared-type mediator (a cast, or an
     * instanceof/switch pattern binding). Runtime value flow holds; declared-type coupling does NOT
     * (the mediator re-mediates the type) — declared-type consumers must skip mediated links.
     * Provenance is only guaranteed on directly-produced links; closure/reconstruction products
     * currently drop it (task #39 step 1; threading through reconstruction is the remaining step). */
    default boolean mediated() {
        return false;
    }

    /** true when this link is a product of the link graph's closure (a composition of two or more facts, or the
     * shared-variable reconstruction of one), false when the statement's own evaluation produced it: an assignment,
     * a call's summary applied at the call site, an element or field access. A consumer that follows VALUE flow
     * (the nullability pass) composes direct links itself, per statement; the closure's products say that two
     * variables are related, not where. In-run provenance only: it is not encoded, and a decoded link reads as
     * direct. */
    default boolean derived() {
        return false;
    }

    @NotNull String toString(Set<Variable> modified);

    @Override
    default int compareTo(@NotNull Link o) {
        int c = from().compareTo(o.from());
        if (c != 0) return c;
        int d = to().compareTo(o.to());
        if (d != 0) return d;
        return Long.compare(linkNature().rank(), o.linkNature().rank());
    }

    Link translate(TranslationMap translationMap);

    Link translateFrom(TranslationMap translationMap);

    default Stream<ParameterInfo> parameterStream() {
        return Stream.concat(from().variableStreamDescend(), to().variableStreamDescend())
                .filter(v -> v instanceof ParameterInfo)
                .map(v -> (ParameterInfo) v);
    }
}
