package com.tcgdigital.vmcontrol.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin wrapper around {@link JavaMailSender}. A failed send is logged, never propagated —
 * the bell notification this accompanies has already succeeded independently, and a broken
 * SMTP config must not break the caller's transaction.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Value("${notification.email.enabled:false}")
    private boolean enabled;

    @Value("${notification.email.from:noreply@tcgdigital.com}")
    private String fromAddress;

    private final JavaMailSender mailSender;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Sends an HTML email, optionally with a single attachment. No-op (logged at debug) when
     * the master {@code notification.email.enabled} flag is off — callers are expected to have
     * already checked their own per-type flag before calling this.
     */
    @Async
    public void sendHtml(List<String> toAddresses, String subject, String htmlBody,
                         String attachmentName, byte[] attachmentBytes) {
        if (!enabled) {
            log.debug("Email suppressed (notification.email.enabled=false): {}", subject);
            return;
        }
        if (toAddresses == null || toAddresses.isEmpty()) {
            log.warn("Skipping email send with no recipients: {}", subject);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message,
                    attachmentBytes != null, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toAddresses.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            if (attachmentBytes != null && attachmentName != null) {
                helper.addAttachment(attachmentName, new org.springframework.core.io.ByteArrayResource(attachmentBytes));
            }
            mailSender.send(message);
            log.info("Email sent to {} recipient(s): {}", toAddresses.size(), subject);
        } catch (Exception e) {
            log.error("Failed to send email '{}' to {}: {}", subject, toAddresses, e.getMessage(), e);
        }
    }
}
