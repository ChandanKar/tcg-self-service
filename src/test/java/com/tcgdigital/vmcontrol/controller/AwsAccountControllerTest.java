package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import com.tcgdigital.vmcontrol.service.aws.AwsAccountAdminService;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The AWS account admin API (E28-T07): admins only, validation, deactivation rules and verify. */
class AwsAccountControllerTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/aws-accounts";
    private static final String ARN = "arn:aws:iam::210987654321:role/VmControlAccess";

    @Autowired private AwsAccountRepository accounts;
    @Autowired private AwsAccountAdminService adminService;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private JdbcTemplate jdbc;

    private Object target;
    private Object realFactory;

    @AfterEach
    void cleanUp() {
        if (target != null) {
            ReflectionTestUtils.setField(target, "clientFactory", realFactory);
            ReflectionTestUtils.setField(target, "multiAccountEnabled", false);
        }
        jdbc.update("UPDATE environment SET aws_account_ref = ?", AwsAccount.DEFAULT_REF);
        jdbc.update("DELETE FROM aws_account WHERE is_default = FALSE");
        jdbc.update("UPDATE aws_account SET is_billing_account = TRUE, account_number = NULL, last_error = NULL WHERE is_default = TRUE");
    }

    private static String body(String name, String arn, String externalId, String regions, Boolean billing) {
        return "{\"displayName\":\"" + name + "\"" + (arn == null ? "" : ",\"roleArn\":\"" + arn + "\"")
                + (externalId == null ? "" : ",\"externalId\":\"" + externalId + "\"")
                + (regions == null ? "" : ",\"regions\":" + regions)
                + (billing == null ? "" : ",\"isBillingAccount\":" + billing) + "}";
    }

    private String create(String name, String arn) throws Exception {
        String json = mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, arn, "ext-secret-9876", "[\"eu-west-1\"]", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.awsAccountRef");
    }

    @Test
    void anAdminRegistersAnAccountWithTheNumberFromTheArnAndItIsAudited() throws Exception {
        mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Payments", ARN, "ext-secret-9876", "[\"eu-west-1\",\"us-east-1\"]", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber").value("210987654321"))
                .andExpect(jsonPath("$.externalIdMasked").value("****9876"))
                .andExpect(jsonPath("$.regions.length()").value(2))
                .andExpect(jsonPath("$.source").value("MANUAL"));

        awaitAsync(() -> assertThat(auditLogs.findAll()).anyMatch(l -> l.getAction() == AuditAction.AWS_ACCOUNT_CREATED));

        mockMvc.perform(get(URL).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].displayName").value("Payments"))
                .andExpect(content().string(not(containsString("ext-secret-9876"))));
    }

    @Test
    void nonAdminsAreRefused() throws Exception {
        for (var who : new org.springframework.test.web.servlet.request.RequestPostProcessor[]{asEnvAdmin(), asUser(), asViewer()}) {
            mockMvc.perform(get(URL).with(who)).andExpect(status().isForbidden());
            mockMvc.perform(post(URL).with(who).contentType(MediaType.APPLICATION_JSON).content(body("X", ARN, null, null, null)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void badArnsAndRegionsAndDuplicatesAreRejected() throws Exception {
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("Bad", "arn:aws:iam::123:user/bob", null, null, null))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("Bad", ARN, null, "[\"Mars-1\"]", null))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("No role", null, null, null, null))), 400);

        create("Payments", ARN);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("Again", ARN, null, null, null))), 409);
    }

    @Test
    void anAccountWithActiveEnvironmentsOrTheDefaultCannotBeDeactivated() throws Exception {
        String ref = create("Payments", ARN);
        Environment env = newEnvironment("Payments prod");
        jdbc.update("UPDATE environment SET aws_account_ref = ? WHERE environment_id = ?", ref, env.getEnvironmentId());

        expectError(mockMvc.perform(delete(URL + "/" + ref).with(asAdmin())), 409);
        expectError(mockMvc.perform(delete(URL + "/" + AwsAccount.DEFAULT_REF).with(asAdmin())), 409);

        jdbc.update("UPDATE environment SET aws_account_ref = ? WHERE environment_id = ?", AwsAccount.DEFAULT_REF, env.getEnvironmentId());
        mockMvc.perform(delete(URL + "/" + ref).with(asAdmin())).andExpect(status().isNoContent());
        assertThat(accounts.findById(ref)).get().satisfies(a -> assertThat(a.isActive()).isFalse());
    }

    @Test
    void billingMovesToTheNewAccountAndAnEditKeepsTheExternalId() throws Exception {
        String ref = create("Payer", ARN);
        mockMvc.perform(put(URL + "/" + ref).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Payer org", ARN, null, null, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isBillingAccount").value(true))
                .andExpect(jsonPath("$.externalIdMasked").value("****9876"));
        assertThat(accounts.findById(AwsAccount.DEFAULT_REF)).get().satisfies(a -> assertThat(a.isBillingAccount()).isFalse());
    }

    @Test
    void importedAccountsAreCreatedWithTheirSourceAndRegisteredOnesSkipped() throws Exception {
        create("Payments", ARN);
        String json = mockMvc.perform(post(URL + "/import").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accounts\":["
                                + "{\"accountNumber\":\"111111111111\",\"displayName\":\"Shared\",\"roleArn\":\"arn:aws:iam::111111111111:role/VmControlAccess\"},"
                                + "{\"accountNumber\":\"210987654321\",\"displayName\":\"Again\",\"roleArn\":\"" + ARN + "\"},"
                                + "{\"accountNumber\":\"333333333333\",\"displayName\":\"Wrong\",\"roleArn\":\"arn:aws:iam::444444444444:role/X\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created.length()").value(1))
                .andExpect(jsonPath("$.created[0].source").value("ORGANIZATIONS"))
                .andExpect(jsonPath("$.skipped.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).contains("already registered").contains("another account");

        expectError(mockMvc.perform(get(URL + "/organization-candidates").with(asAdmin())), 400); // discovery off
        mockMvc.perform(get(URL + "/organization-candidates").with(asEnvAdmin())).andExpect(status().isForbidden());
    }

    @Test
    void verifyRecordsSuccessOrTheMismatchedAccount() throws Exception {
        String ref = create("Payments", ARN);
        AwsClientFactory factory = Mockito.mock(AwsClientFactory.class);
        StsClient sts = Mockito.mock(StsClient.class);
        Mockito.when(factory.sts(ref, null)).thenReturn(sts);
        Mockito.when(sts.getCallerIdentity()).thenReturn(GetCallerIdentityResponse.builder().account("210987654321").build(),
                GetCallerIdentityResponse.builder().account("999999999999").build());
        target = AopTestUtils.getTargetObject(adminService);
        realFactory = ReflectionTestUtils.getField(target, "clientFactory");
        ReflectionTestUtils.setField(target, "clientFactory", factory);

        expectError(mockMvc.perform(post(URL + "/" + ref + "/verify").with(asAdmin())), 400); // multi-account off

        ReflectionTestUtils.setField(target, "multiAccountEnabled", true);
        mockMvc.perform(post(URL + "/" + ref + "/verify").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastVerifiedAt").isNotEmpty())
                .andExpect(jsonPath("$.lastError").doesNotExist());
        mockMvc.perform(post(URL + "/" + ref + "/verify").with(asAdmin()))
                .andExpect(jsonPath("$.lastError").value("Role resolved to account 999999999999"));
    }
}
