package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.model.ChatChannel;
import com.tcgdigital.vmcontrol.model.ChatProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.ChatChannelRepository;
import com.tcgdigital.vmcontrol.repository.ChatDeliveryLogRepository;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import com.tcgdigital.vmcontrol.service.NotificationService;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An operation failure reaches the environment's Teams channel only after commit (E24-T03); a
 * rollback sends nothing, and a dead webhook leaves the bell notifications alone.
 */
class ChatDispatchIntegrationTest extends AbstractIntegrationTest {

    private static final String HOOK = "https://prod-1.westeurope.logic.azure.com/workflows/int";

    @Autowired private ChatDispatchService dispatch;
    @Autowired private NotificationService notificationService;
    @Autowired private ChatChannelRepository channels;
    @Autowired private ChatDeliveryLogRepository logs;
    @Autowired private NotificationRepository notifications;
    @Autowired private SecretCipher cipher;
    @Autowired private PlatformTransactionManager transactionManager;

    private Object target;
    private Object realSenders;
    private ChatSender sender;
    private Environment env;

    @BeforeEach
    void wire() {
        target = AopTestUtils.getTargetObject(dispatch);
        realSenders = ReflectionTestUtils.getField(target, "senders");
        sender = Mockito.mock(ChatSender.class);
        when(sender.provider()).thenReturn(ChatProvider.TEAMS);
        ReflectionTestUtils.setField(target, "senders", new ChatSenderRegistry(List.of(sender)));
        ReflectionTestUtils.setField(target, "enabled", true);

        env = newEnvironment("Chat ops");
        ChatChannel c = new ChatChannel();
        c.setChannelId(UUID.randomUUID().toString());
        c.setEnvironmentId(env.getEnvironmentId());
        c.setProvider(ChatProvider.TEAMS);
        c.setLabel("Ops");
        c.setWebhookUrlEnc(cipher.encrypt(HOOK));
        c.setWebhookHost("prod-1.westeurope.logic.azure.com");
        c.setEventTypes("OPERATION_FAILED");
        channels.saveAndFlush(c);
    }

    @AfterEach
    void unwire() {
        ReflectionTestUtils.setField(target, "senders", realSenders);
        ReflectionTestUtils.setField(target, "enabled", false);
    }

    private void failInTransaction(boolean rollback) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            notificationService.notifyOperationFailedForEnvironment(env.getEnvironmentId(), "Chat ops", null, "STOP", "boom");
            if (rollback) {
                status.setRollbackOnly();
            }
        });
    }

    @Test
    void sentAfterCommitWithTheReason() {
        failInTransaction(false);
        ArgumentCaptor<ChatMessage> message = ArgumentCaptor.forClass(ChatMessage.class);
        verify(sender, timeout(5000)).send(eq(HOOK), message.capture(), eq("Ops"));
        assertThat(message.getValue().text()).contains("Reason: boom");
        awaitAsync(() -> assertThat(logs.findAll()).singleElement().satisfies(l -> assertThat(l.getSuccess()).isTrue()));
    }

    @Test
    void aRollbackSendsNothing() throws InterruptedException {
        failInTransaction(true);
        Thread.sleep(500);
        verify(sender, never()).send(anyString(), any(), anyString());
        assertThat(logs.count()).isZero();
    }

    @Test
    void aDeadWebhookLeavesTheBellAlone() {
        doThrow(new ChatDeliveryException("TEAMS Ops webhook returned HTTP 503")).when(sender).send(any(), any(), any());
        long before = notifications.count();
        newUser("member@chat.test", true, false); // an admin receives the environment broadcast
        failInTransaction(false);
        awaitAsync(() -> assertThat(logs.findAll()).singleElement().satisfies(l -> {
            assertThat(l.getSuccess()).isFalse();
            assertThat(l.getError()).isEqualTo("TEAMS Ops webhook returned HTTP 503");
        }));
        assertThat(notifications.count()).isGreaterThan(before);
    }
}
