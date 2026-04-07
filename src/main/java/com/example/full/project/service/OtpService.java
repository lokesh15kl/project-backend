package com.example.full.project.service;

import java.util.Random;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class OtpService {

    private final JavaMailSender mailSender;
    private final String smtpUsername;

    public OtpService(ObjectProvider<JavaMailSender> mailSenderProvider, @Value("${spring.mail.username:}") String smtpUsername) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.smtpUsername = smtpUsername;
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
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(smtpUsername);
            message.setTo(toEmail);
            message.setSubject("Your OTP for Career Portal Signup");
            message.setText("Your OTP is: " + otp + "\n\nThis OTP expires in 5 minutes. Do not share this OTP.");
            mailSender.send(message);
        } catch (MailException ex) {
            throw new RuntimeException("Failed to send OTP email. Verify Gmail app password and SMTP settings.", ex);
        }
    }
}
