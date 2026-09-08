package com.tcgdigital.vmcontrol.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailService service;

    @BeforeEach
    void setUp() {
        service = new EmailService(mailSender);
    }

    @Test
    void sendHtml_doesNothingWhenDisabled() throws InterruptedException {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.sendHtml(List.of("user@example.com"), "Subject", "<p>body</p>", null, null);
        awaitAsync();

        verify(mailSender, never()).createMimeMessage();
        verify(mailSender, never()).send(any(MimeMessage.class));
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
    }

    @Test
    void sendHtml_skipsSendWithNoRecipients() throws InterruptedException {
        ReflectionTestUtils.setField(service, "enabled", true);

        service.sendHtml(List.of(), "Subject", "<p>body</p>", null, null);
        awaitAsync();

        verify(mailSender, never()).createMimeMessage();
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
