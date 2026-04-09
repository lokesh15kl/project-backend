package com.example.full.project.service;

import java.util.Properties;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private final JavaMailSender mailSender;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUsername;
    private final String smtpPassword;
    private final String smtpFrom;

    public OtpService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${spring.mail.host:smtp.gmail.com}") String smtpHost,
            @Value("${spring.mail.port:587}") int smtpPort,
            @Value("${spring.mail.username:}") String smtpUsername,
            @Value("${spring.mail.password:}") String smtpPassword,
            @Value("${app.mail.from:}") String smtpFrom) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.smtpHost = smtpHost == null ? "smtp.gmail.com" : smtpHost.trim();
        this.smtpPort = smtpPort;
        this.smtpUsername = smtpUsername == null ? "" : smtpUsername.trim();
        this.smtpPassword = smtpPassword == null ? "" : smtpPassword.trim();
        this.smtpFrom = (smtpFrom == null || smtpFrom.isBlank()) ? this.smtpUsername : smtpFrom.trim();
    }

    public String generateOtp() {
        Random random = new Random();
        return String.valueOf(100000 + random.nextInt(900000));
    }

    public void sendOtp(String toEmail, String otp) {
        if (mailSender == null) {
            throw new IllegalStateException("SMTP mail sender is unavailable. Ensure spring-boot-starter-mail is present and SMTP env vars are set.");
        }

        if (smtpUsername == null || smtpUsername.isBlank()) {
            throw new IllegalStateException("SMTP is not configured. Set SMTP_USERNAME and SMTP_PASSWORD for Gmail OTP delivery.");
        }

        try {
            String recipient = toEmail == null ? "" : toEmail.trim().toLowerCase();
            sendWithSender(mailSender, recipient, otp);
        } catch (MailException | MessagingException ex) {
            if (looksLikeConnectTimeout(ex) && smtpPort != 465) {
                try {
                    sendWithSender(buildFallbackSender(465, false), toEmail == null ? "" : toEmail.trim().toLowerCase(), otp);
                    return;
                } catch (MailException | MessagingException fallbackEx) {
                    log.error("Fallback SMTP send also failed for {}", toEmail, fallbackEx);
                    throw new RuntimeException("Failed to send OTP email. Gmail connection timed out on both ports 587 and 465.", fallbackEx);
                }
            }

            if (looksLikeConnectTimeout(ex) && smtpPort != 587) {
                try {
                    sendWithSender(buildFallbackSender(587, true), toEmail == null ? "" : toEmail.trim().toLowerCase(), otp);
                    return;
                } catch (MailException | MessagingException fallbackEx) {
                    log.error("Fallback SMTP send also failed for {}", toEmail, fallbackEx);
                    throw new RuntimeException("Failed to send OTP email. Gmail connection timed out on both ports 465 and 587.", fallbackEx);
                }
            }

            log.error("Failed to send OTP email to {} using SMTP user {}", toEmail, smtpUsername, ex);
            throw new RuntimeException("Failed to send OTP email. Verify Gmail app password and SMTP settings.", ex);
        }
    }

    private void sendWithSender(JavaMailSender sender, String recipient, String otp) throws MailException, MessagingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(smtpFrom);
        helper.setTo(recipient);
        helper.setSubject("Your OTP for Career Portal Signup");
        helper.setText(
                "<div style='font-family:Arial,sans-serif;line-height:1.6'>" +
                        "<h2>Your OTP is: <span style='color:#ff6b6b'>" + otp + "</span></h2>" +
                        "<p>This OTP expires in 5 minutes. Do not share this OTP.</p>" +
                        "</div>",
                true);
        sender.send(message);
    }

    private JavaMailSender buildFallbackSender(int port, boolean startTls) {
        org.springframework.mail.javamail.JavaMailSenderImpl fallback = new org.springframework.mail.javamail.JavaMailSenderImpl();
        fallback.setHost(smtpHost);
        fallback.setPort(port);
        fallback.setUsername(smtpUsername);
        fallback.setPassword(smtpPassword);
        fallback.setDefaultEncoding("UTF-8");

        Properties props = fallback.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.trust", smtpHost);
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        props.put("mail.smtp.starttls.required", String.valueOf(startTls));
        props.put("mail.smtp.ssl.enable", String.valueOf(!startTls));
        return fallback;
    }

    private boolean looksLikeConnectTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("connect timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
