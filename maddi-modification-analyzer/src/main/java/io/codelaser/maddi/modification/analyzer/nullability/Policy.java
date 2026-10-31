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

package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.type.NullableState;

/**
 * @param unreached the verdict for a declaration no null reaches: NONNULL is the {@code @NullMarked} reading
 *                  (annotate only {@code @Nullable}), UNSPECIFIED claims nothing it cannot see
 * @param nullTests a parameter or field compared with {@code null} ({@code p == null}, {@code f != null}) is
 *                  taken as nullable: the author expects null there, even when no null is seen arriving (a
 *                  public parameter no analysed caller passes null to; a lazily initialized field)
 * @param contracts the null annotations on source declarations are contracts (M2): a nullable one is a seed, a
 *                  non-null one stops null. Off to measure the inference against those annotations (the oracle).
 * @param world     who else calls the analysed code: see {@link World}
 * @param assertContentWrites the Kotlin translation's choice: a value that is nullable only through indirect
 *                  evidence (a library contract such as {@code Map.get}, a field's default value, a comparison
 *                  with null) does not make an array's elements or a type argument nullable where it is written
 *                  into them; the slot stays non-null and the printer asserts at the write
 *                  ({@code list.add(map.get(k)!!)}), instead of a {@code !!} at every read of a
 *                  {@code List<X?>}. A null literal, a null argument or initializer, and a source annotation,
 *                  still make the slot nullable. Off for Java annotations, which say what can happen.
 * @param callResults a local, field or return assigned directly the result of an analysed method (not one typed
 *                  by a type variable) receives that method's nullability. Off for guava's annotations, where it
 *                  carried the flow-insensitivity of 'v = get(k); if (v == null) ...' into the callers (+947 noise,
 *                  2026-10-06); on for Kotlin, which does not compile a non-null variable holding a nullable
 *                  result.
 * @param assertAtDeclaration the Kotlin translation's choice for a local that null reaches, but that its own
 *                  block dereferences unconditionally further on (before any jump out, never reassigned):
 *                  the printer asserts at the declaration ({@code val block = blocks.getWithKey(j)!!}), so
 *                  the local is non-null and no null flows on from it (fernflower's
 *                  {@code protectedRange.add(block); block.addSuccessorException(handle)}). It moves the NPE
 *                  forward, from the dereference to the declaration. Off for Java annotations.
 * @param preconditionsNonNull a parameter that is a precondition ({@link Report#preconditions}: its method, and
 *                  in a closed world every method of its override family, dereferences it or throws on null on
 *                  every normal exit) is declared non-null, so no null flows on from it into the method's slots;
 *                  a nullable argument is asserted at the call ({@link Report#nullArguments}). Not where a call
 *                  passes the literal null, nor for a method a method reference or a JUnit null source hands to
 *                  outside code. Off in every preset (CodeLaser/maddi-mod#41): for Java annotations and C# it
 *                  changes nothing at run time; for Kotlin it moves the NPE from inside the method to the call.
 */
public record Policy(NullableState unreached, boolean nullTests, boolean contracts, World world,
                     boolean assertContentWrites, boolean callResults, boolean assertAtDeclaration,
                     boolean libraryCallbacks, boolean testNulls, boolean preconditionsNonNull,
                     boolean rejectionsNullable) {
    public Policy(NullableState unreached, boolean nullTests, boolean contracts, World world,
                  boolean assertContentWrites, boolean callResults, boolean assertAtDeclaration,
                  boolean libraryCallbacks, boolean testNulls, boolean preconditionsNonNull) {
        this(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                libraryCallbacks, testNulls, preconditionsNonNull, false);
    }

    public static final Policy NULL_MARKED = new Policy(NullableState.NONNULL, true, true, World.CLOSED, false,
            false, false, false, true, false);
    public static final Policy NULL_MARKED_FLOW_ONLY = new Policy(NullableState.NONNULL, false, true,
            World.CLOSED, false, false, false, false, true, false);
    public static final Policy CAUTIOUS = new Policy(NullableState.UNSPECIFIED, true, true, World.CLOSED, false,
            false, false, false, true, false);
    /**
     * {@link #NULL_MARKED} for the Java→Kotlin translation: content writes are asserted, and a variable assigned an
     * analysed method's nullable result is nullable (Kotlin will not compile it otherwise), and a parameter of an
     * override of a library method is nullable unless the library declares it non-null (the library calls it back
     * with what it likes; Kotlin checks a non-null parameter on entry).
     */
    public static final Policy KOTLIN = new Policy(NullableState.NONNULL, true, true, World.CLOSED, true, true,
            true, true, true, false);

    public Policy withoutContracts() {
        return new Policy(unreached, nullTests, false, world, assertContentWrites, callResults, assertAtDeclaration,
                libraryCallbacks, testNulls, preconditionsNonNull, rejectionsNullable);
    }

    public Policy withWorld(World world) {
        return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                libraryCallbacks, testNulls, preconditionsNonNull, rejectionsNullable);
    }

    /**
     * Nulls that only test code writes do not count for production code (CodeLaser/maddi-mod#30 3, the
     * diagnostics): a null a test passes to a production method or constructor, or stores into a production
     * object, reaches nothing outside the test sources. Nulls inside the tests still count there.
     */
    public Policy withoutTestNulls() {
        return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                libraryCallbacks, false, preconditionsNonNull, rejectionsNullable);
    }

    /** The precondition parameters are declared non-null ({@link #preconditionsNonNull}). */
    public Policy withPreconditionsNonNull() {
        return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                libraryCallbacks, testNulls, true, rejectionsNullable);
    }

    /**
     * A parameter whose method answers null with its own exception ({@link Report#rejectsNull}) is nullable: a Java
     * caller's null reaches that check instead of Kotlin's entry check (CodeLaser/maddi-mod#28). Opt-in, for a
     * translation that must keep its Java callers' exceptions; every defensively checked parameter becomes '?'.
     */
    public Policy withRejectionsNullable() {
        return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                libraryCallbacks, testNulls, preconditionsNonNull, true);
    }

    public Policy withAssertContentWrites(boolean assertContentWrites) {
        return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                libraryCallbacks, testNulls, preconditionsNonNull, rejectionsNullable);
    }
}
