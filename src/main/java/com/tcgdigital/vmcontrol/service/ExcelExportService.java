package com.tcgdigital.vmcontrol.service;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Serializes a header row + data rows into a single-sheet .xlsx workbook. Generic on purpose —
 * every Cost Management export needs identical POI boilerplate (bold frozen header, thin borders
 * on every cell, columns sized to fit their content), so callers just supply headers and
 * pre-formatted row data rather than each reimplementing workbook setup.
 */
@Service
public class ExcelExportService {

    // Caps how wide a single column can grow from one unusually long value — without this, one
    // stray long string would blow out the whole sheet's width instead of just that one cell.
    private static final int MAX_COLUMN_CHARS = 60;

    public byte[] toWorkbook(String sheetName, List<String> headers, List<Object[]> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);

            CellStyle headerStyle = workbook.createCellStyle();
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            headerStyle.setFont(boldFont);
            applyBorder(headerStyle);

            CellStyle dataStyle = workbook.createCellStyle();
            applyBorder(dataStyle);

            // Tracks the longest value seen per column (header included) so every column can be
            // sized to fit its actual content, not just its header.
            int[] maxLength = new int[headers.size()];

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
                maxLength[i] = headers.get(i).length();
            }
            sheet.createFreezePane(0, 1);

            int rowIndex = 1;
            for (Object[] rowData : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int c = 0; c < rowData.length; c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellStyle(dataStyle);
                    Object value = rowData[c];
                    String text = value == null ? "" : value.toString();
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else if (value != null) {
                        cell.setCellValue(text);
                    }
                    if (c < maxLength.length) {
                        maxLength[c] = Math.max(maxLength[c], text.length());
                    }
                }
            }

            // Not sheet.autoSizeColumn() — it measures text via AWT font metrics, which throws
            // ("Fontconfig head is null") on minimal/headless JRE images (e.g. this app's Docker
            // container) that have no font packages installed. Sizing from actual content length
            // gives the same "fits the data" outcome with zero AWT/font dependency.
            for (int i = 0; i < headers.size(); i++) {
                int width = Math.min(Math.max(maxLength[i] + 2, 10), MAX_COLUMN_CHARS);
                sheet.setColumnWidth(i, width * 256);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void applyBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }
}
