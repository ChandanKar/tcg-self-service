package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.EmailLog;
import com.tcgdigital.vmcontrol.repository.EmailLogRepository;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private EmailLogRepository emailLogRepository;

    private EmailService service;

    @BeforeEach
    void setUp() {
        service = new EmailService(mailSender, emailLogRepository);
    }

    @Test
    void sendHtml_doesNothingWhenDisabled() throws InterruptedException {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.sendHtml(List.of("user@example.com"), "Subject", "<p>body</p>", null, null);
        awaitAsync();

        verify(mailSender, never()).createMimeMessage();
        verify(mailSender, never()).send(any(MimeMessage.class));
        // A suppressed send was never attempted — nothing should land in the email log.
        verify(emailLogRepository, never()).save(any());
    }

    @Test
    void sendHtml_sendsWhenEnabled() throws InterruptedException {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@tcgdigital.com");
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        service.sendHtml(List.of("user@example.com"), "Subject", "<p>body</p>", null, null);
        awaitAsync();

        verify(mailSender).send(mimeMessage);

        ArgumentCaptor<EmailLog> captor = ArgumentCaptor.forClass(EmailLog.class);
        verify(emailLogRepository).save(captor.capture());
        EmailLog logged = captor.getValue();
        assertTrue(logged.isSuccess());
        assertEquals("Subject", logged.getSubject());
        assertEquals(1, logged.getRecipientCount());
        assertEquals("user@example.com", logged.getRecipients());
    }

    @Test
    void sendHtml_withContext_recordsTypeEnvironmentAndActor() throws InterruptedException {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@tcgdigital.com");
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        service.sendHtml(List.of("user@example.com"), "Subject", "<p>body</p>", null, null,
                "STOP_ENVIRONMENT_BROADCAST", "env-1", "admin-1");
        awaitAsync();

        ArgumentCaptor<EmailLog> captor = ArgumentCaptor.forClass(EmailLog.class);
        verify(emailLogRepository).save(captor.capture());
        EmailLog logged = captor.getValue();
        assertEquals("STOP_ENVIRONMENT_BROADCAST", logged.getNotificationType());
        assertEquals("env-1", logged.getEnvironmentId());
        assertEquals("admin-1", logged.getSentByUserId());
    }

    @Test
    void sendHtml_swallowsSendExceptionRatherThanPropagating() throws InterruptedException {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@tcgdigital.com");
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("SMTP connection refused")).when(mailSender).send(any(MimeMessage.class));

        assertDoesNotThrow(() -> {
            service.sendHtml(List.of("user@example.com"), "Subject", "<p>body</p>", null, null);
            awaitAsync();
        });

        // A failed send is still a real attempt — it should land in the log as a failure, not
        // be silently dropped.
        ArgumentCaptor<EmailLog> captor = ArgumentCaptor.forClass(EmailLog.class);
        verify(emailLogRepository).save(captor.capture());
        assertFalse(captor.getValue().isSuccess());
        assertEquals("SMTP connection refused", captor.getValue().getErrorMessage());
    }

    @Test
    void sendHtml_skipsSendWithNoRecipients() throws InterruptedException {
        ReflectionTestUtils.setField(service, "enabled", true);

        service.sendHtml(List.of(), "Subject", "<p>body</p>", null, null);
        awaitAsync();

        verify(mailSender, never()).createMimeMessage();
        verify(emailLogRepository, never()).save(any());
    }

    /**
     * {@link EmailService#sendHtml} is {@code @Async} — since this test constructs the service
     * directly (no Spring context, so no proxy/executor involved), the call actually runs
     * synchronously on the calling thread. This sleep is a no-op safety margin, not a real
     * async wait, kept only so the test still passes if that assumption ever changes.
     */
    private void awaitAsync() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(10);
    }
}
