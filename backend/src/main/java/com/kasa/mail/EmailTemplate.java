package com.kasa.mail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the plain text every email is written in into a simple branded HTML version. Mail clients strip styles
 * from the head, so everything is inline, and the plain text travels alongside for clients that show only text.
 */
public final class EmailTemplate {

    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"]+");

    private EmailTemplate() {
    }

    public static String html(String subject, String text) {
        StringBuilder body = new StringBuilder();
        for (String paragraph : text.split("\\n")) {
            if (paragraph.isBlank()) {
                continue;
            }
            body.append("<p style=\"margin:0 0 14px;line-height:1.55\">").append(linked(escape(paragraph))).append("</p>");
        }
        return """
                <!doctype html>
                <html lang="tr"><body style="margin:0;padding:24px;background:#f6f7f6;font-family:Arial,Helvetica,sans-serif;color:#17201d">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0"><tr><td align="center">
                <table role="presentation" width="560" cellspacing="0" cellpadding="0" style="max-width:560px;background:#ffffff;border:1px solid #dde2df;border-radius:8px">
                <tr><td style="padding:20px 24px;border-bottom:1px solid #dde2df;font-size:20px;font-weight:bold;color:#0f6b57">Kasa</td></tr>
                <tr><td style="padding:24px"><h1 style="margin:0 0 16px;font-size:18px">%s</h1>%s</td></tr>
                <tr><td style="padding:16px 24px;border-top:1px solid #dde2df;font-size:12px;color:#5b6863">Bu e-posta Kasa test ortamından otomatik gönderildi.</td></tr>
                </table></td></tr></table></body></html>
                """.formatted(escape(subject), body);
    }

    /** Links become buttons only after escaping, so text from a user can never inject its own markup. */
    private static String linked(String escaped) {
        Matcher matcher = LINK.matcher(escaped);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String url = matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    "<br><a href=\"" + url + "\" style=\"display:inline-block;margin-top:8px;padding:10px 16px;background:#0f6b57;"
                            + "color:#ffffff;text-decoration:none;border-radius:6px;font-weight:bold\">Bağlantıyı aç</a>"
                            + "<br><span style=\"font-size:12px;color:#5b6863;word-break:break-all\">" + url + "</span>"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
