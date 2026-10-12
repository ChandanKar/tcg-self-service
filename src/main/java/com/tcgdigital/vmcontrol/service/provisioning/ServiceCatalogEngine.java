package com.tcgdigital.vmcontrol.service.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningEngineType;
import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.servicecatalog.ServiceCatalogClient;
import software.amazon.awssdk.services.servicecatalog.model.DescribeRecordRequest;
import software.amazon.awssdk.services.servicecatalog.model.DescribeRecordResponse;
import software.amazon.awssdk.services.servicecatalog.model.GetProvisionedProductOutputsRequest;
import software.amazon.awssdk.services.servicecatalog.model.GetProvisionedProductOutputsResponse;
import software.amazon.awssdk.services.servicecatalog.model.ProvisionProductRequest;
import software.amazon.awssdk.services.servicecatalog.model.ProvisioningParameter;
import software.amazon.awssdk.services.servicecatalog.model.RecordDetail;
import software.amazon.awssdk.services.servicecatalog.model.RecordError;
import software.amazon.awssdk.services.servicecatalog.model.RecordOutput;
import software.amazon.awssdk.services.servicecatalog.model.Tag;
import software.amazon.awssdk.services.servicecatalog.model.TerminateProvisionedProductRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * AWS Service Catalog as a provisioning engine (E32-T02): a template names a product, its
 * provisioning artifact (version) and optionally a launch path; the launch constraint role in
 * Service Catalog creates the resources. Requests are idempotent by request id. The product is
 * tagged with the discovery tag and the environment's stored name, so its instances are found and
 * registered once it is built (the product must propagate the tags to its instances).
 */
@Service
public class ServiceCatalogEngine implements ProvisioningEngine {

    private final BiFunction<String, String, ServiceCatalogClient> clients;
    private final ObjectMapper objectMapper;

    /** The tag VM discovery matches to an environment (E09); its value is the environment's stored name. */
    @org.springframework.beans.factory.annotation.Value("${vm.discovery.tag.environment-key:tcg:environment}")
    private String environmentTagKey = "tcg:environment";

    @org.springframework.beans.factory.annotation.Autowired
    public ServiceCatalogEngine(AwsClientFactory awsClientFactory, ObjectMapper objectMapper) {
        this(awsClientFactory::serviceCatalog, objectMapper);
    }

    /** Test seam: a fixed client per account and region. */
    ServiceCatalogEngine(BiFunction<String, String, ServiceCatalogClient> clients, ObjectMapper objectMapper) {
        this.clients = clients;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProvisioningEngineType type() {
        return ProvisioningEngineType.SERVICE_CATALOG;
    }

    private ServiceCatalogClient client(EnvironmentTemplate template) {
        return clients.apply(template.getAwsAccountRef(), template.getRegion());
    }

    private JsonNode read(String json, String what) {
        try {
            return json == null || json.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ValidationException("The " + what + " is not valid JSON");
        }
    }

    /** Service Catalog names: a letter or digit, then letters, digits, '.', '_' or '-', at most 128. */
    static String productName(String environmentName) {
        String name = environmentName.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("^[^A-Za-z0-9]+", "");
        if (name.isEmpty()) {
            name = "env";
        }
        return name.length() > 128 ? name.substring(0, 128) : name;
    }

    @Override
    public String provision(ProvisioningRequest request, EnvironmentTemplate template) {
        JsonNode ref = read(template.getEngineRef(), "engine reference");
        JsonNode values = read(request.getParameters(), "request parameters");
        List<ProvisioningParameter> parameters = new ArrayList<>();
        values.fields().forEachRemaining(e -> parameters.add(ProvisioningParameter.builder()
                .key(e.getKey()).value(e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString()).build()));
        ProvisionProductRequest.Builder builder = ProvisionProductRequest.builder()
                .productId(ref.path("productId").asText())
                .provisioningArtifactId(ref.path("provisioningArtifactId").asText())
                .provisionedProductName(productName(request.getEnvironmentName()))
                .provisioningParameters(parameters)
                .provisionToken(request.getRequestId())
                .tags(Tag.builder().key(environmentTagKey)
                                .value(com.tcgdigital.vmcontrol.service.support.NameNormalizer.slug(request.getEnvironmentName())).build(),
                        Tag.builder().key("tcg:managed-by").value("vmcontrol").build(),
                        Tag.builder().key("tcg:provisioning-request").value(request.getRequestId()).build());
        if (ref.hasNonNull("launchPathId") && !ref.get("launchPathId").asText().isBlank()) {
            builder.pathId(ref.get("launchPathId").asText());
        }
        RecordDetail record = client(template).provisionProduct(builder.build()).recordDetail();
        return record.recordId();
    }

    @Override
    public ProvisioningProgress poll(EnvironmentTemplate template, String recordId) {
        DescribeRecordResponse response = client(template).describeRecord(DescribeRecordRequest.builder().id(recordId).build());
        RecordDetail record = response.recordDetail();
        String resourceId = record.provisionedProductId();
        String status = record.statusAsString();
        if ("SUCCEEDED".equals(status)) {
            return new ProvisioningProgress(ProvisioningProgress.State.SUCCEEDED, resourceId, "Succeeded");
        }
        if ("FAILED".equals(status)) {
            String message = record.recordErrors() == null || record.recordErrors().isEmpty() ? "Service Catalog reported a failure"
                    : record.recordErrors().stream().map(RecordError::description).filter(d -> d != null && !d.isBlank())
                            .collect(Collectors.joining("; "));
            return new ProvisioningProgress(ProvisioningProgress.State.FAILED, resourceId,
                    message.isBlank() ? "Service Catalog reported a failure" : message);
        }
        // CREATED, IN_PROGRESS, IN_PROGRESS_IN_ERROR: still running (an error may still roll back)
        return new ProvisioningProgress(ProvisioningProgress.State.IN_PROGRESS, resourceId, status);
    }

    @Override
    public Map<String, String> outputs(EnvironmentTemplate template, String resourceId) {
        Map<String, String> outputs = new LinkedHashMap<>();
        String token = null;
        do {
            GetProvisionedProductOutputsResponse page = client(template).getProvisionedProductOutputs(
                    GetProvisionedProductOutputsRequest.builder().provisionedProductId(resourceId).pageToken(token).build());
            for (RecordOutput o : page.outputs()) {
                if (o.outputKey() != null) {
                    outputs.put(o.outputKey(), o.outputValue());
                }
            }
            token = page.nextPageToken();
        } while (token != null && !token.isEmpty());
        return outputs;
    }

    @Override
    public String destroy(ProvisioningRequest request, EnvironmentTemplate template) {
        return client(template).terminateProvisionedProduct(TerminateProvisionedProductRequest.builder()
                        .provisionedProductId(request.getEngineResourceId())
                        .terminateToken(request.getRequestId() + "-destroy")
                        .build())
                .recordDetail().recordId();
    }
}
