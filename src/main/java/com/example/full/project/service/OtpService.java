package com.example.full.project.service;

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
    private final String smtpUsername;
    private final String smtpFrom;

    public OtpService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${spring.mail.username:}") String smtpUsername,
            @Value("${app.mail.from:}") String smtpFrom) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.smtpUsername = smtpUsername == null ? "" : smtpUsername.trim();
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
            MimeMessage message = mailSender.createMimeMessage();
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
            mailSender.send(message);
        } catch (MailException | MessagingException ex) {
            log.error("Failed to send OTP email to {} using SMTP user {}", toEmail, smtpUsername, ex);
            throw new RuntimeException("Failed to send OTP email. Verify Gmail app password and SMTP settings.", ex);
        }
    }
}
