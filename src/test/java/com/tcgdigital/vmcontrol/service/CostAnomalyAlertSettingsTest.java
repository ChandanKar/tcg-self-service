package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.repository.CostAnomalyMonitorRepository;
import com.tcgdigital.vmcontrol.repository.CostAnomalyRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** U15-T05: what the Unusual spend tab says about alerts, from the anomaly settings. */
class CostAnomalyAlertSettingsTest {

    @Test
    void theDefaultsReadEvery12HoursFrom10Dollars() {
        CostAnomalyService service = new CostAnomalyService(mock(CostAnomalyRepository.class), mock(CostAnomalyMonitorRepository.class),
                mock(CostExplorerClientProvider.class), new ObjectMapper(), 7, mock(NotificationService.class),
                mock(EnvironmentRepository.class), mock(UserRepository.class), mock(SecurityService.class), mock(AuditService.class));

        CostAnomalyService.AlertSettings settings = service.alertSettings();

        assertThat(settings.checked()).isEqualTo("Every 12 hours");
        assertThat(settings.minImpact()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(settings.feedbackToAws()).isFalse();
    }
}
