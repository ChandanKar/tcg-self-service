package com.tcgdigital.vmcontrol.service.carbon;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads the AWS carbon emissions Data Export (E37-T01): lists the CSV files (gzip or plain) under the
 * export prefix and maps their columns by header name. The column names are properties
 * (carbon.columns.*) because they must be checked against the live export; the defaults follow
 * the Data Exports carbon emissions table. Emissions are converted to kg CO2e from the unit column
 * (MTCO2e or tCO2e x 1000, kgCO2e x 1) or, without one, carbon.columns.unit-multiplier.
 * Rows for the same month, account, region and service are summed.
 */
@Component
public class CarbonExportReader {

    /** One export file. */
    public record ExportFile(String key, String etag, long size) {
        /** The folder the file is in; the parts of one export execution share it. */
        public String folder() {
            int slash = key.lastIndexOf('/');
            return slash < 0 ? "" : key.substring(0, slash);
        }
    }

    /** One month of emissions for an account, region and service, in kg CO2e. */
    public record CarbonRow(LocalDate month, String account, String region, String service, BigDecimal mbmKg, BigDecimal lbmKg) {
    }

    /** Which header names hold which value. */
    public record Columns(String month, String account, String region, String service, String mbm, String lbm,
                          String mbmUnit, String lbmUnit, BigDecimal unitMultiplier) {
        public static Columns defaults() {
            return new Columns("usage_period_start", "usage_account_id", "region_code", "product_code",
                    "total_mbm_emissions_value", "total_lbm_emissions_value", "total_mbm_emissions_unit",
                    "total_lbm_emissions_unit", new BigDecimal("1000"));
        }
    }

    @Value("${carbon.columns.month:usage_period_start}")
    private String monthColumn = "usage_period_start";
    @Value("${carbon.columns.account:usage_account_id}")
    private String accountColumn = "usage_account_id";
    @Value("${carbon.columns.region:region_code}")
    private String regionColumn = "region_code";
    @Value("${carbon.columns.service:product_code}")
    private String serviceColumn = "product_code";
    @Value("${carbon.columns.mbm:total_mbm_emissions_value}")
    private String mbmColumn = "total_mbm_emissions_value";
    @Value("${carbon.columns.lbm:total_lbm_emissions_value}")
    private String lbmColumn = "total_lbm_emissions_value";
    @Value("${carbon.columns.mbm-unit:total_mbm_emissions_unit}")
    private String mbmUnitColumn = "total_mbm_emissions_unit";
    @Value("${carbon.columns.lbm-unit:total_lbm_emissions_unit}")
    private String lbmUnitColumn = "total_lbm_emissions_unit";
    @Value("${carbon.columns.unit-multiplier:1000}")
    private BigDecimal unitMultiplier = new BigDecimal("1000");

    public Columns columns() {
        return new Columns(monthColumn, accountColumn, regionColumn, serviceColumn, mbmColumn, lbmColumn, mbmUnitColumn,
                lbmUnitColumn, unitMultiplier);
    }

