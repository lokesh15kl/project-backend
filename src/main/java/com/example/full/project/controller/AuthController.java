package com.example.full.project.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.full.project.dto.LoginRequest;
import com.example.full.project.dto.RegisterRequest;
import com.example.full.project.entity.AppUser;
import com.example.full.project.entity.QuizResult;
import com.example.full.project.repository.AppUserRepository;
import com.example.full.project.repository.QuizResultRepository;
import com.example.full.project.service.OtpService;

import jakarta.servlet.http.HttpSession;

@RestController
public class AuthController {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";
    private static final long CAPTCHA_TTL_MILLIS = 5 * 60 * 1000;
    private static final String OTP_SESSION_KEY = "otp";
    private static final String OTP_ISSUED_AT_KEY = "otpIssuedAt";
    private static final String OTP_ATTEMPTS_KEY = "otpAttempts";
    private static final String PENDING_NAME_KEY = "pendingName";
    private static final String PENDING_EMAIL_KEY = "pendingEmail";
    private static final String PENDING_PASSWORD_HASH_KEY = "pendingPasswordHash";

    private final AppUserRepository userRepository;
    private final QuizResultRepository quizResultRepository;
    private final OtpService otpService;
    private final Map<String, CaptchaChallenge> captchaChallenges = new ConcurrentHashMap<>();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final long otpExpiryMillis;
    private final int otpMaxAttempts;

    public AuthController(
            AppUserRepository userRepository,
            QuizResultRepository quizResultRepository,
            OtpService otpService,
            @Value("${app.otp.expiry-seconds:300}") long otpExpirySeconds,
            @Value("${app.otp.max-attempts:5}") int otpMaxAttempts) {
        this.userRepository = userRepository;
        this.quizResultRepository = quizResultRepository;
        this.otpService = otpService;
        this.otpExpiryMillis = Math.max(60, otpExpirySeconds) * 1000;
        this.otpMaxAttempts = Math.max(1, otpMaxAttempts);
    }

    @GetMapping("/api/captcha")
    public Map<String, String> getCaptcha(HttpSession session) {
        purgeExpiredCaptchas();
        String captcha = generateCaptcha();
        String captchaId = UUID.randomUUID().toString();

        session.setAttribute("captcha", captcha);
        captchaChallenges.put(captchaId, new CaptchaChallenge(captcha, Instant.now().toEpochMilli() + CAPTCHA_TTL_MILLIS));

        return Map.of("captcha", captcha, "captchaId", captchaId);
    }

