package com.tcgdigital.vmcontrol.service.carbon;

import com.tcgdigital.vmcontrol.model.CarbonMonthly;
import com.tcgdigital.vmcontrol.repository.CarbonIngestFileRepository;
import com.tcgdigital.vmcontrol.repository.CarbonMonthlyRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Ingesting carbon export files (E37-T01): idempotent by key and ETag, a changed file replaces its months. */
class CarbonIngestionServiceTest extends AbstractIntegrationTest {

    @Autowired private CarbonIngestionService service;
    @Autowired private CarbonMonthlyRepository carbonRepository;
    @Autowired private CarbonIngestFileRepository fileRepository;

    /** An export bucket in memory: files and the rows each one holds. */
    private final List<CarbonExportReader.ExportFile> files = new ArrayList<>();
    private final Map<String, List<CarbonExportReader.CarbonRow>> contents = new HashMap<>();
    private final List<String> reads = new ArrayList<>();
    private Object target;
    private Object originalReader;

    private final CarbonExportReader fakeReader = new CarbonExportReader() {
        @Override
        public List<ExportFile> list(S3Client s3, String bucket, String prefix) {
            return List.copyOf(files);
        }

        @Override
        public List<CarbonRow> read(S3Client s3, String bucket, ExportFile file) throws IOException {
            reads.add(file.key());
            if (!contents.containsKey(file.key())) throw new IOException("unreadable");
            return contents.get(file.key());
        }
    };

    private static CarbonExportReader.CarbonRow row(String month, String service, String mbm) {
        return new CarbonExportReader.CarbonRow(LocalDate.parse(month + "-01"), "123456789012", "us-east-1", service,
                new BigDecimal(mbm), null);
    }

    private void put(String key, String etag, CarbonExportReader.CarbonRow... rows) {
        files.removeIf(f -> f.key().equals(key));
        files.add(new CarbonExportReader.ExportFile(key, etag, 100));
        contents.put(key, List.of(rows));
    }

    @BeforeEach
    void setUp() {
        target = AopTestUtils.getTargetObject(service);
        originalReader = ReflectionTestUtils.getField(target, "reader");
        ReflectionTestUtils.setField(target, "reader", fakeReader);
        ReflectionTestUtils.setField(target, "enabled", true);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(target, "reader", originalReader);
        ReflectionTestUtils.setField(target, "enabled", false);
    }

    private CarbonIngestionService.Result run() {
        return ((CarbonIngestionService) target).ingest(mock(S3Client.class));
    }

    private BigDecimal stored(String month, String service) {
        return carbonRepository.findByMonth(LocalDate.parse(month + "-01")).stream()
                .filter(c -> c.getServiceCode().equals(service)).map(CarbonMonthly::getEmissionsMbmKg).findFirst().orElse(null);
    }

    @Test
    void theSameFileTwiceChangesNothingAndAChangedOneReplacesItsMonths() {
        put("carbon/data/BILLING_PERIOD=2026-08/part-1.csv.gz", "e1", row("2026-08", "AmazonEC2", "15"), row("2026-08", "AmazonRDS", "4"));
        put("carbon/data/BILLING_PERIOD=2026-07/part-1.csv.gz", "e2", row("2026-07", "AmazonEC2", "9"));

        CarbonIngestionService.Result first = run();
        assertThat(first.ingested()).isEqualTo(2);
        assertThat(first.months()).containsExactly("2026-07", "2026-08");
        assertThat(carbonRepository.count()).isEqualTo(3);

        reads.clear();
        CarbonIngestionService.Result again = run();
        assertThat(again.ingested()).isZero();
        assertThat(again.skipped()).isEqualTo(2);
        assertThat(reads).isEmpty();
        assertThat(carbonRepository.count()).isEqualTo(3);

        // AWS refreshed August: RDS is gone and EC2 changed; July stays as it was.
        put("carbon/data/BILLING_PERIOD=2026-08/part-1.csv.gz", "e1b", row("2026-08", "AmazonEC2", "16.5"));
        CarbonIngestionService.Result refreshed = run();
        assertThat(refreshed.months()).containsExactly("2026-08");
        assertThat(stored("2026-08", "AmazonEC2")).isEqualByComparingTo("16.5");
        assertThat(stored("2026-08", "AmazonRDS")).isNull();
        assertThat(stored("2026-07", "AmazonEC2")).isEqualByComparingTo("9");
        assertThat(fileRepository.findById("carbon/data/BILLING_PERIOD=2026-08/part-1.csv.gz")).get()
                .satisfies(f -> assertThat(f.getEtag()).isEqualTo("e1b"));
    }

    @Test
    void partsOfAFolderAreReadTogetherAndAnUnreadableFolderIsRetriedLater() {
        put("carbon/2026-09/part-1.csv.gz", "a", row("2026-09", "AmazonEC2", "1"));
        put("carbon/2026-09/part-2.csv.gz", "b", row("2026-09", "AmazonEC2", "2"), row("2026-09", "AmazonS3", "3"));
        files.add(new CarbonExportReader.ExportFile("carbon/2026-10/part-1.csv.gz", "c", 10)); // unreadable

        CarbonIngestionService.Result result = run();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(stored("2026-09", "AmazonEC2")).isEqualByComparingTo("3");
        assertThat(fileRepository.existsById("carbon/2026-10/part-1.csv.gz")).isFalse();

        // Only part 2 changed, but part 1 is read again so September keeps its EC2 row from part 1.
        put("carbon/2026-09/part-2.csv.gz", "b2", row("2026-09", "AmazonS3", "5"));
        reads.clear();
        run();
        assertThat(reads).contains("carbon/2026-09/part-1.csv.gz", "carbon/2026-09/part-2.csv.gz");
        assertThat(stored("2026-09", "AmazonEC2")).isEqualByComparingTo("1");
        assertThat(stored("2026-09", "AmazonS3")).isEqualByComparingTo("5");
    }

    @Test
    void offOrWithoutABucketNothingRuns() {
        ReflectionTestUtils.setField(target, "enabled", false);
        assertThat(service.ingest().status()).isEqualTo("disabled");
        ReflectionTestUtils.setField(target, "enabled", true);
        assertThat(service.ingest().status()).contains("no bucket");
    }
}
