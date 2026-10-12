package com.tcgdigital.vmcontrol.service.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningEngineType;
import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.servicecatalog.ServiceCatalogClient;
import software.amazon.awssdk.services.servicecatalog.model.DescribeRecordRequest;
import software.amazon.awssdk.services.servicecatalog.model.DescribeRecordResponse;
import software.amazon.awssdk.services.servicecatalog.model.GetProvisionedProductOutputsRequest;
import software.amazon.awssdk.services.servicecatalog.model.GetProvisionedProductOutputsResponse;
import software.amazon.awssdk.services.servicecatalog.model.ProvisionProductRequest;
import software.amazon.awssdk.services.servicecatalog.model.ProvisionProductResponse;
import software.amazon.awssdk.services.servicecatalog.model.RecordDetail;
import software.amazon.awssdk.services.servicecatalog.model.RecordError;
import software.amazon.awssdk.services.servicecatalog.model.RecordOutput;
import software.amazon.awssdk.services.servicecatalog.model.RecordStatus;
import software.amazon.awssdk.services.servicecatalog.model.Tag;
import software.amazon.awssdk.services.servicecatalog.model.TerminateProvisionedProductRequest;
import software.amazon.awssdk.services.servicecatalog.model.TerminateProvisionedProductResponse;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Service Catalog engine's requests and status mapping (E32-T02). */
class ServiceCatalogEngineTest {

    private final ServiceCatalogClient client = mock(ServiceCatalogClient.class);
    private ServiceCatalogEngine engine;
    private EnvironmentTemplate template;
    private ProvisioningRequest request;

    @BeforeEach
    void setUp() {
        engine = new ServiceCatalogEngine((account, region) -> client, new ObjectMapper());
        template = new EnvironmentTemplate();
        template.setEngine(ProvisioningEngineType.SERVICE_CATALOG);
        template.setEngineRef("{\"productId\":\"prod-abc\",\"provisioningArtifactId\":\"pa-1\",\"launchPathId\":\"lpv2-x\"}");
        template.setRegion("us-east-1");
        request = new ProvisioningRequest();
        request.setRequestId("req-1");
        request.setEnvironmentName("team a/dev");
        request.setParameters("{\"InstanceType\":\"t3.small\",\"Count\":2}");
    }

    @Test
    void provisioningSendsTheProductParametersTagsAndIdempotencyToken() {
        when(client.provisionProduct(any(ProvisionProductRequest.class))).thenReturn(ProvisionProductResponse.builder()
                .recordDetail(RecordDetail.builder().recordId("rec-1").build()).build());

        assertThat(engine.provision(request, template)).isEqualTo("rec-1");

        ArgumentCaptor<ProvisionProductRequest> sent = ArgumentCaptor.forClass(ProvisionProductRequest.class);
        verify(client).provisionProduct(sent.capture());
        ProvisionProductRequest r = sent.getValue();
        assertThat(r.productId()).isEqualTo("prod-abc");
        assertThat(r.provisioningArtifactId()).isEqualTo("pa-1");
        assertThat(r.pathId()).isEqualTo("lpv2-x");
        assertThat(r.provisionToken()).isEqualTo("req-1");
        assertThat(r.provisionedProductName()).isEqualTo("team-a-dev");
        assertThat(r.provisioningParameters()).extracting(p -> p.key() + "=" + p.value())
                .containsExactlyInAnyOrder("InstanceType=t3.small", "Count=2");
        Map<String, String> tags = r.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));
        assertThat(tags).containsEntry("tcg:environment", "team-a/dev").containsEntry("tcg:managed-by", "vmcontrol")
                .containsEntry("tcg:provisioning-request", "req-1");
    }

    private void record(RecordStatus status, RecordError... errors) {
        when(client.describeRecord(any(DescribeRecordRequest.class))).thenReturn(DescribeRecordResponse.builder()
                .recordDetail(RecordDetail.builder().recordId("rec-1").provisionedProductId("pp-1").status(status)
                        .recordErrors(errors).build()).build());
    }

    @Test
    void recordStatusesMapToProgress() {
        record(RecordStatus.IN_PROGRESS_IN_ERROR);
        assertThat(engine.poll(template, "rec-1").state()).isEqualTo(ProvisioningProgress.State.IN_PROGRESS);

        record(RecordStatus.SUCCEEDED);
        ProvisioningProgress done = engine.poll(template, "rec-1");
        assertThat(done.state()).isEqualTo(ProvisioningProgress.State.SUCCEEDED);
        assertThat(done.resourceId()).isEqualTo("pp-1");
    }

    @Test
    void aFailedRecordCarriesAwsErrorMessage() {
        record(RecordStatus.FAILED, RecordError.builder().code("CFN").description("Subnet subnet-x does not exist").build());

        ProvisioningProgress failed = engine.poll(template, "rec-1");

        assertThat(failed.state()).isEqualTo(ProvisioningProgress.State.FAILED);
        assertThat(failed.message()).isEqualTo("Subnet subnet-x does not exist");
    }

    @Test
    void outputsAreReadAcrossPagesAndDestroyIsIdempotent() {
        when(client.getProvisionedProductOutputs(any(GetProvisionedProductOutputsRequest.class)))
                .thenReturn(GetProvisionedProductOutputsResponse.builder()
                        .outputs(RecordOutput.builder().outputKey("ClusterName").outputValue("dev-a").build()).nextPageToken("p2").build())
                .thenReturn(GetProvisionedProductOutputsResponse.builder()
                        .outputs(RecordOutput.builder().outputKey("Region").outputValue("us-east-1").build()).build());
        assertThat(engine.outputs(template, "pp-1")).containsEntry("ClusterName", "dev-a").containsEntry("Region", "us-east-1");

        request.setEngineResourceId("pp-1");
        when(client.terminateProvisionedProduct(any(TerminateProvisionedProductRequest.class)))
                .thenReturn(TerminateProvisionedProductResponse.builder().recordDetail(RecordDetail.builder().recordId("rec-9").build()).build());
        assertThat(engine.destroy(request, template)).isEqualTo("rec-9");
        ArgumentCaptor<TerminateProvisionedProductRequest> sent = ArgumentCaptor.forClass(TerminateProvisionedProductRequest.class);
        verify(client).terminateProvisionedProduct(sent.capture());
        assertThat(sent.getValue().terminateToken()).isEqualTo("req-1-destroy");
        assertThat(sent.getValue().provisionedProductId()).isEqualTo("pp-1");
    }
}
