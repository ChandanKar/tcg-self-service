package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.model.ChatChannel;
import com.tcgdigital.vmcontrol.model.ChatDeliveryLog;
import com.tcgdigital.vmcontrol.model.ChatProvider;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.repository.ChatChannelRepository;
import com.tcgdigital.vmcontrol.repository.ChatDeliveryLogRepository;
import com.tcgdigital.vmcontrol.service.support.AfterCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Chat dispatch (E24-T03): channel resolution, global fallback, dedupe, failure isolation, the off switch. */
class ChatDispatchServiceTest {

    private final SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private ChatChannelRepository channels;
    private ChatDeliveryLogRepository logs;
    private ChatSender sender;
    private AfterCommit afterCommit;
    private ChatDispatchService service;

    @BeforeEach
    void setUp() {
        channels = mock(ChatChannelRepository.class);
        logs = mock(ChatDeliveryLogRepository.class);
        sender = mock(ChatSender.class);
        when(sender.provider()).thenReturn(ChatProvider.TEAMS);
        afterCommit = mock(AfterCommit.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(afterCommit).run(any());
        service = new ChatDispatchService(channels, logs, cipher, new ChatSenderRegistry(List.of(sender)), afterCommit, Runnable::run);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://vmc.example.com/");
        when(channels.findByEnvironmentIdIsNullAndIsActiveTrue()).thenReturn(List.of());
    }

    private ChatChannel channel(String id, String env, String events) {
        ChatChannel c = new ChatChannel();
        c.setChannelId(id);
        c.setEnvironmentId(env);
        c.setProvider(ChatProvider.TEAMS);
        c.setLabel("Channel " + id);
        c.setWebhookUrlEnc(cipher.encrypt("https://x.logic.azure.com/hook-" + id));
        c.setWebhookHost("x.logic.azure.com");
        c.setEventTypes(events);
        return c;
    }

    @Test
    void theEnvironmentsSubscribedChannelsGetTheMessageWithAnOpenLink() {
        when(channels.findByEnvironmentIdAndIsActiveTrue("env-1")).thenReturn(List.of(
                channel("a", "env-1", "OPERATION_FAILED,LOCK_BROKEN"), channel("b", "env-1", "LEASE_EXPIRING")));
        when(channels.findByEnvironmentIdIsNullAndIsActiveTrue()).thenReturn(List.of(channel("g", null, "OPERATION_FAILED")));

        service.notify("env-1", NotificationType.OPERATION_FAILED, ChatMessage.Severity.CRITICAL, "Stop failed: Dev", "Reason: boom");

        ArgumentCaptor<ChatMessage> message = ArgumentCaptor.forClass(ChatMessage.class);
        verify(sender).send(eq("https://x.logic.azure.com/hook-a"), message.capture(), eq("Channel a"));
        verifyNoMoreInteractions(ignoreStubs(sender));
        assertThat(message.getValue().linkUrl()).isEqualTo("https://vmc.example.com/#/environments/env-1");
        assertThat(message.getValue().severity()).isEqualTo(ChatMessage.Severity.CRITICAL);
        ArgumentCaptor<ChatDeliveryLog> row = ArgumentCaptor.forClass(ChatDeliveryLog.class);
        verify(logs).save(row.capture());
        assertThat(row.getValue().getSuccess()).isTrue();
        assertThat(row.getValue().getNotificationType()).isEqualTo("OPERATION_FAILED");
    }

    @Test
    void withoutASubscribedEnvironmentChannelTheDefaultsGetIt() {
        when(channels.findByEnvironmentIdAndIsActiveTrue("env-1")).thenReturn(List.of(channel("b", "env-1", "LEASE_EXPIRING")));
        when(channels.findByEnvironmentIdIsNullAndIsActiveTrue()).thenReturn(List.of(
                channel("g1", null, "OPERATION_FAILED"), channel("g2", null, "COST_ANOMALY")));

        service.notify("env-1", NotificationType.OPERATION_FAILED, ChatMessage.Severity.CRITICAL, "t", "x");
        verify(sender).send(eq("https://x.logic.azure.com/hook-g1"), any(), eq("Channel g1"));

        service.notify("env-1", NotificationType.ACCESS_REQUESTED, ChatMessage.Severity.INFO, "t", "x");
        service.notify(null, NotificationType.COST_ANOMALY, ChatMessage.Severity.WARNING, "t", "x");
        verify(sender).send(eq("https://x.logic.azure.com/hook-g2"), any(), eq("Channel g2"));
        verify(sender, times(2)).send(anyString(), any(), anyString());
    }

    @Test
    void aFailingWebhookIsLoggedAndTheNextChannelStillGetsIt() {
        when(channels.findByEnvironmentIdAndIsActiveTrue("env-1")).thenReturn(List.of(
                channel("a", "env-1", "OPERATION_FAILED"), channel("b", "env-1", "OPERATION_FAILED")));
        doThrow(new ChatDeliveryException("TEAMS Channel a webhook returned HTTP 500"))
                .when(sender).send(eq("https://x.logic.azure.com/hook-a"), any(), any());

        service.notify("env-1", NotificationType.OPERATION_FAILED, ChatMessage.Severity.CRITICAL, "t", "x");

        verify(sender).send(eq("https://x.logic.azure.com/hook-b"), any(), any());
        ArgumentCaptor<ChatDeliveryLog> rows = ArgumentCaptor.forClass(ChatDeliveryLog.class);
        verify(logs, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(ChatDeliveryLog::getSuccess).containsExactly(false, true);
        assertThat(rows.getAllValues().get(0).getError()).isEqualTo("TEAMS Channel a webhook returned HTTP 500");

        // Any other error: only its class name is kept (client errors can quote the URL).
        reset(logs);
        doThrow(new IllegalStateException("https://x.logic.azure.com/hook-a")).when(sender).send(any(), any(), any());
        service.notify("env-1", NotificationType.OPERATION_FAILED, ChatMessage.Severity.CRITICAL, "t", "x");
        verify(logs, times(2)).save(rows.capture());
        assertThat(rows.getValue().getError()).isEqualTo("IllegalStateException");
    }

    @Test
    void aDedupKeyIsSentOncePerChannel() {
        when(channels.findByEnvironmentIdAndIsActiveTrue("env-1")).thenReturn(List.of(channel("a", "env-1", "LEASE_EXPIRING")));
        when(logs.existsByChannelIdAndDedupKeyAndSuccessTrue("a", "LEASE:1")).thenReturn(false, true);

        service.notify("env-1", NotificationType.LEASE_EXPIRING, ChatMessage.Severity.WARNING, "t", "x", "LEASE:1");
        service.notify("env-1", NotificationType.LEASE_EXPIRING, ChatMessage.Severity.WARNING, "t", "x", "LEASE:1");
        verify(sender, times(1)).send(anyString(), any(), anyString());
    }

    @Test
    void offMeansNoLookupAndNoCall() {
        ReflectionTestUtils.setField(service, "enabled", false);
        service.notify("env-1", NotificationType.OPERATION_FAILED, ChatMessage.Severity.CRITICAL, "t", "x");
        verifyNoInteractions(channels, logs, afterCommit);
        verify(sender, never()).send(anyString(), any(), anyString());
        assertThat(service.environmentLink("e")).isEqualTo("https://vmc.example.com/#/environments/e");
        ReflectionTestUtils.setField(service, "publicBaseUrl", "");
        assertThat(service.environmentLink("e")).isNull();
    }
}
