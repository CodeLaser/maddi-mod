package io.codelaser.maddi.modification.prepwork.variable;

import java.util.List;
import java.util.Set;

import io.codelaser.maddi.cst.api.info.MethodInfo;

public interface LinkNature extends Comparable<LinkNature> {

    boolean isAssignedFrom();

    boolean isDecoration();

    boolean isIdenticalToOrAssignedFromTo();

    boolean isIdenticalTo();

    boolean multiplySymbols();

    int rank();

    List<LinkNature> redundantFromUp();

    List<LinkNature> redundantToUp();

    List<LinkNature> redundantUp();

    LinkNature reverse();

    boolean valid();

    LinkNature combine(LinkNature other);

    LinkNature best(LinkNature other);

    int score();

    Set<MethodInfo> pass();
}