    @PostMapping(value = "/doLogin", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> loginWithCaptcha(
            @RequestParam("email") String email,
            @RequestParam("password") String password,
            @RequestParam("captcha") String captcha,
            @RequestParam(value = "captchaId", required = false) String captchaId,
            HttpSession session) {

        String normalizedEmail = normalize(email).toLowerCase();
        String normalizedPassword = normalize(password);
        String normalizedCaptcha = normalize(captcha).toUpperCase();

        if (!isCaptchaValid(normalizedCaptcha, captchaId, session)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Invalid captcha", "success", false));
        }

        List<AppUser> users = userRepository.findAllByEmailIgnoreCaseOrderByIdAsc(normalizedEmail);
        if (users.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Email not found", "success", false));
        }

        AppUser user = users.stream()
                .filter(candidate -> isPasswordValid(candidate, normalizedPassword))
                .findFirst()
                .orElse(null);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Wrong password", "success", false));
        }

        setSessionUser(session, user);
        return ResponseEntity.ok(Map.of("success", true, "role", user.getRole()));
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        String name = normalize(request.getName());
        String email = normalize(request.getEmail()).toLowerCase();
        String password = normalize(request.getPassword());
        String requestedRole = normalize(request.getRole()).toUpperCase();

        if (name.isBlank() || email.isBlank() || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "name, email and password are required"));
        }

        if (userRepository.existsByEmailIgnoreCase(email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "email already exists"));
        }

        AppUser user = new AppUser();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        if (ROLE_ADMIN.equals(requestedRole) || ROLE_USER.equals(requestedRole)) {
            user.setRole(requestedRole);
        } else {
            user.setRole(resolveNewUserRole(email));
        }
        userRepository.save(user);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", user.getId());
        response.put("name", user.getName());
        response.put("email", user.getEmail());
        response.put("role", user.getRole());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpSession session) {
        String email = normalize(request.getEmail()).toLowerCase();
        String password = normalize(request.getPassword());

        List<AppUser> users = userRepository.findAllByEmailIgnoreCaseOrderByIdAsc(email);
        AppUser user = users.stream()
            .filter(candidate -> isPasswordValid(candidate, password))
            .findFirst()
            .orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "invalid credentials"));
        }

        setSessionUser(session, user);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "login successful");
        response.put("id", user.getId());
        response.put("name", user.getName());
        response.put("email", user.getEmail());
        response.put("role", user.getRole());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/auth/admin-login")
    public ResponseEntity<?> adminLogin(@RequestBody LoginRequest request, HttpSession session) {
        String email = normalize(request.getEmail()).toLowerCase();
        String password = normalize(request.getPassword());
        String configuredAdminEmail = normalize(System.getenv("APP_ADMIN_EMAIL")).toLowerCase();

        List<AppUser> users = userRepository.findAllByEmailIgnoreCaseOrderByIdAsc(email);
        AppUser user = users.stream()
            .filter(candidate -> isPasswordValid(candidate, password))
            .findFirst()
            .orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "invalid credentials"));
        }

        boolean configuredAdmin = !configuredAdminEmail.isBlank() && configuredAdminEmail.equals(email);
        if (!ROLE_ADMIN.equalsIgnoreCase(normalize(user.getRole())) && !configuredAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Admin access only"));
        }

        if (configuredAdmin && !ROLE_ADMIN.equalsIgnoreCase(normalize(user.getRole()))) {
            user.setRole(ROLE_ADMIN);
            userRepository.save(user);
        }

        setSessionUser(session, user);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "admin login successful");
        response.put("id", user.getId());
        response.put("name", user.getName());
        response.put("email", user.getEmail());
        response.put("role", user.getRole());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<?> me(HttpSession session) {
        String userId = normalize((String) session.getAttribute("userId"));
        if (userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not authenticated"));
        }

        Optional<AppUser> user = userRepository.findById(userId);
        if (user.isEmpty()) {
            session.invalidate();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Session expired"));
        }

        AppUser current = user.get();
        return ResponseEntity.ok(Map.of(
                "id", current.getId(),
                "name", current.getName(),
                "email", current.getEmail(),
                "role", current.getRole(),
                "profileImageUrl", normalize(current.getProfileImageUrl())));
    }

    @PostMapping("/api/auth/profile")
    public ResponseEntity<?> updateProfile(
            @RequestBody(required = false) Map<String, String> payload,
            HttpSession session) {

        String userId = normalize((String) session.getAttribute("userId"));
        if (userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not authenticated"));
        }

        AppUser user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            session.invalidate();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Session expired"));
        }

        String name = normalize(payload == null ? null : payload.get("name"));
        String profileImageUrl = normalize(payload == null ? null : payload.get("profileImageUrl"));

        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Name is required"));
        }

        if (!profileImageUrl.isBlank() && profileImageUrl.length() > 2_000_000) {
            return ResponseEntity.badRequest().body(Map.of("message", "Profile image is too large"));
        }

        user.setName(name);
        user.setProfileImageUrl(profileImageUrl);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of(
                "message", "Profile updated",
                "id", user.getId(),
                "name", user.getName(),
                "email", user.getEmail(),
                "role", user.getRole(),
                "profileImageUrl", normalize(user.getProfileImageUrl())));
    }

    @PostMapping("/api/auth/change-password")
    public ResponseEntity<?> changePassword(
            @RequestBody(required = false) Map<String, String> payload,
            HttpSession session) {

        String userId = normalize((String) session.getAttribute("userId"));
        if (userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not authenticated"));
        }

        AppUser user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            session.invalidate();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Session expired"));
        }

        String currentPassword = normalize(payload == null ? null : payload.get("currentPassword"));
        String newPassword = normalize(payload == null ? null : payload.get("newPassword"));

        if (currentPassword.isBlank() || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Current password and new password are required"));
        }

        if (newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("message", "New password must be at least 6 characters"));
        }

        if (!isPasswordValid(user, currentPassword)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Current password is incorrect"));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "logout successful"));
    }

    @PostMapping(value = {"/sendOtp", "/api/auth/sendOtp"}, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> sendOtp(
            @RequestParam("name") String name,
            @RequestParam("email") String email,
            @RequestParam("password") String password,
            HttpSession session) {

        return sendOtpInternal(name, email, password, session);
    }

    @PostMapping(value = {"/sendOtp", "/api/auth/sendOtp"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> sendOtpJson(
            @RequestBody(required = false) Map<String, String> payload,
            HttpSession session) {

        String name = payload == null ? null : payload.get("name");
        String email = payload == null ? null : payload.get("email");
        String password = payload == null ? null : payload.get("password");

        return sendOtpInternal(name, email, password, session);
    }

    private ResponseEntity<?> sendOtpInternal(
            String name,
            String email,
            String password,
            HttpSession session) {

        String normalizedName = normalize(name);
        String normalizedEmail = normalize(email).toLowerCase();
        String normalizedPassword = normalize(password);

        if (normalizedName.isBlank() || normalizedEmail.isBlank() || normalizedPassword.isBlank()) {
            return ResponseEntity.badRequest().body("name, email and password are required");
        }

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Email already registered");
        }

        String otp = otpService.generateOtp();
        try {
            otpService.sendOtp(normalizedEmail, otp);
        } catch (RuntimeException ex) {
            Throwable rootCause = rootCause(ex);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "message", rootCause == null || rootCause.getMessage() == null ? ex.getMessage() : rootCause.getMessage(),
                    "errorType", rootCause == null ? ex.getClass().getSimpleName() : rootCause.getClass().getSimpleName(),
                    "details", ex.getMessage(),
                    "success", false,
                    "otpBypassed", false));
        }

        session.setAttribute(OTP_SESSION_KEY, otp);
        session.setAttribute(OTP_ISSUED_AT_KEY, Instant.now().toEpochMilli());
        session.setAttribute(OTP_ATTEMPTS_KEY, 0);
        session.setAttribute(PENDING_NAME_KEY, normalizedName);
        session.setAttribute(PENDING_EMAIL_KEY, normalizedEmail);
        session.setAttribute(PENDING_PASSWORD_HASH_KEY, passwordEncoder.encode(normalizedPassword));

        return ResponseEntity.ok(Map.of("message", "OTP sent successfully", "otpBypassed", false));
    }

    @PostMapping(value = {"/verifyOtp", "/api/auth/verifyOtp"}, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> verifyOtp(
            @RequestParam("otp") String otp,
            HttpSession session) {

        return verifyOtpInternal(otp, session);
    }

    @PostMapping(value = {"/verifyOtp", "/api/auth/verifyOtp"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> verifyOtpJson(
            @RequestBody(required = false) Map<String, String> payload,
            HttpSession session) {

        String otp = payload == null ? null : payload.get("otp");
        return verifyOtpInternal(otp, session);
    }

    @PostMapping(value = {"/resendOtp", "/api/auth/resendOtp"}, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> resendOtp(HttpSession session) {
        return resendOtpInternal(session);
    }

    @PostMapping(value = {"/resendOtp", "/api/auth/resendOtp"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resendOtpJson(HttpSession session) {
        return resendOtpInternal(session);
    }

    private ResponseEntity<?> resendOtpInternal(HttpSession session) {
        String pendingName = normalize((String) session.getAttribute(PENDING_NAME_KEY));
        String pendingEmail = normalize((String) session.getAttribute(PENDING_EMAIL_KEY)).toLowerCase();
        String pendingPasswordHash = normalize((String) session.getAttribute(PENDING_PASSWORD_HASH_KEY));

        if (pendingName.isBlank() || pendingEmail.isBlank() || pendingPasswordHash.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "message", "No pending signup found. Please start signup again.",
                    "success", false));
        }

        if (userRepository.existsByEmailIgnoreCase(pendingEmail)) {
            clearOtpSession(session);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "Email already registered",
                    "success", false));
        }

        String otp = otpService.generateOtp();
        try {
            otpService.sendOtp(pendingEmail, otp);
        } catch (RuntimeException ex) {
            Throwable rootCause = rootCause(ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "message", rootCause == null || rootCause.getMessage() == null ? ex.getMessage() : rootCause.getMessage(),
                    "errorType", rootCause == null ? ex.getClass().getSimpleName() : rootCause.getClass().getSimpleName(),
                    "details", ex.getMessage(),
                    "success", false));
        }

        session.setAttribute(OTP_SESSION_KEY, otp);
        session.setAttribute(OTP_ISSUED_AT_KEY, Instant.now().toEpochMilli());
        session.setAttribute(OTP_ATTEMPTS_KEY, 0);

        return ResponseEntity.ok(Map.of(
                "message", "OTP resent successfully",
                "success", true,
                "otpBypassed", false));
    }

    private ResponseEntity<?> verifyOtpInternal(String otp, HttpSession session) {
        String enteredOtp = normalize(otp);
        String savedOtp = (String) session.getAttribute(OTP_SESSION_KEY);

        if (savedOtp == null) {
            clearOtpSession(session);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("OTP session expired. Please send OTP again.");
        }

        Long issuedAt = (Long) session.getAttribute(OTP_ISSUED_AT_KEY);
        if (issuedAt == null || (Instant.now().toEpochMilli() - issuedAt) > otpExpiryMillis) {
            clearOtpSession(session);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("OTP expired. Please send OTP again.");
        }

        Integer attempts = (Integer) session.getAttribute(OTP_ATTEMPTS_KEY);
        int currentAttempts = attempts == null ? 0 : attempts;
        if (currentAttempts >= otpMaxAttempts) {
            clearOtpSession(session);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Maximum OTP attempts reached. Please request a new OTP.");
        }

        if (!savedOtp.equals(enteredOtp)) {
            int updatedAttempts = currentAttempts + 1;
            session.setAttribute(OTP_ATTEMPTS_KEY, updatedAttempts);
            int remaining = Math.max(0, otpMaxAttempts - updatedAttempts);

            if (updatedAttempts >= otpMaxAttempts) {
                clearOtpSession(session);
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Maximum OTP attempts reached. Please request a new OTP.");
            }

            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid OTP. Remaining attempts: " + remaining);
        }

        String pendingName = (String) session.getAttribute(PENDING_NAME_KEY);
        String pendingEmail = (String) session.getAttribute(PENDING_EMAIL_KEY);
        String pendingPasswordHash = (String) session.getAttribute(PENDING_PASSWORD_HASH_KEY);

        if (normalize(pendingName).isBlank() || normalize(pendingEmail).isBlank() || normalize(pendingPasswordHash).isBlank()) {
            clearOtpSession(session);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Signup session expired. Please send OTP again.");
        }

        if (userRepository.existsByEmailIgnoreCase(pendingEmail)) {
            clearOtpSession(session);
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Email already registered");
        }

        AppUser user = new AppUser();
        user.setName(pendingName);
        user.setEmail(pendingEmail);
        user.setPassword(pendingPasswordHash);
        user.setRole(resolveNewUserRole(pendingEmail));
        userRepository.save(user);

        setSessionUser(session, user);

        clearOtpSession(session);

        return ResponseEntity.ok(Map.of("message", "Signup successful", "role", user.getRole()));
    }

    @PostMapping("/api/admin/users/promote")
    public ResponseEntity<?> promoteUser(
            @RequestBody(required = false) Map<String, String> body,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "username", required = false) String username,
            HttpSession session) {

        String role = String.valueOf(session.getAttribute("userRole"));
        if (!ROLE_ADMIN.equalsIgnoreCase(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only admin can promote users"));
        }

        String candidate = normalize(email);
        if (candidate.isBlank()) {
            candidate = normalize(username);
        }
        if (candidate.isBlank() && body != null) {
            candidate = normalize(body.get("email"));
        }
        if (candidate.isBlank() && body != null) {
            candidate = normalize(body.get("username"));
        }

        if (candidate.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Email or username is required"));
        }

        final String lookup = candidate;
        AppUser target = userRepository.findFirstByEmailIgnoreCaseOrderByIdAsc(lookup)
            .or(() -> userRepository.findFirstByNameIgnoreCaseOrderByIdAsc(lookup))
                .orElse(null);

        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found"));
        }

        target.setRole(ROLE_ADMIN);
        userRepository.save(target);
        return ResponseEntity.ok(Map.of("message", "User promoted to admin", "email", target.getEmail(), "success", true));
    }

    @PostMapping("/api/admin/make-admin")
    public ResponseEntity<?> promoteUserAlias(
            @RequestBody(required = false) Map<String, String> body,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "username", required = false) String username,
            HttpSession session) {
        return promoteUser(body, email, username, session);
    }

    @GetMapping("/api/admin/scores")
    public ResponseEntity<?> getAdminScores(HttpSession session) {
        String role = String.valueOf(session.getAttribute("userRole"));
        if (!ROLE_ADMIN.equalsIgnoreCase(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only admin can view scores"));
        }

        List<QuizResult> results = quizResultRepository.findAllByOrderByAttemptedAtDesc();
        List<Map<String, Object>> rows = results.stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("email", item.getEmail());
            row.put("category", item.getCategory());
            row.put("quizTitle", item.getQuizTitle());
            row.put("score", item.getScore());
            row.put("totalQuestions", item.getTotalQuestions());
            row.put("attemptedAt", item.getAttemptedAt());
            return row;
        }).toList();

        return ResponseEntity.ok(rows);
    }

    @GetMapping("/api/admin/analytics")
    public ResponseEntity<?> getAdminAnalytics(HttpSession session) {
        String role = String.valueOf(session.getAttribute("userRole"));
        if (!ROLE_ADMIN.equalsIgnoreCase(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only admin can access analytics"));
        }

        List<QuizResult> results = quizResultRepository.findAllByOrderByAttemptedAtDesc();
        long totalUsers = userRepository.count();
        long totalAttempts = results.size();
        double averageScore = results.stream()
                .mapToDouble(item -> {
                    Integer score = item.getScore();
                    return score == null ? 0.0 : score.doubleValue();
                })
                .average()
                .orElse(0.0);

        Map<String, Long> attemptsByCategory = new LinkedHashMap<>();
        Map<String, Double> scoreSumByCategory = new LinkedHashMap<>();
        Map<String, Long> scoreCountByCategory = new LinkedHashMap<>();
        Map<String, Long> topUsersByAttempts = new LinkedHashMap<>();

        for (QuizResult item : results) {
            String category = normalize(item.getCategory());
            String email = normalize(item.getEmail());
            if (category.isBlank()) {
                category = "Unknown";
            }
            if (email.isBlank()) {
                email = "Unknown user";
            }

            attemptsByCategory.merge(category, 1L, Long::sum);
            scoreSumByCategory.merge(category, item.getScore() == null ? 0.0 : item.getScore().doubleValue(), Double::sum);
            scoreCountByCategory.merge(category, 1L, Long::sum);
            topUsersByAttempts.merge(email, 1L, Long::sum);
        }

        Map<String, Double> averageByCategory = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : scoreSumByCategory.entrySet()) {
            long count = scoreCountByCategory.getOrDefault(entry.getKey(), 1L);
            averageByCategory.put(entry.getKey(), entry.getValue() / Math.max(1L, count));
        }

        Map<String, Long> sortedTopUsers = topUsersByAttempts.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(10)
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalUsers", totalUsers);
        response.put("totalAttempts", totalAttempts);
        response.put("averageScore", averageScore);
        response.put("attemptsByCategory", attemptsByCategory);
        response.put("averageByCategory", averageByCategory);
        response.put("topUsersByAttempts", sortedTopUsers);
        return ResponseEntity.ok(response);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private boolean isPasswordValid(AppUser user, String rawPassword) {
        String stored = user.getPassword();
        if (stored == null || stored.isBlank()) {
            return false;
        }

        boolean bcryptFormat = stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$");
        if (bcryptFormat) {
            return passwordEncoder.matches(rawPassword, stored);
        }

        // Backward compatibility for existing plain-text rows.
        if (stored.equals(rawPassword)) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            userRepository.save(user);
            return true;
        }

        return false;
    }

    private void setSessionUser(HttpSession session, AppUser user) {
        session.setAttribute("userId", user.getId());
        session.setAttribute("userEmail", user.getEmail());
        session.setAttribute("userRole", user.getRole());
    }

    private void clearOtpSession(HttpSession session) {
        session.removeAttribute(OTP_SESSION_KEY);
        session.removeAttribute(OTP_ISSUED_AT_KEY);
        session.removeAttribute(OTP_ATTEMPTS_KEY);
        session.removeAttribute(PENDING_NAME_KEY);
        session.removeAttribute(PENDING_EMAIL_KEY);
        session.removeAttribute(PENDING_PASSWORD_HASH_KEY);
    }

    private String resolveNewUserRole(String email) {
        String adminEmail = normalize(System.getenv("APP_ADMIN_EMAIL")).toLowerCase();
        if (!adminEmail.isBlank() && adminEmail.equals(email)) {
            return ROLE_ADMIN;
        }

        return userRepository.count() == 0 ? ROLE_ADMIN : ROLE_USER;
    }

    private boolean isCaptchaValid(String userCaptcha, String captchaId, HttpSession session) {
        purgeExpiredCaptchas();

        if (captchaId != null && !captchaId.isBlank()) {
            CaptchaChallenge challenge = captchaChallenges.remove(captchaId);
            if (challenge != null && challenge.expiresAt >= Instant.now().toEpochMilli()) {
                return challenge.captcha.equalsIgnoreCase(userCaptcha);
            }
        }

        String sessionCaptcha = (String) session.getAttribute("captcha");
        return sessionCaptcha != null && sessionCaptcha.equalsIgnoreCase(userCaptcha);
    }

    private void purgeExpiredCaptchas() {
        long now = Instant.now().toEpochMilli();
        captchaChallenges.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    private String generateCaptcha() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder captcha = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 5; i++) {
            captcha.append(chars.charAt(random.nextInt(chars.length())));
        }
        return captcha.toString();
    }

    private static class CaptchaChallenge {
        private final String captcha;
        private final long expiresAt;

        private CaptchaChallenge(String captcha, long expiresAt) {
            this.captcha = captcha;
            this.expiresAt = expiresAt;
        }
    }
}
