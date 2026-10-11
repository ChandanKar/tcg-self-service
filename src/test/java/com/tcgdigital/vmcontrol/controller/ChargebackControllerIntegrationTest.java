package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chargeback endpoints (E23-T06): admins only, month validation, JSON, CSV and XLSX. */
class ChargebackControllerIntegrationTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/cost-management/chargeback";
    private static final YearMonth MONTH = YearMonth.now().minusMonths(1);

    @Autowired private CostDailySnapshotRepository snapshots;

    private void seed() {
        Environment env = newEnvironment("Charged");
        CostDailySnapshot s = new CostDailySnapshot();
        s.setEnvironment(env);
        s.setSnapshotDate(Date.valueOf(MONTH.atDay(2)));
        s.setEstimatedCost(new BigDecimal("12.34"));
        snapshots.saveAndFlush(s);
    }

    @Test
    void adminsGetJsonAndLastMonthIsTheDefault() throws Exception {
        seed();
        mockMvc.perform(get(URL).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(MONTH.toString()))
                .andExpect(jsonPath("$.rows[0].teamName").value("Unassigned"))
                .andExpect(jsonPath("$.totals.total").value(12.34));
        mockMvc.perform(get(URL).param("month", MONTH.toString()).with(asAdmin()))
                .andExpect(jsonPath("$.rows[0].environments[0].environmentName").value("Charged"));
        expectError(mockMvc.perform(get(URL).param("month", YearMonth.now().plusMonths(2).toString()).with(asAdmin())), 400);
        expectError(mockMvc.perform(get(URL).param("month", "2026-13").with(asAdmin())), 400);
    }

    @Test
    void exportsAsXlsxAndCsv() throws Exception {
        seed();
        byte[] xlsx = mockMvc.perform(get(URL + "/export").param("month", MONTH.toString()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("chargeback-" + MONTH + ".xlsx")))
                .andExpect(header().string("Content-Type", containsString("spreadsheetml")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2); // header, Unassigned, Total
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(sheet.getRow(2).getCell(4).getNumericCellValue()).isEqualTo(12.34);
        }

        String csv = mockMvc.perform(get(URL + "/export").param("month", MONTH.toString()).param("format", "csv").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("chargeback-" + MONTH + ".csv")))
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("\"Unassigned\",\"\",12.34,0.00,12.34,0.0");
        expectError(mockMvc.perform(get(URL + "/export").param("format", "pdf").with(asAdmin())), 400);
    }

    @Test
    void onlyAdmins() throws Exception {
        mockMvc.perform(get(URL).with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).with(asEnvAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/export").with(asViewer())).andExpect(status().isForbidden());
    }
}
