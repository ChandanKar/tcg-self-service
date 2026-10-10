package com.tcgdigital.vmcontrol.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Plain HTML string builders for notification emails — no templating engine, matching this
 * codebase's existing style of building bell-notification messages via string concatenation.
 */
final class EmailTemplates {

    private EmailTemplates() {}

    /**
     * Wraps a short plain-text body for a single-event email (access granted, lock broken, etc).
     * The body is escaped and its newlines become line breaks (E11-T09, M13): a lock reason or a
     * name can never inject markup or a link. URLs are not auto-linked.
     */
    static String eventEmail(String title, String bodyText) {
        return "<html><body style=\"font-family:Arial,sans-serif;font-size:14px;color:#222;\">"
                + "<h2 style=\"margin:0 0 12px;\">" + escape(title) + "</h2>"
                + "<p>" + escape(bodyText).replace("\r\n", "\n").replace("\n", "<br>") + "</p>"
                + "</body></html>";
    }

    /**
     * Short HTML summary for a weekly digest email — the actual data goes in the Excel
     * attachment; this is just a "here's what changed" preview.
     */
    static String digestSummary(String title, String introText, List<String> headers, List<Object[]> previewRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style=\"font-family:Arial,sans-serif;font-size:14px;color:#222;\">");
        sb.append("<h2 style=\"margin:0 0 12px;\">").append(escape(title)).append("</h2>");
        sb.append("<p>").append(escape(introText)).append("</p>");
        if (!previewRows.isEmpty()) {
            sb.append("<table style=\"border-collapse:collapse;margin-top:12px;\">");
            sb.append("<tr>");
            for (String header : headers) {
                sb.append("<th style=\"border:1px solid #ccc;padding:6px 10px;background:#f2f2f2;text-align:left;\">")
                        .append(escape(header)).append("</th>");
            }
            sb.append("</tr>");
            for (Object[] row : previewRows) {
                sb.append("<tr>");
                for (Object cell : row) {
                    sb.append("<td style=\"border:1px solid #ccc;padding:6px 10px;\">")
                            .append(escape(formatCell(cell))).append("</td>");
                }
                sb.append("</tr>");
            }
            sb.append("</table>");
        }
        sb.append("<p style=\"margin-top:16px;color:#555;\">Full details are in the attached spreadsheet.</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String formatCell(Object cell) {
        if (cell instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        return String.valueOf(cell);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
