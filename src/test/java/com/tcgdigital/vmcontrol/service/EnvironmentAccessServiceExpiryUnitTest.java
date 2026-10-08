package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One failing grant does not stop the expiry batch (E04-T01, C4).
 */
@ExtendWith(MockitoExtension.class)
class EnvironmentAccessServiceExpiryUnitTest {

    @Mock private EnvironmentAccessRepository accessRepository;
    @Mock private AccessExpiryProcessor accessExpiryProcessor;

    @InjectMocks private EnvironmentAccessService accessService;

    @Test
    void aFailingRowIsSkippedAndTheRestAreExpired() {
        when(accessRepository.findExpiredAccessIds(any())).thenReturn(List.of("a1", "a2", "a3"));
        when(accessExpiryProcessor.expireOne("a1")).thenReturn(true);
        when(accessExpiryProcessor.expireOne("a2")).thenThrow(new IllegalStateException("deadlock"));
        when(accessExpiryProcessor.expireOne("a3")).thenReturn(true);

        assertThat(accessService.processExpiredAccess()).isEqualTo(2);
        verify(accessExpiryProcessor).expireOne("a3");
    }

    @Test
    void rowsAlreadyHandledElsewhereAreNotCounted() {
        when(accessRepository.findExpiredAccessIds(any())).thenReturn(List.of("a1", "a2"));
        when(accessExpiryProcessor.expireOne("a1")).thenReturn(false);
        when(accessExpiryProcessor.expireOne("a2")).thenReturn(true);

        assertThat(accessService.processExpiredAccess()).isEqualTo(1);
    }
}
