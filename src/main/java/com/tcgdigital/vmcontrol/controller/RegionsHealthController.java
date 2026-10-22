package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.service.aws.AwsAccountService;
import com.tcgdigital.vmcontrol.service.aws.AwsRegionService;
import com.tcgdigital.vmcontrol.service.aws.RegionalServiceNotes;
import com.tcgdigital.vmcontrol.service.discovery.AwsResourceSweep;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * System health's Regions tab (U19-T06, U19-T08): each account's enabled regions, what the last
 * sweeps found in each region or why they could not read it, and the services that are not offered,
 * not turned on or refused in a region.
 */
@RestController
@RequestMapping("/api/v1/monitoring/regions")
@Tag(name = "Monitoring", description = "Regions for System health")
public class RegionsHealthController {

    public record AccountRegions(String accountRef, String accountName, List<String> enabled, List<String> refused,
                                 Instant readAt, List<String> limit) { }

    public record Scan(String kind, String accountRef, String accountName, String region, Instant scannedAt, int found,
                       String error, boolean refused) { }

    public record Note(String service, String accountName, String region, String kind, String message, Instant at) { }

    public record RegionsHealth(List<AccountRegions> accounts, List<Scan> scans, List<Note> notes, List<String> excluded) { }

    private final AwsAccountService accounts;
    private final AwsRegionService regions;
    private final AwsResourceSweep sweep;
    private final RegionalServiceNotes notes;

    public RegionsHealthController(AwsAccountService accounts, AwsRegionService regions, AwsResourceSweep sweep,
                                   RegionalServiceNotes notes) {
        this.accounts = accounts;
        this.regions = regions;
        this.sweep = sweep;
        this.notes = notes;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Regions for System health",
            description = "Each active account's enabled regions (as last read) and refused ones, the last sweep of each "
                    + "account, region and kind with what it found or why it failed, and region-specific service notes.")
    public ResponseEntity<RegionsHealth> regions() {
        Map<String, String> names = accounts.namesByRef();
        List<AccountRegions> perAccount = accounts.listActive().stream().map((AwsAccount a) -> {
            List<AwsRegionService.RegionView> view = regions.view(a.getAwsAccountRef());
            List<String> enabled = view.stream().filter(v -> v.optInStatus() != null).map(AwsRegionService.RegionView::region).toList();
            List<String> refused = view.stream().filter(AwsRegionService.RegionView::refused).map(AwsRegionService.RegionView::region).toList();
            Instant readAt = view.stream().filter(v -> v.optInStatus() != null && v.readAt() != null)
                    .map(AwsRegionService.RegionView::readAt).min(Instant::compareTo).orElse(null);
            return new AccountRegions(a.getAwsAccountRef(), a.getDisplayName(), enabled, refused, readAt, a.getRegionList());
        }).toList();
        List<Scan> scans = sweep.lastOutcomes().stream().map(o -> new Scan(o.kind(), o.target().accountRef(),
                names.getOrDefault(o.target().accountRef(), o.target().accountRef()), o.target().region(), o.scannedAt(),
                o.found(), o.error(), o.refused())).toList();
        List<Note> serviceNotes = notes.notes().stream().map(n -> new Note(n.service(),
                n.accountRef() == null ? null : names.getOrDefault(n.accountRef(), n.accountRef()), n.region(), n.kind().name(),
                n.message(), n.at())).toList();
        return ResponseEntity.ok(new RegionsHealth(perAccount, scans, serviceNotes, List.copyOf(accounts.excludedRegionsView())));
    }
}
