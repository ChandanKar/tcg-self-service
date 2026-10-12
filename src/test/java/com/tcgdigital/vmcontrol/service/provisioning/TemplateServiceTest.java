package com.tcgdigital.vmcontrol.service.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.ProvisioningEngineType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Engine reference and parameter-schema checks of the template catalogue (E32-T01). */
class TemplateServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    void eachEngineNeedsItsOwnReference() throws Exception {
        assertThatCode(() -> TemplateService.validateEngineRef(ProvisioningEngineType.SERVICE_CATALOG,
                json("{\"productId\":\"prod-1\",\"provisioningArtifactId\":\"pa-1\"}"))).doesNotThrowAnyException();
        assertThatCode(() -> TemplateService.validateEngineRef(ProvisioningEngineType.TERRAFORM,
                json("{\"organization\":\"tcg\",\"workspacePrefix\":\"dev-\"}"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> TemplateService.validateEngineRef(ProvisioningEngineType.TERRAFORM,
                json("{\"productId\":\"prod-1\"}"))).isInstanceOf(ValidationException.class).hasMessageContaining("organization");
        assertThatThrownBy(() -> TemplateService.validateEngineRef(ProvisioningEngineType.SERVICE_CATALOG,
                json("[]"))).isInstanceOf(ValidationException.class);
    }

    @Test
    void schemaKeysTypesOptionsPatternsAndDefaultsAreChecked() throws Exception {
        assertThatCode(() -> TemplateService.validateSchema(json("[{\"key\":\"size\",\"type\":\"enum\",\"options\":[\"s\",\"l\"],\"default\":\"s\"},"
                + "{\"key\":\"count\",\"type\":\"number\",\"default\":2},{\"key\":\"owner\",\"pattern\":\"[a-z]+\",\"default\":\"ops\"}]")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TemplateService.validateSchema(json("[{\"key\":\"1bad\"}]"))).hasMessageContaining("keys start");
        assertThatThrownBy(() -> TemplateService.validateSchema(json("[{\"key\":\"x\",\"type\":\"date\"}]"))).hasMessageContaining("type");
        assertThatThrownBy(() -> TemplateService.validateSchema(json("[{\"key\":\"x\",\"type\":\"enum\",\"options\":[\"a\"],\"default\":\"b\"}]")))
                .hasMessageContaining("one of a");
        assertThatThrownBy(() -> TemplateService.validateSchema(json("[{\"key\":\"n\",\"type\":\"number\",\"default\":\"many\"}]")))
                .hasMessageContaining("number");
        assertThatThrownBy(() -> TemplateService.validateSchema(json("[{\"key\":\"o\",\"pattern\":\"[a-z]+\",\"default\":\"OPS\"}]")))
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> TemplateService.validateSchema(json("[{\"key\":\"o\",\"pattern\":\"[a-z\"}]")))
                .hasMessageContaining("invalid pattern");
        assertThatThrownBy(() -> TemplateService.validateSchema(json("{}"))).hasMessageContaining("array");
    }

    @Test
    void aValueFitsItsParameter() {
        assertThat(TemplateService.valueProblem("number", List.of(), null, "3.5")).isNull();
        assertThat(TemplateService.valueProblem("enum", List.of("a", "b"), null, "c")).contains("one of");
        assertThat(TemplateService.valueProblem("string", List.of(), Pattern.compile("[a-z]+"), "abc")).isNull();
    }
}
