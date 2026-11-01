package com.kasa.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.kasa.common.BusinessCalendar;
import com.kasa.payment.PaymentViews.PaymentView;

/** Writes a report that opens correctly in Excel under a Turkish locale: a BOM, semicolons and decimal commas. */
final class PaymentCsv {

    private static final String HEADER = "Ödeme kimliği;Durum;Tutar;Para birimi;Tahsil edilen;İade edilen;Komisyon;Açıklama;Hata kodu;Oluşturulma";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PaymentCsv() {
    }

    static byte[] write(List<PaymentView> payments) {
        StringBuilder csv = new StringBuilder("﻿").append(HEADER).append("\r\n");
        for (PaymentView payment : payments) {
            csv.append(String.join(";",
                    payment.id().toString(),
                    payment.status().name(),
                    money(payment.amount()),
                    payment.currency(),
                    money(payment.capturedAmount()),
                    money(payment.refundedAmount()),
                    money(payment.feeAmount()),
                    text(payment.description()),
                    text(payment.failureCode()),
                    payment.createdAt().atZoneSameInstant(BusinessCalendar.ZONE).format(DATE_TIME)));
            csv.append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String money(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, 2).toPlainString().replace('.', ',');
    }

    /**
     * Quotes free text. A leading apostrophe stops a spreadsheet from running a value such as =HYPERLINK(...)
     * as a formula, since descriptions come from outside and the report is opened by someone else.
     */
    private static String text(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.matches("(?s)^[\\s]*[=+\\-@].*") ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
