package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmIdleSummaryRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A running EKS node group gets CloudWatch samples through the metrics sync and, after six
 * quiet samples, an idle summary (E26-T01). CloudWatch is mocked.
 */
class EksMetricsSyncIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AwsEksMetricsProviderService eksMetrics;
    @Autowired private VmMetricsService metricsService;
    @Autowired private VmRepository vms;
    @Autowired private VmMetricSampleRepository samples;
    @Autowired private VmIdleSummaryRepository idle;

    private Object target;
    private Object realFactory;

    @AfterEach
    void restore() {
        if (target != null) {
            ReflectionTestUtils.setField(target, "enabled", false);
            ReflectionTestUtils.setField(target, "clientFactory", realFactory);
        }
    }

    @Test
    void aRunningNodeGroupGetsSamplesAndAnIdleSummary() {
        Environment env = newEnvironment("EKS metrics");
        Vm vm = newVm(newGroup(env, "workers"), "workers", VmStatus.RUNNING);
        vm.setProvider(CloudProvider.AWS_EKS);
        vm.setProviderVmId("prod/workers-" + System.nanoTime());
        vm.setRegion("ap-south-1");
        vm.setMetadata("{\"minSize\":1,\"desiredSize\":2,\"asgNames\":[\"eks-ng-abc\"]}");
        vm = vms.saveAndFlush(vm);

        CloudWatchClient cloudWatch = Mockito.mock(CloudWatchClient.class);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        List<Instant> times = new ArrayList<>();
        List<Double> quiet = new ArrayList<>();
        for (int i = 7; i >= 1; i--) {
            times.add(now.minusSeconds(300L * i));
            quiet.add(1.0);
        }
        when(cloudWatch.getMetricData(any(GetMetricDataRequest.class))).thenAnswer(inv -> {
            GetMetricDataRequest request = inv.getArgument(0);
            List<MetricDataResult> results = new ArrayList<>();
            request.metricDataQueries().forEach(q -> results.add(MetricDataResult.builder().id(q.id()).timestamps(times).values(quiet).build()));
            return GetMetricDataResponse.builder().metricDataResults(results).build();
        });

        target = AopTestUtils.getTargetObject(eksMetrics);
        realFactory = ReflectionTestUtils.getField(target, "clientFactory");
        java.util.function.BiFunction<String, String, CloudWatchClient> fake = (accountRef, region) -> cloudWatch;
        ReflectionTestUtils.setField(target, "clientFactory", fake);
        ReflectionTestUtils.setField(target, "enabled", true);

        int saved = metricsService.syncRunningVmMetrics();

        assertThat(saved).isGreaterThanOrEqualTo(6);
        String vmId = vm.getVmId();
        assertThat(samples.findByVmVmIdAndSampleTimeBetweenOrderBySampleTimeAsc(vmId,
                Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now))).hasSizeGreaterThanOrEqualTo(6);
        assertThat(idle.findByVmVmId(vmId)).get().satisfies(s -> assertThat(s.getIdle()).isTrue());
    }
}
