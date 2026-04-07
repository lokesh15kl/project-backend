package com.example.full.project.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.full.project.entity.ManualQuizQuestion;
import com.example.full.project.entity.QuizResult;
import com.example.full.project.repository.ManualQuizQuestionRepository;
import com.example.full.project.repository.QuizResultRepository;

import jakarta.servlet.http.HttpSession;

@RestController
public class ResultController {

    private static final String ROLE_ADMIN = "ADMIN";

    private final ManualQuizQuestionRepository manualQuizQuestionRepository;
    private final QuizResultRepository quizResultRepository;

    public ResultController(
            ManualQuizQuestionRepository manualQuizQuestionRepository,
            QuizResultRepository quizResultRepository) {
        this.manualQuizQuestionRepository = manualQuizQuestionRepository;
        this.quizResultRepository = quizResultRepository;
    }

    @PostMapping(value = "/api/submitQuiz", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> submitQuiz(
            @RequestParam Map<String, String> form,
            HttpSession session) {
        int score = scoreAndSave(form, session);
        return ResponseEntity.ok(String.valueOf(score));
    }

    @PostMapping(value = "/api/submitQuizWithAudio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> submitQuizWithAudio(
            @RequestParam Map<String, String> form,
            @RequestParam(value = "audioResponses", required = false) MultipartFile[] ignoredAudioFiles,
            HttpSession session) {
        int score = scoreAndSave(form, session);
        return ResponseEntity.ok(String.valueOf(score));
    }

    @GetMapping("/api/submitQuiz")
    public ResponseEntity<?> submitQuizGetInfo() {
        return ResponseEntity.ok(Map.of(
                "message", "Use POST to submit quiz answers.",
                "submitEndpoint", "/api/submitQuiz",
                "method", "POST",
                "contentType", "application/x-www-form-urlencoded",
                "resultView", "/api/results"));
    }

    @GetMapping("/api/submitQuizWithAudio")
    public ResponseEntity<?> submitQuizWithAudioGetInfo() {
        return ResponseEntity.ok(Map.of(
                "message", "Use POST multipart/form-data to submit quiz answers with audio.",
                "submitEndpoint", "/api/submitQuizWithAudio",
                "method", "POST",
                "contentType", "multipart/form-data",
                "resultView", "/api/results"));
    }

    @GetMapping("/api/admin/results")
    public ResponseEntity<?> getAdminResults(HttpSession session) {
        String role = String.valueOf(session.getAttribute("userRole"));
        if (!ROLE_ADMIN.equalsIgnoreCase(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only admin can view results"));
        }

        return ResponseEntity.ok(toScoreRows(quizResultRepository.findAllByOrderByAttemptedAtDesc()));
    }

    @GetMapping("/api/results")
    public ResponseEntity<?> getResults(HttpSession session) {
        String role = String.valueOf(session.getAttribute("userRole"));
        if (ROLE_ADMIN.equalsIgnoreCase(role)) {
            return ResponseEntity.ok(toScoreRows(quizResultRepository.findAllByOrderByAttemptedAtDesc()));
        }

        String email = normalize((String) session.getAttribute("userEmail"));
        if (email.isBlank()) {
            return ResponseEntity.ok(toScoreRows(quizResultRepository.findAllByOrderByAttemptedAtDesc()));
        }

        return ResponseEntity.ok(toScoreRows(quizResultRepository.findByEmailIgnoreCaseOrderByAttemptedAtDesc(email)));
    }

    private int scoreAndSave(Map<String, String> form, HttpSession session) {
        Map<String, String> answersByQuestionId = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            String key = normalize(entry.getKey());
            if (!key.startsWith("q") || key.length() <= 1) {
                continue;
            }

            String questionId = normalize(key.substring(1));
            if (!questionId.isBlank()) {
                answersByQuestionId.put(questionId, normalize(entry.getValue()));
            }
        }

        if (answersByQuestionId.isEmpty()) {
            return 0;
        }

        List<ManualQuizQuestion> questions = manualQuizQuestionRepository.findAllById(answersByQuestionId.keySet()).stream()
            .sorted(Comparator.comparing(
                ManualQuizQuestion::getId,
                Comparator.nullsFirst(String::compareTo)))
                .toList();

        int totalQuestions = questions.size();
        int score = 0;

        for (ManualQuizQuestion question : questions) {
            String expected = normalize(question.getAnswer());
            String submitted = normalize(answersByQuestionId.get(question.getId()));
            if (!expected.isBlank() && expected.equalsIgnoreCase(submitted)) {
                score += 1;
            }
        }

        String category = normalize(form.get("category"));
        String quizTitle = normalize(form.get("quizTitle"));
        if (quizTitle.isBlank()) {
            quizTitle = normalize(form.get("quizName"));
        }

        if ((category.isBlank() || quizTitle.isBlank()) && !questions.isEmpty()) {
            ManualQuizQuestion first = questions.get(0);
            if (category.isBlank()) {
                category = normalize(first.getCategory());
            }
            if (quizTitle.isBlank()) {
                quizTitle = normalize(first.getQuizTitle());
            }
        }

        QuizResult result = new QuizResult();
        Object sessionUserId = session.getAttribute("userId");
        result.setUserId(sessionUserId == null ? null : String.valueOf(sessionUserId));

        String email = normalize((String) session.getAttribute("userEmail"));
        result.setEmail(email.isBlank() ? "Current user" : email);
        result.setCategory(category.isBlank() ? "Unknown" : category);
        result.setQuizTitle(quizTitle.isBlank() ? "Unknown Quiz" : quizTitle);
        result.setScore(score);
        result.setTotalQuestions(Math.max(totalQuestions, 0));
        result.setAttemptedAt(LocalDateTime.now());
        quizResultRepository.save(result);

        return score;
    }

    private List<Map<String, Object>> toScoreRows(List<QuizResult> results) {
        List<Map<String, Object>> rows = new ArrayList<>();

        for (QuizResult item : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("email", item.getEmail());
            row.put("category", item.getCategory());
            row.put("quizTitle", item.getQuizTitle());
            row.put("score", item.getScore());
            row.put("totalQuestions", item.getTotalQuestions());
            row.put("attemptedAt", item.getAttemptedAt());
            rows.add(row);
        }

        return rows;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
