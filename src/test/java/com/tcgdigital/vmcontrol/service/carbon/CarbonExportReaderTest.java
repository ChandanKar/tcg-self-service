package com.tcgdigital.vmcontrol.service.carbon;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parsing the carbon emissions Data Export (E37-T01). */
class CarbonExportReaderTest {

    private static List<CarbonExportReader.CarbonRow> fixture() throws IOException {
        try (InputStream in = CarbonExportReaderTest.class.getResourceAsStream("/carbon/sample-carbon-export.csv.gz")) {
            return CarbonExportReader.parse(in, true, CarbonExportReader.Columns.defaults());
        }
    }

    private static CarbonExportReader.CarbonRow row(List<CarbonExportReader.CarbonRow> rows, String service) {
        return rows.stream().filter(r -> r.service().equals(service)).findFirst().orElseThrow();
    }

    @Test
    void theFixtureBecomesMonthlyRowsInKilograms() throws IOException {
        List<CarbonExportReader.CarbonRow> rows = fixture();

        assertThat(rows).hasSize(3);
        CarbonExportReader.CarbonRow ec2 = row(rows, "AmazonEC2");
        assertThat(ec2.month()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(ec2.account()).isEqualTo("123456789012");
        assertThat(ec2.region()).isEqualTo("us-east-1");
        // Two rows of one bucket add up: 0.0125 + 0.0025 MTCO2e = 15 kg; 0.0200 + 0.0010 = 21 kg.
        assertThat(ec2.mbmKg()).isEqualByComparingTo("15");
        assertThat(ec2.lbmKg()).isEqualByComparingTo("21");

        CarbonExportReader.CarbonRow cdn = row(rows, "AmazonCloudFront");
        assertThat(cdn.region()).isEqualTo("global");
        assertThat(cdn.mbmKg()).isEqualByComparingTo("0.1");

        CarbonExportReader.CarbonRow rds = row(rows, "AmazonRDS");
        assertThat(rds.month()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(rds.mbmKg()).isEqualByComparingTo("0.5"); // already kgCO2e
        assertThat(rds.lbmKg()).isNull();
    }

    @Test
    void columnsAreConfigurableAndAMissingOneIsReported() throws IOException {
        String csv = "Month,Account,Region,Service,CO2\n2026-05,999,ap-south-1,AmazonS3,0.002\n";
        CarbonExportReader.Columns columns = new CarbonExportReader.Columns("month", "account", "region", "service", "co2", "lbm",
                null, null, new BigDecimal("1000"));

        List<CarbonExportReader.CarbonRow> rows = CarbonExportReader.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), false, columns);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.month()).isEqualTo(LocalDate.of(2026, 5, 1));
            assertThat(r.mbmKg()).isEqualByComparingTo("2");
            assertThat(r.lbmKg()).isNull();
        });
        assertThatThrownBy(() -> CarbonExportReader.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), false,
                CarbonExportReader.Columns.defaults())).isInstanceOf(IOException.class).hasMessageContaining("usage_period_start");
    }

    @Test
    void quotedFieldsKeepCommasQuotesAndLineBreaks() throws IOException {
        CarbonExportReader.CsvReader csv = new CarbonExportReader.CsvReader(new java.io.BufferedReader(
                new java.io.StringReader("a,\"b, \"\"c\"\"\nd\",e\r\nf,,\n")));

        assertThat(csv.next()).containsExactly("a", "b, \"c\"\nd", "e");
        assertThat(csv.next()).containsExactly("f", "", "");
        assertThat(csv.next()).isNull();
    }
}
