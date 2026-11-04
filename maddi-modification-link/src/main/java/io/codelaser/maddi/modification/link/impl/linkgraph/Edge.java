package io.codelaser.maddi.modification.link.impl.linkgraph;

import org.jetbrains.annotations.NotNull;

import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.LinkNature;

record Edge(Variable from, LinkNature linkNature, Variable to) {
    @Override
    public @NotNull String toString() {
        return Util.simpleName(from) + " " + linkNature + " " + Util.simpleName(to);
    }
}
