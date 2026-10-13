package com.tcgdigital.vmcontrol.service.webhook;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.WebhookDelivery;
import com.tcgdigital.vmcontrol.model.WebhookSubscription;
import com.tcgdigital.vmcontrol.repository.WebhookDeliveryRepository;
import com.tcgdigital.vmcontrol.repository.WebhookSubscriptionRepository;
import com.tcgdigital.vmcontrol.service.chat.SecretCipher;
import com.tcgdigital.vmcontrol.service.events.PlatformEventType;
import com.tcgdigital.vmcontrol.service.events.PlatformEvents;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Platform events become webhook deliveries only after commit, and only for matching subscriptions (E34-T07). */
class WebhookEventListenerTest extends AbstractIntegrationTest {

    @Autowired private PlatformEvents platformEvents;
    @Autowired private WebhookService webhookService;
    @Autowired private WebhookSubscriptionRepository subscriptions;
    @Autowired private WebhookDeliveryRepository deliveries;
    @Autowired private SecretCipher cipher;
    @Autowired private PlatformTransactionManager transactionManager;

    private Environment env;
    private Environment other;

    private void webhooks(boolean on) {
        Object target = AopTestUtils.getTargetObject(webhookService);
        ReflectionTestUtils.setField(target, "enabled", on);
    }

    @BeforeEach
    void setUp() {
        webhooks(true);
        env = newEnvironment("Hooked");
        other = newEnvironment("Other");
    }

    @AfterEach
    void off() {
        webhooks(false);
    }

    private WebhookSubscription subscribe(String environmentId, String types) {
        WebhookSubscription s = new WebhookSubscription();
        s.setWebhookId(UUID.randomUUID().toString());
        s.setLabel("hook");
        s.setUrl("https://93.184.216.34/hook");
        s.setSecretEnc(cipher.encrypt("whsec_test"));
        s.setEventTypes(types);
        s.setEnvironmentId(environmentId);
        s.setCreatedBy("u-1");
        return subscriptions.saveAndFlush(s);
    }

    private void inTransaction(Runnable work, boolean rollback) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            work.run();
            if (rollback) status.setRollbackOnly();
        });
    }

    @Test
    void aCommittedEventIsQueuedForTheEnvironmentsAndGlobalSubscriptionsThatWantIt() {
        WebhookSubscription mine = subscribe(env.getEnvironmentId(), "environment.stopped");
        WebhookSubscription global = subscribe(null, "environment.stopped,operation.failed");
        subscribe(other.getEnvironmentId(), "environment.stopped");
        subscribe(env.getEnvironmentId(), "access.granted");

        inTransaction(() -> platformEvents.publish(PlatformEventType.ENVIRONMENT_STOPPED, env.getEnvironmentId(), env.getName(),
                Map.of("operationId", "op-9")), false);

        List<WebhookDelivery> queued = deliveries.findAll();
        assertThat(queued).extracting(WebhookDelivery::getWebhookId)
                .containsExactlyInAnyOrder(mine.getWebhookId(), global.getWebhookId());
        assertThat(queued).allSatisfy(d -> {
            assertThat(d.getStatus()).isEqualTo(WebhookDelivery.Status.PENDING);
            assertThat(d.getPayload()).contains(env.getEnvironmentId()).contains("op-9");
        });
    }

    @Test
    void aRolledBackActionQueuesNothing() {
        subscribe(env.getEnvironmentId(), "environment.stopped");

        inTransaction(() -> platformEvents.publish(PlatformEventType.ENVIRONMENT_STOPPED, env.getEnvironmentId(), env.getName(),
                Map.of()), true);

        assertThat(deliveries.findAll()).isEmpty();
    }

    @Test
    void withWebhooksOffNothingIsQueued() {
        subscribe(env.getEnvironmentId(), "environment.stopped");
        webhooks(false);

        platformEvents.publish(PlatformEventType.ENVIRONMENT_STOPPED, env.getEnvironmentId(), env.getName(), Map.of());

        assertThat(deliveries.findAll()).isEmpty();
    }
}