    /** The CSV files under the prefix (manifests and other files are left out). */
    public List<ExportFile> list(S3Client s3, String bucket, String prefix) {
        List<ExportFile> files = new ArrayList<>();
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix == null ? "" : prefix).build();
        for (S3Object o : s3.listObjectsV2Paginator(request).contents()) {
            String key = o.key().toLowerCase(Locale.ROOT);
            if (key.endsWith(".csv.gz") || key.endsWith(".csv")) {
                files.add(new ExportFile(o.key(), o.eTag() == null ? "" : o.eTag().replace("\"", ""), o.size() == null ? 0 : o.size()));
            }
        }
        return files;
    }

    public List<CarbonRow> read(S3Client s3, String bucket, ExportFile file) throws IOException {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(file.key()).build())) {
            return parse(in, file.key().toLowerCase(Locale.ROOT).endsWith(".gz"), columns());
        }
    }

    static List<CarbonRow> parse(InputStream raw, boolean gzip, Columns columns) throws IOException {
        InputStream in = gzip ? new GZIPInputStream(raw) : raw;
        try (Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            CsvReader csv = new CsvReader(reader);
            List<String> header = csv.next();
            if (header == null) return List.of();
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                index.put(header.get(i).trim().replace("﻿", "").toLowerCase(Locale.ROOT), i);
            }
            int month = required(index, columns.month());
            int account = required(index, columns.account());
            int region = required(index, columns.region());
            int service = required(index, columns.service());
            Integer mbm = index.get(columns.mbm().toLowerCase(Locale.ROOT));
            Integer lbm = index.get(columns.lbm().toLowerCase(Locale.ROOT));
            if (mbm == null && lbm == null) {
                throw new IOException("The export has neither " + columns.mbm() + " nor " + columns.lbm());
            }
            Integer mbmUnit = columns.mbmUnit() == null ? null : index.get(columns.mbmUnit().toLowerCase(Locale.ROOT));
            Integer lbmUnit = columns.lbmUnit() == null ? null : index.get(columns.lbmUnit().toLowerCase(Locale.ROOT));

            Map<String, CarbonRow> rows = new LinkedHashMap<>();
            List<String> r;
            while ((r = csv.next()) != null) {
                if (r.size() == 1 && r.get(0).isBlank()) continue;
                LocalDate m = month(cell(r, month));
                String acct = cell(r, account);
                if (m == null || acct.isEmpty()) continue;
                String reg = cell(r, region).isEmpty() ? "global" : cell(r, region);
                String svc = cell(r, service).isEmpty() ? "unknown" : cell(r, service);
                BigDecimal mbmKg = kg(r, mbm, mbmUnit, columns.unitMultiplier());
                BigDecimal lbmKg = kg(r, lbm, lbmUnit, columns.unitMultiplier());
                String key = m + "|" + acct + "|" + reg + "|" + svc;
                CarbonRow before = rows.get(key);
                rows.put(key, before == null ? new CarbonRow(m, acct, reg, svc, mbmKg, lbmKg)
                        : new CarbonRow(m, acct, reg, svc, add(before.mbmKg(), mbmKg), add(before.lbmKg(), lbmKg)));
            }
            return new ArrayList<>(rows.values());
        }
    }

    private static int required(Map<String, Integer> index, String name) throws IOException {
        Integer i = index.get(name.toLowerCase(Locale.ROOT));
        if (i == null) throw new IOException("The export has no column " + name + " (set carbon.columns.*)");
        return i;
    }

    private static String cell(List<String> row, Integer i) {
        return i == null || i >= row.size() || row.get(i) == null ? "" : row.get(i).trim();
    }

    /** The first of the month from 2026-08, 2026-08-01 or 2026-08-01T00:00:00Z. */
    static LocalDate month(String text) {
        if (text.length() < 7) return null;
        try {
            return LocalDate.parse(text.substring(0, 7) + "-01");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal kg(List<String> row, Integer value, Integer unit, BigDecimal defaultMultiplier) {
        String v = cell(row, value);
        if (v.isEmpty()) return null;
        BigDecimal amount;
        try {
            amount = new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
        String u = cell(row, unit).toLowerCase(Locale.ROOT).replace(" ", "").replace("₂", "2");
        BigDecimal multiplier = u.isEmpty() ? defaultMultiplier
                : u.startsWith("kg") ? BigDecimal.ONE
                : u.startsWith("g") ? new BigDecimal("0.001")
                : new BigDecimal("1000"); // MTCO2e, tCO2e: metric tons
        return amount.multiply(multiplier);
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.add(b);
    }

    /** A small RFC 4180 reader: quoted fields, doubled quotes, line breaks inside quotes. */
    static final class CsvReader {
        private final Reader reader;
        private boolean done;

        CsvReader(Reader reader) {
            this.reader = reader;
        }

        List<String> next() throws IOException {
            if (done) return null;
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean any = false;
            int c;
            while ((c = reader.read()) != -1) {
                any = true;
                char ch = (char) c;
                if (quoted) {
                    if (ch == '"') {
                        reader.mark(1);
                        int n = reader.read();
                        if (n == '"') {
                            field.append('"');
                        } else {
                            quoted = false;
                            if (n != -1) reader.reset();
                        }
                    } else {
                        field.append(ch);
                    }
                } else if (ch == '"') {
                    quoted = true;
                } else if (ch == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                } else if (ch == '\n') {
                    fields.add(field.toString());
                    return fields;
                } else if (ch != '\r') {
                    field.append(ch);
                }
            }
            done = true;
            if (!any) return null;
            fields.add(field.toString());
            return fields;
        }
    }
}
