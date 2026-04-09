package com.example.full.project.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final String brevoApiKey;
    private final String brevoSenderEmail;
    private final String brevoSenderName;
    private final HttpClient httpClient;

    public OtpService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${spring.mail.host:smtp.gmail.com}") String smtpHost,
            @Value("${spring.mail.port:587}") int smtpPort,
            @Value("${spring.mail.username:}") String smtpUsername,
            @Value("${spring.mail.password:}") String smtpPassword,
            @Value("${app.mail.from:}") String smtpFrom,
            @Value("${app.mail.brevo.api-key:}") String brevoApiKey,
            @Value("${app.mail.brevo.sender-email:}") String brevoSenderEmail,
            @Value("${app.mail.brevo.sender-name:Career Portal}") String brevoSenderName) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.smtpHost = smtpHost == null ? "smtp.gmail.com" : smtpHost.trim();
        this.smtpPort = smtpPort;
        this.smtpUsername = smtpUsername == null ? "" : smtpUsername.trim();
        this.smtpPassword = smtpPassword == null ? "" : smtpPassword.trim();
        this.smtpFrom = (smtpFrom == null || smtpFrom.isBlank()) ? this.smtpUsername : smtpFrom.trim();
        this.brevoApiKey = brevoApiKey == null ? "" : brevoApiKey.trim();
        this.brevoSenderEmail = brevoSenderEmail == null ? "" : brevoSenderEmail.trim();
        this.brevoSenderName = brevoSenderName == null || brevoSenderName.isBlank() ? "Career Portal" : brevoSenderName.trim();
        this.httpClient = HttpClient.newHttpClient();
    }

    public String generateOtp() {
        Random random = new Random();
        return String.valueOf(100000 + random.nextInt(900000));
    }

    public void sendOtp(String toEmail, String otp) {
        String recipient = toEmail == null ? "" : toEmail.trim().toLowerCase();
        if (recipient.isBlank()) {
            throw new IllegalArgumentException("Recipient email is required");
        }

        if (isBrevoConfigured()) {
            try {
                sendWithBrevo(recipient, otp);
                return;
            } catch (IOException | InterruptedException | RuntimeException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Brevo send failed for {}. Falling back to SMTP.", recipient, ex);
            }
        }

        if (mailSender == null) {
            throw new IllegalStateException("Mail sender unavailable. Configure BREVO_API_KEY or SMTP settings.");
        }

        if (smtpUsername.isBlank()) {
            throw new IllegalStateException("SMTP not configured. Set SMTP_USERNAME and SMTP_PASSWORD.");
        }

        try {
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

    private boolean isBrevoConfigured() {
        return !brevoApiKey.isBlank() && !brevoSenderEmail.isBlank();
    }

    private void sendWithBrevo(String recipient, String otp) throws IOException, InterruptedException {
        String subject = "Your OTP for Career Portal Signup";
        String html = "<div style='font-family:Arial,sans-serif;line-height:1.6'>"
                + "<h2>Your OTP is: <span style='color:#ff6b6b'>" + otp + "</span></h2>"
                + "<p>This OTP expires in 5 minutes. Do not share this OTP.</p>"
                + "</div>";

        String payload = "{"
                + "\"sender\":{\"name\":\"" + jsonEscape(brevoSenderName) + "\",\"email\":\"" + jsonEscape(brevoSenderEmail) + "\"},"
                + "\"to\":[{\"email\":\"" + jsonEscape(recipient) + "\"}],"
                + "\"subject\":\"" + jsonEscape(subject) + "\","
                + "\"htmlContent\":\"" + jsonEscape(html) + "\""
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.brevo.com/v3/smtp/email"))
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .header("api-key", brevoApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("Brevo API send failed with status " + status + ": " + response.body());
        }
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.toString();
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
