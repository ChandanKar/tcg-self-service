package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.EmailLog;
import com.tcgdigital.vmcontrol.repository.EmailLogRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

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
    private final EmailLogRepository emailLogRepository;

    public EmailService(JavaMailSender mailSender, EmailLogRepository emailLogRepository) {
        this.mailSender = mailSender;
        this.emailLogRepository = emailLogRepository;
    }

    /**
     * Sends an HTML email, optionally with a single attachment. No-op (logged at debug) when
     * the master {@code notification.email.enabled} flag is off — callers are expected to have
     * already checked their own per-type flag before calling this.
     */
    @Async("notificationExecutor")
    public void sendHtml(List<String> toAddresses, String subject, String htmlBody,
                         String attachmentName, byte[] attachmentBytes) {
        sendHtml(toAddresses, subject, htmlBody, attachmentName, attachmentBytes, null, null, null);
    }

    /**
     * Same as {@link #sendHtml(List, String, String, String, byte[])}, plus context recorded
     * in the {@code email_log} table (the admin-facing "who have we emailed" view) for this
     * send: what triggered it, which environment it's about, and who (if anyone) initiated it
     * directly. Pass null for any that don't apply.
     */
    @Async("notificationExecutor")
    public void sendHtml(List<String> toAddresses, String subject, String htmlBody,
                         String attachmentName, byte[] attachmentBytes,
                         String notificationType, String environmentId, String sentByUserId) {
        if (!enabled) {
            log.debug("Email suppressed (notification.email.enabled=false): {}", subject);
            return;
        }
        if (toAddresses == null || toAddresses.isEmpty()) {
            log.warn("Skipping email send with no recipients: {}", subject);
            return;
        }

        boolean success;
        String errorMessage = null;
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
            success = true;
        } catch (Exception e) {
            log.error("Failed to send email '{}' to {}: {}", subject, toAddresses, e.getMessage(), e);
            success = false;
            errorMessage = e.getMessage();
        }

        try {
            EmailLog entry = new EmailLog();
            entry.setLogId(UUID.randomUUID().toString());
            entry.setNotificationType(notificationType);
            entry.setEnvironmentId(environmentId);
            entry.setRecipients(String.join(", ", toAddresses));
            entry.setRecipientCount(toAddresses.size());
            entry.setSubject(subject);
            entry.setSuccess(success);
            entry.setErrorMessage(errorMessage);
            entry.setSentByUserId(sentByUserId);
            emailLogRepository.save(entry);
        } catch (Exception e) {
            // The email itself already succeeded or failed above; a logging failure must not
            // look like a send failure to the caller (this method returns void either way).
            log.error("Failed to record email_log entry for '{}': {}", subject, e.getMessage(), e);
        }
    }
}
