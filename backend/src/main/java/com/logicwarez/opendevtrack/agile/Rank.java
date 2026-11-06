/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import java.math.BigInteger;

/**
 * Keys of the one order of a project (ADR-15): fractional indexes in base 62 ({@code 0-9A-Za-z}, in the order of their
 * bytes), at least {@value #HEAD} digits, never ending with a 0 beyond them. Read as the digits of a fraction, the
 * order of the keys is the order of the text, so a key between two others is their mean, one digit longer when they are
 * next to each other. The bottom of the order grows by one on the first {@value #HEAD} digits, which keeps the keys of
 * new issues short.
 */
final class Rank {

    static final int HEAD = 6;
    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(DIGITS.length());

    /** The key of the first issue of a project. */
    static final String FIRST = "U00000";

    private Rank() {
    }

    /** A key after {@code last} (the bottom of the order); {@link #FIRST} when there is none. */
    static String after(String last) {
        if (last == null) {
            return FIRST;
        }
        BigInteger head = value(last.substring(0, HEAD));
        BigInteger top = BASE.pow(HEAD).subtract(BigInteger.ONE);
        return head.compareTo(top) < 0 ? text(head.add(BigInteger.ONE), HEAD) : between(last, null);
    }

    /** A key before {@code first} (the top of the order). */
    static String before(String first) {
        if (first == null) {
            return FIRST;
        }
        BigInteger head = value(first.substring(0, HEAD));
        // Never the key 000000: nothing would fit before it.
        return head.compareTo(BigInteger.ONE) > 0 ? text(head.subtract(BigInteger.ONE), HEAD) : between(null, first);
    }

    /** A key strictly between {@code low} and {@code high}; null stands for the start or the end of the order. */
    static String between(String low, String high) {
        if (low != null && high != null && low.compareTo(high) >= 0 || high != null && value(high).signum() == 0) {
            throw new IllegalArgumentException("No key between " + low + " and " + high);
        }
        int digits = Math.max(HEAD, Math.max(low == null ? 0 : low.length(), high == null ? 0 : high.length()));
        while (true) {
            BigInteger a = low == null ? BigInteger.ZERO : value(pad(low, digits));
            BigInteger b = high == null ? BASE.pow(digits) : value(pad(high, digits));
            BigInteger mean = a.add(b).shiftRight(1);
            if (mean.compareTo(a) > 0) {
                return trim(text(mean, digits));
            }
            digits++;
        }
    }

    static boolean valid(String key) {
        return key != null && key.length() >= HEAD && key.chars().allMatch(c -> DIGITS.indexOf(c) >= 0);
    }

    private static BigInteger value(String key) {
        BigInteger v = BigInteger.ZERO;
        for (int i = 0; i < key.length(); i++) {
            v = v.multiply(BASE).add(BigInteger.valueOf(DIGITS.indexOf(key.charAt(i))));
        }
        return v;
    }

    private static String text(BigInteger value, int digits) {
        char[] out = new char[digits];
        BigInteger v = value;
        for (int i = digits - 1; i >= 0; i--) {
            BigInteger[] qr = v.divideAndRemainder(BASE);
            out[i] = DIGITS.charAt(qr[1].intValue());
            v = qr[0];
        }
        return new String(out);
    }

    private static String pad(String key, int digits) {
        return key + "0".repeat(digits - key.length());
    }

    private static String trim(String key) {
        int end = key.length();
        while (end > HEAD && key.charAt(end - 1) == '0') {
            end--;
        }
        return key.substring(0, end);
    }
}
