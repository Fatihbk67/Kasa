package com.kasa.payment;

import java.util.List;
import java.util.Map;

/**
 * Paying in instalments, as Turkish cards allow. The customer pays the same total; the card bank pays the business
 * at once and collects from the customer month by month, and charges for that wait. So the business's commission
 * grows with the number of instalments.
 */
public final class Installments {

    public static final List<Integer> ALLOWED = List.of(1, 2, 3, 6, 9, 12);

    /** On top of the single payment commission, in basis points. */
    private static final Map<Integer, Integer> SURCHARGE_BASIS_POINTS = Map.of(1, 0, 2, 120, 3, 200, 6, 450, 9, 700, 12, 950);

    /** Below this a bank offers no instalments, since each one would be a few lira. */
    public static final long MINIMUM_AMOUNT = 20_000;

    private Installments() {
    }

    public static boolean isAllowed(int count, long amount) {
        return count == 1 || (ALLOWED.contains(count) && amount >= MINIMUM_AMOUNT);
    }

    public static int surchargeBasisPoints(int count) {
        Integer surcharge = SURCHARGE_BASIS_POINTS.get(count);
        if (surcharge == null) {
            throw new IllegalArgumentException("unsupported instalment count " + count);
        }
        return surcharge;
    }
}
