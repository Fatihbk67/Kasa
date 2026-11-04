package com.kasa.common;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/** Amounts written into messages people read, such as notifications, in the way Turkish readers expect. */
public final class MoneyText {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private MoneyText() {
    }

    public static String format(long minorUnits, String currency) {
        NumberFormat format = NumberFormat.getNumberInstance(TURKISH);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        String number = format.format(BigDecimal.valueOf(minorUnits, 2));
        return currency.equals("TRY") ? "₺" + number : number + " " + currency;
    }
}
