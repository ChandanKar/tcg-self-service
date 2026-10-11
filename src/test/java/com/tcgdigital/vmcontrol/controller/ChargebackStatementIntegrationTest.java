package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.ChargebackStatementRepository;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.scheduler.ChargebackScheduler;
import com.tcgdigital.vmcontrol.service.ChargebackService;
import com.tcgdigital.vmcontrol.service.EmailService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Frozen chargeback statements (E23-T07): finalize freezes, twice is 409, only finished months, the monthly send. */
class ChargebackStatementIntegrationTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/cost-management/chargeback";
    private static final YearMonth MONTH = YearMonth.now().minusMonths(1);

    @Autowired private CostDailySnapshotRepository snapshots;
    @Autowired private ChargebackStatementRepository statements;
    @Autowired private ChargebackService chargebackService;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private ApplicationContext context;

    private void cost(Environment env, int day, String amount) {
        CostDailySnapshot s = new CostDailySnapshot();
        s.setEnvironment(env);
        s.setSnapshotDate(Date.valueOf(MONTH.atDay(day)));
        s.setEstimatedCost(new BigDecimal(amount));
        snapshots.saveAndFlush(s);
    }

    @Test
    void aFinalizedMonthStaysFrozen() throws Exception {
        Environment env = newEnvironment("Frozen");
        cost(env, 1, "100.00");
        mockMvc.perform(get(URL).param("month", MONTH.toString()).with(asAdmin()))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(post(URL + "/" + MONTH + "/finalize").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINAL"))
                .andExpect(jsonPath("$.totals.total").value(100.00));

        // Late billing for the month arrives after it was frozen.
        cost(env, 2, "25.00");
        mockMvc.perform(get(URL).param("month", MONTH.toString()).with(asAdmin()))
                .andExpect(jsonPath("$.status").value("FINAL"))
                .andExpect(jsonPath("$.totals.total").value(100.00))
                .andExpect(jsonPath("$.rows[0].environments[0].environmentName").value("Frozen"));
        String csv = mockMvc.perform(get(URL + "/export").param("month", MONTH.toString()).param("format", "csv").with(asAdmin()))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("100.00").doesNotContain("125.00");

        expectError(mockMvc.perform(post(URL + "/" + MONTH + "/finalize").with(asAdmin())), 409);
        awaitAsync(() -> assertThat(auditLogs.findAll()).extracting(a -> a.getAction())
                .contains(AuditAction.CHARGEBACK_FINALIZED));
    }

    @Test
    void onlyFinishedMonthsAndOnlyAdmins() throws Exception {
        expectError(mockMvc.perform(post(URL + "/" + YearMonth.now() + "/finalize").with(asAdmin())), 400);
        expectError(mockMvc.perform(post(URL + "/" + YearMonth.now().plusMonths(1) + "/finalize").with(asAdmin())), 400);
        expectError(mockMvc.perform(post(URL + "/nope/finalize").with(asAdmin())), 400);
        mockMvc.perform(post(URL + "/" + MONTH + "/finalize").with(asUser())).andExpect(status().isForbidden());
        assertThat(statements.count()).isZero();
    }

    @Test
    void theMonthlyJobIsOffByDefaultAndSendsTheWorkbookOnceWhenRun() {
        assertThat(context.getBeanProvider(ChargebackScheduler.class).getIfAvailable()).isNull();

        cost(newEnvironment("Mailed"), 3, "9.99");
        Object target = AopTestUtils.getTargetObject(chargebackService);
        EmailService real = (EmailService) ReflectionTestUtils.getField(target, "emailService");
        EmailService email = Mockito.mock(EmailService.class);
        ReflectionTestUtils.setField(target, "emailService", email);
        ReflectionTestUtils.setField(target, "recipients", "finance@example.com, ap@example.com ,");
        try {
            chargebackService.finalizeAndSendPreviousMonth();
            chargebackService.finalizeAndSendPreviousMonth();
        } finally {
            ReflectionTestUtils.setField(target, "emailService", real);
            ReflectionTestUtils.setField(target, "recipients", "");
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<byte[]> workbook = ArgumentCaptor.forClass(byte[].class);
        verify(email).sendHtml(to.capture(), eq("Chargeback statement " + MONTH), anyString(),
                eq("chargeback-" + MONTH + ".xlsx"), workbook.capture(), eq("CHARGEBACK_STATEMENT"), isNull(), isNull());
        assertThat(to.getValue()).containsExactly("finance@example.com", "ap@example.com");
        assertThat(workbook.getValue()).isNotEmpty();
        assertThat(statements.findByMonth(MONTH.toString())).get()
                .satisfies(s -> assertThat(s.getTotal()).isEqualByComparingTo("9.99"));
    }

    @Test
    void withNoRecipientsTheMonthIsFrozenButNothingIsSent() {
        Object target = AopTestUtils.getTargetObject(chargebackService);
        EmailService real = (EmailService) ReflectionTestUtils.getField(target, "emailService");
        EmailService email = Mockito.mock(EmailService.class);
        ReflectionTestUtils.setField(target, "emailService", email);
        try {
            chargebackService.finalizeAndSendPreviousMonth();
        } finally {
            ReflectionTestUtils.setField(target, "emailService", real);
        }
        verify(email, never()).sendHtml(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(statements.findByMonth(MONTH.toString())).isPresent();
    }
}
