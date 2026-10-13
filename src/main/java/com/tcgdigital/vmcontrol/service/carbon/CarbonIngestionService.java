package com.tcgdigital.vmcontrol.service.carbon;

import com.tcgdigital.vmcontrol.model.CarbonIngestFile;
import com.tcgdigital.vmcontrol.model.CarbonMonthly;
import com.tcgdigital.vmcontrol.repository.CarbonIngestFileRepository;
import com.tcgdigital.vmcontrol.repository.CarbonMonthlyRepository;
import com.tcgdigital.vmcontrol.service.aws.AwsAccountService;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Ingests the AWS carbon emissions Data Export (E37-T01) from S3 in the billing account, daily and on
 * demand. A folder whose files all have the key and ETag already ingested is skipped; otherwise every
 * file in it is read again (the parts of one export execution share a folder) and the months they
 * contain are replaced in one transaction. A file that cannot be read is logged and skipped; the next
 * run tries it again. Off unless carbon.enabled and a bucket is set.
 */
@Service
public class CarbonIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CarbonIngestionService.class);

    /** What a run did. */
    public record Result(String status, int files, int ingested, int skipped, int failed, int rows, List<String> months) {
        static Result off(String why) {
            return new Result(why, 0, 0, 0, 0, 0, List.of());
        }
    }

    @Value("${carbon.enabled:false}")
    private boolean enabled;

    @Value("${carbon.export.bucket:}")
    private String bucket = "";

    @Value("${carbon.export.prefix:}")
    private String prefix = "";

    @Value("${carbon.export.region:us-east-1}")
    private String region = "us-east-1";

    @Autowired(required = false)
    private SettingsService settings;

    private final AwsClientFactory clientFactory;
    private final AwsAccountService accountService;
    private CarbonExportReader reader; // not final: tests swap in an export held in memory
    private final CarbonMonthlyRepository carbonRepository;
    private final CarbonIngestFileRepository fileRepository;
    private final TransactionTemplate tx;
    private final ObjectProvider<CarbonMonthsListener> listeners;
    private Clock clock = Clock.systemUTC();

    /** Told which months changed, after they are committed (E37-T02 attributes them). */
    public interface CarbonMonthsListener {
        void monthsChanged(Set<LocalDate> months);
    }

    public CarbonIngestionService(AwsClientFactory clientFactory, AwsAccountService accountService, CarbonExportReader reader,
                                  CarbonMonthlyRepository carbonRepository, CarbonIngestFileRepository fileRepository,
                                  PlatformTransactionManager transactionManager, ObjectProvider<CarbonMonthsListener> listeners) {
        this.clientFactory = clientFactory;
        this.accountService = accountService;
        this.reader = reader;
        this.carbonRepository = carbonRepository;
        this.fileRepository = fileRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.listeners = listeners;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public boolean isEnabled() {
        return settings == null ? enabled : settings.overrideOr("carbon.enabled", enabled);
    }

    public Result ingest() {
        if (!isEnabled()) return Result.off("disabled");
        if (bucket == null || bucket.isBlank()) return Result.off("no bucket configured (carbon.export.bucket)");
        if (!clientFactory.isConfigured()) return Result.off("no AWS credentials");
        S3Client s3 = clientFactory.s3(accountService.getBillingAccountRef(), region);
        return ingest(s3);
    }

    Result ingest(S3Client s3) {
        List<CarbonExportReader.ExportFile> files = reader.list(s3, bucket, prefix);
        Map<String, List<CarbonExportReader.ExportFile>> byFolder = new LinkedHashMap<>();
        files.forEach(f -> byFolder.computeIfAbsent(f.folder(), k -> new ArrayList<>()).add(f));

        int ingested = 0, skipped = 0, failed = 0, rows = 0;
        Set<LocalDate> changed = new TreeSet<>();
        for (List<CarbonExportReader.ExportFile> folder : byFolder.values()) {
            if (folder.stream().allMatch(this::alreadyIngested)) {
                skipped += folder.size();
                continue;
            }
            List<CarbonExportReader.CarbonRow> read = new ArrayList<>();
            Map<CarbonExportReader.ExportFile, Integer> counts = new LinkedHashMap<>();
            boolean ok = true;
            for (CarbonExportReader.ExportFile f : folder) {
                try {
                    List<CarbonExportReader.CarbonRow> part = reader.read(s3, bucket, f);
                    read.addAll(part);
                    counts.put(f, part.size());
                } catch (Exception e) {
                    log.warn("Carbon export file {} could not be read: {}", f.key(), e.getMessage());
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                failed += folder.size();
                continue;
            }
            Set<LocalDate> months = store(merge(read), counts);
            changed.addAll(months);
            ingested += folder.size();
            rows += read.size();
        }
        if (!changed.isEmpty()) {
            for (CarbonMonthsListener l : listeners.orderedStream().toList()) {
                try {
                    l.monthsChanged(changed);
                } catch (Exception e) {
                    log.warn("Carbon follow-up for {} failed: {}", changed, e.getMessage());
                }
            }
        }
        return new Result("ok", files.size(), ingested, skipped, failed, rows, changed.stream().map(m -> m.toString().substring(0, 7)).toList());
    }

    private boolean alreadyIngested(CarbonExportReader.ExportFile f) {
        Optional<CarbonIngestFile> seen = fileRepository.findById(f.key());
        return seen.isPresent() && seen.get().getEtag().equals(f.etag());
    }

    /** Parts of one folder may repeat a month, account, region and service: their values add up. */
    private static List<CarbonExportReader.CarbonRow> merge(List<CarbonExportReader.CarbonRow> rows) {
        Map<String, CarbonExportReader.CarbonRow> merged = new LinkedHashMap<>();
        for (CarbonExportReader.CarbonRow r : rows) {
            String key = r.month() + "|" + r.account() + "|" + r.region() + "|" + r.service();
            merged.merge(key, r, (a, b) -> new CarbonExportReader.CarbonRow(a.month(), a.account(), a.region(), a.service(),
                    a.mbmKg() == null ? b.mbmKg() : b.mbmKg() == null ? a.mbmKg() : a.mbmKg().add(b.mbmKg()),
                    a.lbmKg() == null ? b.lbmKg() : b.lbmKg() == null ? a.lbmKg() : a.lbmKg().add(b.lbmKg())));
        }
        return new ArrayList<>(merged.values());
    }

    private Set<LocalDate> store(List<CarbonExportReader.CarbonRow> rows, Map<CarbonExportReader.ExportFile, Integer> files) {
        Set<LocalDate> months = new TreeSet<>();
        rows.forEach(r -> months.add(r.month()));
        String source = files.keySet().iterator().next().folder();
        Timestamp now = Timestamp.from(clock.instant());
        tx.executeWithoutResult(status -> {
            if (!months.isEmpty()) carbonRepository.deleteByMonthIn(months);
            carbonRepository.flush();
            List<CarbonMonthly> entities = rows.stream().map(r -> {
                CarbonMonthly c = new CarbonMonthly();
                c.setCarbonId(UUID.randomUUID().toString());
                c.setMonth(r.month());
                c.setAccountNumber(r.account().length() > 12 ? r.account().substring(0, 12) : r.account());
                c.setRegion(r.region().length() > 30 ? r.region().substring(0, 30) : r.region());
                c.setServiceCode(r.service().length() > 100 ? r.service().substring(0, 100) : r.service());
                c.setEmissionsMbmKg(r.mbmKg());
                c.setEmissionsLbmKg(r.lbmKg());
                c.setSourceFile(source.length() > 1024 ? source.substring(0, 1024) : source);
                return c;
            }).toList();
            carbonRepository.saveAll(entities);
            files.forEach((f, count) -> {
                CarbonIngestFile seen = fileRepository.findById(f.key()).orElseGet(() -> new CarbonIngestFile(f.key(), f.etag(), now, count));
                seen.setEtag(f.etag());
                seen.setIngestedAt(now);
                seen.setRowCount(count);
                fileRepository.save(seen);
            });
        });
        return months;
    }
}
