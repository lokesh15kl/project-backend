package com.example.full.project.controller;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.full.project.entity.AdminQuiz;
import com.example.full.project.entity.AssessmentCategory;
import com.example.full.project.entity.ManualQuizQuestion;
import com.example.full.project.repository.AdminQuizRepository;
import com.example.full.project.repository.AssessmentCategoryRepository;
import com.example.full.project.repository.ManualQuizQuestionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;

@RestController
public class AdminQuizController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private record AiTemplate(String prompt, String correct, String distractor1, String distractor2, String distractor3) {
    }

    private final AssessmentCategoryRepository assessmentCategoryRepository;
    private final AdminQuizRepository adminQuizRepository;
    private final ManualQuizQuestionRepository manualQuizQuestionRepository;

    @Value("${huggingface.api.key:}")
    private String huggingfaceApiKey;

    public AdminQuizController(
            AssessmentCategoryRepository assessmentCategoryRepository,
            AdminQuizRepository adminQuizRepository,
            ManualQuizQuestionRepository manualQuizQuestionRepository) {
        this.assessmentCategoryRepository = assessmentCategoryRepository;
        this.adminQuizRepository = adminQuizRepository;
        this.manualQuizQuestionRepository = manualQuizQuestionRepository;
    }

    @GetMapping("/api/admin/categories")
    public List<String> getAdminCategories() {
        return assessmentCategoryRepository.findAll().stream()
                .map(AssessmentCategory::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @GetMapping("/api/categories")
    public List<Map<String, Object>> getUserCategories() {
        return assessmentCategoryRepository.findAll().stream()
                .sorted(Comparator.comparing(
                        AssessmentCategory::getName,
                        String.CASE_INSENSITIVE_ORDER))
                .map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", item.getId());
                    row.put("name", item.getName());
                    return row;
                })
                .toList();
    }

    @GetMapping("/api/admin/assessments/categories")
    public List<String> getAdminAssessmentCategoriesAlias() {
        return getAdminCategories();
    }

    @PostMapping("/api/admin/categories")
    public ResponseEntity<?> addAdminCategory(@RequestBody(required = false) Map<String, String> payload) {
        String name = normalize(payload == null ? null : payload.get("name"));
        String note = normalize(payload == null ? null : payload.get("note"));

        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Category name is required"));
        }

        if (assessmentCategoryRepository.existsByNameIgnoreCase(name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Category already exists"));
        }

        AssessmentCategory category = new AssessmentCategory();
        category.setName(name);
        category.setNote(note.isBlank() ? "Assessment category" : note);
        assessmentCategoryRepository.save(category);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", category.getId(), "name", category.getName(), "note", category.getNote()));
    }

    @DeleteMapping("/api/admin/categories/{name}")
    public ResponseEntity<?> deleteAdminCategory(@PathVariable("name") String name) {
        String normalized = normalize(name);
        if (normalized.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Category name is required"));
        }

        if (!assessmentCategoryRepository.existsByNameIgnoreCase(normalized)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Category not found"));
        }

        manualQuizQuestionRepository.deleteByCategoryIgnoreCase(normalized);
        adminQuizRepository.deleteByCategoryIgnoreCase(normalized);
        assessmentCategoryRepository.deleteByNameIgnoreCase(normalized);

        return ResponseEntity.ok(Map.of("message", "Category deleted"));
    }

    @PostMapping("/api/admin/quizzes")
    public ResponseEntity<?> createAdminQuiz(@RequestBody(required = false) Map<String, String> payload) {
        String category = normalize(payload == null ? null : firstOf(payload, "category", "assessment"));
        String quizTitle = normalize(payload == null ? null : firstOf(payload, "quizTitle", "quizName"));

        if (category.isBlank() || quizTitle.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Category and quiz title are required"));
        }

        ensureCategoryExists(category);

        if (adminQuizRepository.existsByCategoryIgnoreCaseAndQuizTitleIgnoreCase(category, quizTitle)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Quiz already exists"));
        }

        AdminQuiz quiz = new AdminQuiz();
        quiz.setCategory(category);
        quiz.setQuizTitle(quizTitle);
        adminQuizRepository.save(quiz);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", quiz.getId(), "category", quiz.getCategory(), "quizTitle", quiz.getQuizTitle()));
    }

    @GetMapping("/api/admin/quizzes/overview")
    public List<Map<String, Object>> getQuizOverview() {
        Map<String, Integer> questionCountsByKey = manualQuizQuestionRepository.findAll().stream()
                .collect(Collectors.toMap(
                        item -> toQuizKey(item.getCategory(), item.getQuizTitle()),
                        item -> 1,
                        Integer::sum));

        List<AdminQuiz> quizzes = adminQuizRepository.findAll().stream()
                .sorted(Comparator
                        .comparing(AdminQuiz::getCategory, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AdminQuiz::getQuizTitle, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AdminQuiz quiz : quizzes) {
            String key = toQuizKey(quiz.getCategory(), quiz.getQuizTitle());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", quiz.getId());
            row.put("category", quiz.getCategory());
            row.put("quizTitle", quiz.getQuizTitle());
            row.put("questionCount", questionCountsByKey.getOrDefault(key, 0));
            row.put("createdAt", quiz.getCreatedAt());
            rows.add(row);
        }

        return rows;
    }

    @GetMapping("/api/quizzes/overview")
    public List<Map<String, Object>> getQuizOverviewAlias() {
        return getQuizOverview();
    }

    @PostMapping(value = "/generate-quiz", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> generateQuizAlias(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "quizTitle", required = false) String quizTitle,
            @RequestParam(value = "quizName", required = false) String quizName) {

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("category", normalize(category));
        payload.put("quizTitle", normalize(quizTitle));
        payload.put("quizName", normalize(quizName));
        return createAdminQuiz(payload);
    }

    @GetMapping("/api/ai/status")
    public ResponseEntity<?> aiStatus() {
        boolean configured = isHuggingFaceConfigured();
        return ResponseEntity.ok(Map.of(
            "available", configured,
            "message", configured
                ? "AI generation is available for admin assignments and user practice."
                : "AI generation key is missing. Add HUGGINGFACE_API_KEY in backend/.env.local and restart backend.",
                "features", List.of("admin-assessment-generation", "user-practice-generation", "career-insights")));
    }

    @PostMapping("/api/ai/career-insights")
    public ResponseEntity<?> generateCareerInsights(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpSession session) {

        if (!isLoggedIn(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Login required"));
        }

        String career = normalize(readFirst(payload, "career", "role"));
        if (career.isBlank()) {
            career = normalize(readFirst(payload, "query", "topic"));
        }

        if (career.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Career search query is required"));
        }

        String categoryHint = normalize(readFirst(payload, "category", "track"));

        Map<String, Object> aiInsight = generateCareerInsightWithLlm(career, categoryHint);
        if (!aiInsight.isEmpty()) {
            return ResponseEntity.ok(aiInsight);
        }

        return ResponseEntity.ok(buildCareerInsightFallback(career, categoryHint));
    }

    @PostMapping("/api/admin/ai/generate-assessment")
    public ResponseEntity<?> generateAssessmentWithAi(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpSession session) {

        if (!isAdmin(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Admin access required"));
        }

        String category = normalize(readFirst(payload, "category", "assessment"));
        String quizTitle = normalize(readFirst(payload, "quizTitle", "quizName"));
        String topic = normalize(readFirst(payload, "topic", "focus"));
        String difficulty = normalize(readFirst(payload, "difficulty", "level"));

        int questionCount = clamp(readInt(payload, "questionCount", 8), 3, 20);
        boolean replaceExisting = readBoolean(payload, "replaceExisting", true);

        if (category.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Category is required"));
        }
        if (quizTitle.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Quiz title is required"));
        }

        String resolvedTopic = topic.isBlank() ? category : topic;
        String resolvedDifficulty = difficulty.isBlank() ? "medium" : difficulty.toLowerCase();

        ensureCategoryExists(category);
        ensureQuizExists(category, quizTitle);

        Set<String> previousQuestionSet = manualQuizQuestionRepository
            .findByCategoryIgnoreCaseAndQuizTitleIgnoreCaseOrderByIdAsc(category, quizTitle)
            .stream()
            .map(ManualQuizQuestion::getQuestion)
            .map(this::normalizeQuestionKey)
            .filter(key -> !key.isBlank())
            .collect(Collectors.toSet());

        if (replaceExisting) {
            manualQuizQuestionRepository.deleteByCategoryIgnoreCaseAndQuizTitleIgnoreCase(category, quizTitle);
        }

        List<ManualQuizQuestion> savedQuestions = persistGeneratedQuestions(
                category,
                quizTitle,
                resolvedTopic,
                resolvedDifficulty,
            questionCount,
            previousQuestionSet);

        return ResponseEntity.ok(Map.of(
                "message", "AI assessment generated and published for users.",
                "category", category,
                "quizTitle", quizTitle,
                "topic", resolvedTopic,
                "difficulty", resolvedDifficulty,
                "questionCount", savedQuestions.size(),
                "questions", savedQuestions.stream().map(this::toQuestionResponse).collect(Collectors.toList())));
    }

    @PostMapping("/api/ai/generate-practice")
    public ResponseEntity<?> generatePracticeWithAi(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpSession session) {

        if (!isLoggedIn(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Login required"));
        }

        String category = normalize(readFirst(payload, "category", "assessment"));
        String topic = normalize(readFirst(payload, "topic", "focus"));
        String difficulty = normalize(readFirst(payload, "difficulty", "level"));
        String requestedTitle = normalize(readFirst(payload, "quizTitle", "quizName"));

        if (topic.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Topic is required"));
        }

        if (!isHuggingFaceConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "message", "AI key is missing. Add HUGGINGFACE_API_KEY in backend/.env.local and restart backend."));
        }

        int questionCount = clamp(readInt(payload, "questionCount", 6), 3, 20);
        String resolvedTopic = topic;
        String resolvedCategory = category.isBlank() ? resolvedTopic : category;
        String resolvedDifficulty = difficulty.isBlank() ? "medium" : difficulty.toLowerCase();
        String quizTitle = requestedTitle.isBlank()
                ? buildPracticeQuizTitle(resolvedTopic)
                : requestedTitle;

        ensureCategoryExists(resolvedCategory);
        String finalQuizTitle = quizTitle;
        manualQuizQuestionRepository.deleteByCategoryIgnoreCaseAndQuizTitleIgnoreCase(
            resolvedCategory,
            finalQuizTitle);
        ensureQuizExists(resolvedCategory, finalQuizTitle);

        List<ManualQuizQuestion> savedQuestions = persistGeneratedQuestions(
            resolvedCategory,
            finalQuizTitle,
                resolvedTopic,
                resolvedDifficulty,
                questionCount,
                Set.of());

        return ResponseEntity.ok(Map.of(
                "message", "AI practice quiz generated.",
            "category", resolvedCategory,
                "quizTitle", finalQuizTitle,
                "topic", resolvedTopic,
                "difficulty", resolvedDifficulty,
                "questionCount", savedQuestions.size(),
                "questions", savedQuestions.stream().map(this::toQuestionResponse).collect(Collectors.toList())));
    }

    @PostMapping(value = "/admin/saveManualQuiz", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> saveManualQuiz(
            @RequestParam("category") String category,
            @RequestParam(value = "quizTitle", required = false) String quizTitle,
            @RequestParam(value = "quizName", required = false) String quizName,
            @RequestParam("question") String question,
            @RequestParam(value = "option1", required = false) String option1,
            @RequestParam(value = "option2", required = false) String option2,
            @RequestParam(value = "option3", required = false) String option3,
            @RequestParam(value = "option4", required = false) String option4,
            @RequestParam("answer") String answer,
            @RequestParam(value = "questionType", required = false) String questionType) {

        String normalizedCategory = normalize(category);
        String normalizedQuizTitle = normalize(quizTitle);
        if (normalizedQuizTitle.isBlank()) {
            normalizedQuizTitle = normalize(quizName);
        }

        String normalizedQuestion = normalize(question);
        String normalizedQuestionType = normalize(questionType).toLowerCase();
        String normalizedAnswer = normalize(answer);

        if (normalizedQuestionType.isBlank()) {
            normalizedQuestionType = "mcq";
        }

        if (normalizedCategory.isBlank() || normalizedQuizTitle.isBlank() || normalizedQuestion.isBlank() || normalizedAnswer.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Category, quiz, question and answer are required"));
        }

        ensureCategoryExists(normalizedCategory);
        ensureQuizExists(normalizedCategory, normalizedQuizTitle);

        ManualQuizQuestion item = new ManualQuizQuestion();
        item.setCategory(normalizedCategory);
        item.setQuizTitle(normalizedQuizTitle);
        item.setQuestion(normalizedQuestion);
        item.setQuestionType(normalizedQuestionType);
        item.setOption1(normalizeOrPlaceholder(option1));
        item.setOption2(normalizeOrPlaceholder(option2));
        item.setOption3(normalizeOrPlaceholder(option3));
        item.setOption4(normalizeOrPlaceholder(option4));
        item.setAnswer(normalizedAnswer);
        manualQuizQuestionRepository.save(item);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", item.getId(), "message", "Question saved"));
    }

    @GetMapping("/api/admin/questions")
    public List<Map<String, Object>> getAdminQuestions(
            @RequestParam("category") String category,
            @RequestParam("quizName") String quizName) {
        return getQuestions(category, quizName);
    }

    @GetMapping("/admin/manualQuiz/questions")
    public List<Map<String, Object>> getAdminQuestionsAlias(
            @RequestParam("category") String category,
            @RequestParam("quizName") String quizName) {
        return getQuestions(category, quizName);
    }

    @GetMapping("/api/admin/manualQuiz/questions")
    public List<Map<String, Object>> getAdminQuestionsAliasApi(
            @RequestParam("category") String category,
            @RequestParam("quizName") String quizName) {
        return getQuestions(category, quizName);
    }

    @GetMapping("/api/admin/questions/all")
    public List<Map<String, Object>> getAllAdminQuestions() {
        List<ManualQuizQuestion> questions = manualQuizQuestionRepository.findAll().stream()
            .sorted(Comparator.comparing(
                ManualQuizQuestion::getId,
                Comparator.nullsFirst(String::compareTo)))
                .toList();

        List<Map<String, Object>> response = new ArrayList<>();
        for (ManualQuizQuestion item : questions) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", item.getId());
            mapped.put("category", item.getCategory());
            mapped.put("quizName", item.getQuizTitle());
            mapped.put("questionText", item.getQuestion());
            mapped.put("questionType", item.getQuestionType());
            mapped.put("options", List.of(
                    normalizeOption(item.getOption1()),
                    normalizeOption(item.getOption2()),
                    normalizeOption(item.getOption3()),
                    normalizeOption(item.getOption4())));
            mapped.put("correctAnswer", item.getAnswer());
            mapped.put("createdAt", item.getCreatedAt());
            response.add(mapped);
        }

        return response;
    }

    @GetMapping("/api/quizList")
    public List<String> getQuizList(@RequestParam("category") String category) {
        String requestedCategory = normalizeCategoryKey(category);

        return adminQuizRepository.findAll().stream()
            .filter(item -> normalizeCategoryKey(item.getCategory()).equals(requestedCategory))
                .map(AdminQuiz::getQuizTitle)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @GetMapping("/api/attemptQuiz")
    public List<Map<String, Object>> attemptQuiz(
            @RequestParam("category") String category,
            @RequestParam("quizTitle") String quizTitle) {
        String normalizedCategory = normalizeCategoryKey(category);
        String normalizedQuizTitle = normalize(quizTitle);

        List<ManualQuizQuestion> questions = manualQuizQuestionRepository.findAll().stream()
            .filter(item -> normalizeCategoryKey(item.getCategory()).equals(normalizedCategory))
            .filter(item -> normalize(item.getQuizTitle()).equalsIgnoreCase(normalizedQuizTitle))
            .sorted(Comparator.comparing(
                ManualQuizQuestion::getId,
                Comparator.nullsFirst(String::compareTo)))
            .toList();

        List<Map<String, Object>> response = new ArrayList<>();
        for (ManualQuizQuestion item : questions) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", item.getId());
            mapped.put("questionType", item.getQuestionType());
            mapped.put("question", item.getQuestion());
            mapped.put("option1", normalizeOption(item.getOption1()));
            mapped.put("option2", normalizeOption(item.getOption2()));
            mapped.put("option3", normalizeOption(item.getOption3()));
            mapped.put("option4", normalizeOption(item.getOption4()));
            mapped.put("correctAnswer", item.getAnswer());
            response.add(mapped);
        }

        return response;
    }

    private List<Map<String, Object>> getQuestions(String category, String quizName) {
        String normalizedCategory = normalize(category);
        String normalizedQuizName = normalize(quizName);

        List<ManualQuizQuestion> questions = manualQuizQuestionRepository
                .findByCategoryIgnoreCaseAndQuizTitleIgnoreCaseOrderByIdAsc(normalizedCategory, normalizedQuizName);

        List<Map<String, Object>> response = new ArrayList<>();
        for (ManualQuizQuestion item : questions) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", item.getId());
            mapped.put("category", item.getCategory());
            mapped.put("quizName", item.getQuizTitle());
            mapped.put("questionText", item.getQuestion());
            mapped.put("questionType", item.getQuestionType());
            mapped.put("options", List.of(
                    normalizeOption(item.getOption1()),
                    normalizeOption(item.getOption2()),
                    normalizeOption(item.getOption3()),
                    normalizeOption(item.getOption4())));
            mapped.put("correctAnswer", item.getAnswer());
            mapped.put("createdAt", item.getCreatedAt());
            response.add(mapped);
        }

        return response;
    }

    private void ensureCategoryExists(String category) {
        if (assessmentCategoryRepository.existsByNameIgnoreCase(category)) {
            return;
        }

        AssessmentCategory item = new AssessmentCategory();
        item.setName(category);
        item.setNote("Assessment category");
        assessmentCategoryRepository.save(item);
    }

    private void ensureQuizExists(String category, String quizTitle) {
        if (adminQuizRepository.existsByCategoryIgnoreCaseAndQuizTitleIgnoreCase(category, quizTitle)) {
            return;
        }

        AdminQuiz quiz = new AdminQuiz();
        quiz.setCategory(category);
        quiz.setQuizTitle(quizTitle);
        adminQuizRepository.save(quiz);
    }

    private List<ManualQuizQuestion> persistGeneratedQuestions(
            String category,
            String quizTitle,
            String topic,
            String difficulty,
            int questionCount,
            Set<String> excludedQuestionKeys) {

        List<AiTemplate> templates = templatesForCategory(category, topic, difficulty, questionCount);
        long seed = System.nanoTime()
            ^ Objects.hash(normalize(category), normalize(quizTitle), normalize(topic), normalize(difficulty));
        Random random = new Random(seed);
        List<AiTemplate> shuffledTemplates = new ArrayList<>(templates);
        Collections.shuffle(shuffledTemplates, random);

        Set<String> usedQuestionKeys = new HashSet<>();
        if (excludedQuestionKeys != null && !excludedQuestionKeys.isEmpty()) {
            usedQuestionKeys.addAll(excludedQuestionKeys);
        }

        List<ManualQuizQuestion> saved = new ArrayList<>();
        int attempts = 0;
        int index = 0;
        int maxAttempts = Math.max(questionCount * 30, 60);

        while (saved.size() < questionCount && attempts < maxAttempts) {
            AiTemplate template = shuffledTemplates.get(index % shuffledTemplates.size());
            String prompt = buildPromptVariant(template.prompt(), topic, random, index);

            List<String> options = new ArrayList<>(List.of(
                    template.correct(),
                    template.distractor1(),
                    template.distractor2(),
                    template.distractor3()));
            Collections.shuffle(options, random);

            String questionText = "[" + difficulty.toUpperCase() + "] " + prompt;
            String questionKey = normalizeQuestionKey(questionText);
            attempts++;
            index++;

            if (questionKey.isBlank() || usedQuestionKeys.contains(questionKey)) {
                continue;
            }

            ManualQuizQuestion item = new ManualQuizQuestion();
            item.setCategory(category);
            item.setQuizTitle(quizTitle);
            item.setQuestionType("mcq");
            item.setQuestion(questionText);
            item.setOption1(options.get(0));
            item.setOption2(options.get(1));
            item.setOption3(options.get(2));
            item.setOption4(options.get(3));
            item.setAnswer(template.correct());
            saved.add(manualQuizQuestionRepository.save(item));
            usedQuestionKeys.add(questionKey);

            if (index % shuffledTemplates.size() == 0) {
                Collections.shuffle(shuffledTemplates, random);
            }
        }

        // Fallback path to guarantee requested count while keeping questions distinct.
        while (saved.size() < questionCount) {
            int fallbackIndex = saved.size();
            AiTemplate template = shuffledTemplates.get(fallbackIndex % shuffledTemplates.size());
            String prompt = buildPromptVariant(template.prompt(), topic, random, fallbackIndex)
                    + " Focus area " + (fallbackIndex + 1) + ".";

            List<String> options = new ArrayList<>(List.of(
                    template.correct(),
                    template.distractor1(),
                    template.distractor2(),
                    template.distractor3()));
            Collections.shuffle(options, random);

            String questionText = "[" + difficulty.toUpperCase() + "] " + prompt;
            String questionKey = normalizeQuestionKey(questionText);
            if (questionKey.isBlank() || usedQuestionKeys.contains(questionKey)) {
                questionText = questionText + " Scenario " + Long.toHexString(System.nanoTime()) + ".";
                questionKey = normalizeQuestionKey(questionText);
            }

            ManualQuizQuestion item = new ManualQuizQuestion();
            item.setCategory(category);
            item.setQuizTitle(quizTitle);
            item.setQuestionType("mcq");
            item.setQuestion(questionText);
            item.setOption1(options.get(0));
            item.setOption2(options.get(1));
            item.setOption3(options.get(2));
            item.setOption4(options.get(3));
            item.setAnswer(template.correct());
            saved.add(manualQuizQuestionRepository.save(item));
            usedQuestionKeys.add(questionKey);
        }

        return saved;
    }

    private String buildPromptVariant(String basePrompt, String topic, Random random, int index) {
        String normalizedBasePrompt = normalize(basePrompt);
        String normalizedTopic = normalize(topic);
        if (normalizedBasePrompt.isBlank()) {
            return "Choose the most appropriate response.";
        }

        String[] intros = {
                "Scenario:",
                "In practice,",
                "Consider this case:",
                "In a real-world situation,",
            "From an assessment perspective,",
            "In the workplace,",
            "When under pressure,"
        };

        String[] closers = {
                "",
                "Select the strongest option.",
                "Pick the best response.",
                "Choose the most effective answer.",
            "Identify the most suitable choice.",
            "Choose the option that would work best in practice.",
            "Select the response with the highest long-term impact."
        };

        String[] lenses = {
            "Think about team outcomes.",
            "Prioritize practical impact.",
            "Focus on decision quality.",
            "Consider professional standards.",
            "Balance speed with correctness.",
            "Use evidence-based judgment."
        };

        String intro = intros[random.nextInt(intros.length)];
        String closer = closers[random.nextInt(closers.length)];
        String lens = lenses[random.nextInt(lenses.length)];

        StringBuilder prompt = new StringBuilder();
        prompt.append(intro).append(" ").append(normalizedBasePrompt);

        if (!normalizedTopic.isBlank() && random.nextBoolean()) {
            prompt.append(" [Topic: ").append(normalizedTopic).append("]");
        }

        if (index > 0 && random.nextBoolean()) {
            prompt.append(" (Set ").append(index + 1).append(")");
        }

        if (random.nextBoolean()) {
            prompt.append(" ").append(lens);
        }

        if (!closer.isBlank()) {
            prompt.append(" ").append(closer);
        }

        return prompt.toString();
    }

    private String normalizeQuestionKey(String question) {
        return normalize(question).toLowerCase();
    }

    private Map<String, Object> toQuestionResponse(ManualQuizQuestion item) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", item.getId());
        response.put("category", item.getCategory());
        response.put("quizName", item.getQuizTitle());
        response.put("question", item.getQuestion());
        response.put("questionType", item.getQuestionType());
        response.put("option1", normalizeOption(item.getOption1()));
        response.put("option2", normalizeOption(item.getOption2()));
        response.put("option3", normalizeOption(item.getOption3()));
        response.put("option4", normalizeOption(item.getOption4()));
        response.put("correctAnswer", item.getAnswer());
        return response;
    }

    private List<AiTemplate> templatesForCategory(String category, String topic, String difficulty, int questionCount) {
        String normalizedCategory = normalize(category).toLowerCase();
        String normalizedTopic = normalize(topic);

        if (!normalizedTopic.isBlank()) {
            List<AiTemplate> aiGenerated = generateTopicTemplatesWithLlm(normalizedTopic, difficulty, questionCount);
            if (!aiGenerated.isEmpty()) {
                return aiGenerated;
            }
            return templatesForTopic(normalizedTopic);
        }

        Map<String, List<AiTemplate>> catalog = new HashMap<>();
        catalog.put("technical assessment", List.of(
                new AiTemplate("What improves API reliability the most in distributed systems?", "Idempotent operations", "More UI animations", "Longer variable names", "Bigger font sizes"),
                new AiTemplate("Which data structure gives average O(1) key lookup?", "Hash map", "Linked list", "Stack", "Queue"),
                new AiTemplate("What is the best first step before optimizing slow code?", "Measure bottlenecks with profiling", "Rewrite everything", "Change all libraries", "Disable logging forever"),
                new AiTemplate("Which practice most reduces production incidents?", "Automated tests in CI", "Manual deploy notes only", "Bigger meeting calendars", "Skipping code reviews"),
                new AiTemplate("What is most important in secure password storage?", "One-way salted hashing", "Plain text for easy recovery", "Base64 encoding", "Excel sheet backup")));

        catalog.put("aptitude assessment", List.of(
                new AiTemplate("If 5 workers finish a task in 12 days, how many days for 10 workers at same rate?", "6 days", "10 days", "12 days", "24 days"),
                new AiTemplate("Which number comes next: 3, 6, 12, 24, ?", "48", "30", "42", "54"),
                new AiTemplate("A price increases by 20% then decreases by 20%. Net effect?", "4% decrease", "No change", "4% increase", "20% decrease"),
                new AiTemplate("If ratio A:B is 2:3 and B:C is 4:5, then A:C is", "8:15", "2:5", "3:5", "5:8"),
                new AiTemplate("Average of 10 and 20 is", "15", "10", "20", "30")));

        catalog.put("logical assessment", List.of(
                new AiTemplate("All coders are learners. Some learners are mentors. What follows?", "Some mentors may be coders", "All mentors are coders", "No coder is mentor", "All learners are coders"),
                new AiTemplate("Odd one out: Triangle, Square, Circle, Cube", "Cube", "Triangle", "Square", "Circle"),
                new AiTemplate("If LIGHT is coded as 54231 and NIGHT as 84231, code for THING is", "31482", "84213", "32148", "41382"),
                new AiTemplate("Find next pattern: AB, DE, GH, ?", "JK", "IJ", "KL", "LM"),
                new AiTemplate("If every statement is true, which must be true?", "A valid conclusion follows the premises", "Any random option is true", "Contradiction is guaranteed", "Evidence is unnecessary")));

        catalog.put("personality assessment", List.of(
                new AiTemplate("In a team conflict, the most constructive first action is", "Listen to both sides before deciding", "Escalate immediately", "Ignore the issue", "Blame one person publicly"),
                new AiTemplate("When priorities change suddenly, an effective response is", "Clarify impact and re-plan tasks", "Refuse all changes", "Hide delays", "Skip communication"),
                new AiTemplate("Strong professional communication usually means", "Clear, concise, and respectful", "Long and vague", "Only written messages", "Never asking questions"),
                new AiTemplate("A growth mindset is best shown by", "Learning from feedback", "Avoiding challenges", "Defending every mistake", "Comparing constantly"),
                new AiTemplate("Time management improves most when you", "Prioritize high-impact tasks first", "Multitask all day", "Keep no schedule", "Wait for urgency")));

        catalog.put("career interest assessment", List.of(
                new AiTemplate("Who usually enjoys a product role most?", "People who like user problems and prioritization", "People who avoid teamwork", "People who dislike decisions", "People who avoid feedback"),
                new AiTemplate("Which activity aligns with data careers?", "Analyzing trends and communicating insights", "Only drawing UI icons", "Ignoring metrics", "Avoiding experimentation"),
                new AiTemplate("A good sign for engineering interest is", "Enjoying debugging and system thinking", "Avoiding problem solving", "Disliking iteration", "Preferring no ownership"),
                new AiTemplate("People-focused careers often require", "Empathy and communication", "Zero collaboration", "No listening", "Avoiding stakeholders"),
                new AiTemplate("Best first step to explore a new career path", "Small projects plus mentor feedback", "Waiting without practice", "Skipping research", "Ignoring required skills")));

        List<AiTemplate> selected = catalog.entrySet().stream()
                .filter(entry -> normalizedCategory.contains(entry.getKey().replace(" assessment", ""))
                        || normalizedCategory.equals(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> new ArrayList<>(catalog.values().stream()
                        .flatMap(List::stream)
                        .filter(Objects::nonNull)
                        .limit(5)
                        .toList()));

        return selected;
    }

    private List<AiTemplate> generateTopicTemplatesWithLlm(String topic, String difficulty, int questionCount) {
        String hfApiKey = normalize(System.getenv("HUGGINGFACE_API_KEY"));
        if (hfApiKey.isBlank()) {
            return List.of();
        }

        String endpoint = normalize(System.getenv("HUGGINGFACE_TEXT_MODEL_URL"));
        if (endpoint.isBlank()) {
            endpoint = "https://api-inference.huggingface.co/models/mistralai/Mistral-7B-Instruct-v0.2";
        }

        int targetCount = clamp(questionCount, 3, 20);
        String normalizedDifficulty = normalize(difficulty);
        if (normalizedDifficulty.isBlank()) {
            normalizedDifficulty = "medium";
        }

        String prompt = "Generate " + targetCount + " MCQ questions strictly about topic: \"" + topic + "\". "
                + "Difficulty: " + normalizedDifficulty + ". "
                + "Return ONLY valid JSON array, no markdown and no extra text. "
                + "Each item must have keys: question, correctAnswer, distractor1, distractor2, distractor3. "
            + "Every question must clearly reference the topic and be domain-specific. "
            + "Questions must test concrete concepts, syntax, architecture, workflows, tools, or troubleshooting within the topic. "
            + "Do NOT generate generic assessment, motivation, or study-advice questions. "
            + "Distractors must be plausible within the same topic but still incorrect.";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inputs", prompt);
        payload.put("parameters", Map.of(
                "max_new_tokens", Math.min(1800, 220 * targetCount),
                "temperature", 0.4,
                "return_full_text", false
        ));

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(12))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + hfApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }

            String rawBody = normalize(response.body());
            if (rawBody.isBlank()) {
                return List.of();
            }

            JsonNode root = OBJECT_MAPPER.readTree(rawBody);
            String generatedText;
            if (root.isArray() && root.size() > 0) {
                JsonNode first = root.get(0);
                generatedText = normalize(first.path("generated_text").asText(""));
            } else {
                generatedText = normalize(root.path("generated_text").asText(""));
            }

            if (generatedText.isBlank()) {
                return List.of();
            }

            return parseGeneratedTemplates(generatedText, topic);
        } catch (IOException | InterruptedException ex) {
            return List.of();
        }
    }

    private boolean isHuggingFaceConfigured() {
        return huggingfaceApiKey != null && !huggingfaceApiKey.isBlank();
    }

    private List<AiTemplate> parseGeneratedTemplates(String generatedText, String topic) {
        String cleaned = normalize(generatedText)
                .replace("```json", "")
                .replace("```", "")
                .trim();

        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }

        String jsonArray = cleaned.substring(start, end + 1);

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(jsonArray);
            if (!arrayNode.isArray()) {
                return List.of();
            }

            List<AiTemplate> templates = new ArrayList<>();
            for (JsonNode node : arrayNode) {
                String question = normalize(node.path("question").asText(""));
                String correct = normalize(node.path("correctAnswer").asText(""));
                String d1 = normalize(node.path("distractor1").asText(""));
                String d2 = normalize(node.path("distractor2").asText(""));
                String d3 = normalize(node.path("distractor3").asText(""));

                if (question.isBlank() || correct.isBlank() || d1.isBlank() || d2.isBlank() || d3.isBlank()) {
                    continue;
                }

                String lowerQuestion = question.toLowerCase();
                String lowerTopic = normalize(topic).toLowerCase();
                if (!lowerTopic.isBlank() && !lowerQuestion.contains(lowerTopic)) {
                    question = question + " (" + topic + ")";
                }

                String combined = String.join(" ", question, correct, d1, d2, d3);
                if (!isTopicRelevantText(combined, topic)) {
                    continue;
                }

                templates.add(new AiTemplate(question, correct, d1, d2, d3));
            }

            return templates;
        } catch (IOException ex) {
            return List.of();
        }
    }

    private List<AiTemplate> templatesForTopic(String topic) {
        String normalizedTopic = normalize(topic).toLowerCase();

        if (normalizedTopic.contains("sql") || normalizedTopic.contains("database") || normalizedTopic.contains("postgres")
                || normalizedTopic.contains("mysql")) {
            return List.of(
                    new AiTemplate("In SQL, which clause is used to filter rows before grouping?", "WHERE", "HAVING", "ORDER BY", "LIMIT"),
                    new AiTemplate("In SQL, which statement is used to combine rows from two tables based on related columns?", "JOIN", "UNION", "GROUP BY", "DISTINCT"),
                    new AiTemplate("Which SQL aggregate function returns the number of rows?", "COUNT", "SUM", "AVG", "MAX"),
                    new AiTemplate("Which SQL command removes all rows from a table but keeps the table structure?", "TRUNCATE", "DROP", "DELETE with WHERE", "ALTER"),
                    new AiTemplate("In SQL, which keyword is used to sort query results?", "ORDER BY", "GROUP BY", "HAVING", "WHERE"),
                    new AiTemplate("Which JOIN returns all rows from the left table and matched rows from the right table?", "LEFT JOIN", "INNER JOIN", "RIGHT JOIN", "CROSS JOIN"),
                    new AiTemplate("What does the SQL GROUP BY clause do?", "Groups rows that share values for aggregation", "Sorts rows alphabetically", "Filters duplicate tables", "Renames columns"),
                    new AiTemplate("Which SQL operator is used for pattern matching with wildcards?", "LIKE", "IN", "BETWEEN", "EXISTS"),
                    new AiTemplate("In SQL, which condition checks for missing values?", "IS NULL", "= NULL", "NULLIF", "NOT EMPTY"),
                    new AiTemplate("Which SQL command is used to modify existing records in a table?", "UPDATE", "ALTER", "INSERT", "MERGE"));
        }

        String label = normalize(topic);
        return List.of(
                new AiTemplate("In " + label + ", which option best identifies a core concept required for correct problem solving?", "The option that applies a topic-specific concept with correct context", "The option with generic wording but no " + label + " concept", "The option that ignores " + label + " constraints", "The option based on unrelated domain assumptions"),
                new AiTemplate("When starting a " + label + " task, what should be clarified first?", "Problem requirements and " + label + " constraints", "Only visual formatting", "Unrelated tool preferences", "Random implementation details"),
                new AiTemplate("In practical " + label + " work, what makes a solution reliable?", "Correct logic validated against " + label + " scenarios", "Fast output without validation", "Complexity without need", "Ignoring edge cases in " + label),
                new AiTemplate("Which approach improves troubleshooting in " + label + "?", "Isolate causes and test hypotheses specific to " + label, "Change multiple variables at once", "Skip diagnostics and guess", "Ignore evidence from " + label + " outputs"),
                new AiTemplate("For " + label + " assessments, which answer is usually strongest?", "An answer grounded in " + label + " principles and correct reasoning", "An answer with broad motivational advice", "An answer unrelated to " + label + " domain behavior", "An answer chosen only by length"),
                new AiTemplate("What is a strong best practice in " + label + " implementations?", "Use clear assumptions, verification, and " + label + " specific checks", "Avoid documenting decisions", "Skip validation to save time", "Prefer unrelated templates blindly"),
                new AiTemplate("In " + label + ", why are edge cases important?", "They reveal failures that basic " + label + " examples may hide", "They are never relevant in production", "They only matter in design slides", "They can be ignored after first success"),
                new AiTemplate("Which metric best reflects quality in " + label + " outcomes?", "Correctness and consistency under realistic " + label + " conditions", "Only speed regardless of errors", "Amount of copied code/text", "Number of files touched"),
                new AiTemplate("What should you do after a wrong answer in " + label + "?", "Review the exact concept gap and retry a similar " + label + " problem", "Switch to unrelated topics immediately", "Memorize only the final answer", "Ignore why the answer failed"),
                new AiTemplate("Which behavior improves long-term mastery of " + label + "?", "Iterative practice with feedback on " + label + " decisions", "One-time cramming only", "Avoiding difficult " + label + " questions", "Relying on guesswork over reasoning"));
    }

    private Map<String, Object> generateCareerInsightWithLlm(String career, String categoryHint) {
        String hfApiKey = normalize(System.getenv("HUGGINGFACE_API_KEY"));
        if (hfApiKey.isBlank()) {
            return Map.of();
        }

        String endpoint = normalize(System.getenv("HUGGINGFACE_TEXT_MODEL_URL"));
        if (endpoint.isBlank()) {
            endpoint = "https://api-inference.huggingface.co/models/mistralai/Mistral-7B-Instruct-v0.2";
        }

        String prompt = "You are a career counselor AI. Return ONLY valid JSON object (no markdown, no extra text). "
                + "Career query: \"" + career + "\". "
                + "Category hint: \"" + (categoryHint.isBlank() ? "General" : categoryHint) + "\". "
                + "Required JSON keys: role (string), category (string), overview (string), skills (array of strings), "
                + "roadmap (array of strings), responsibilities (array of strings), tools (array of strings), "
                + "salaryRange (string), futureScope (string), suggestions (array of objects with title and reason). "
                + "Keep suggestions to exactly 4 items. Keep skills/roadmap/responsibilities/tools each 4-6 items.";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inputs", prompt);
        payload.put("parameters", Map.of(
                "max_new_tokens", 1200,
                "temperature", 0.3,
                "return_full_text", false
        ));

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(12))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + hfApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Map.of();
            }

            String rawBody = normalize(response.body());
            if (rawBody.isBlank()) {
                return Map.of();
            }

            JsonNode root = OBJECT_MAPPER.readTree(rawBody);
            String generatedText;
            if (root.isArray() && root.size() > 0) {
                generatedText = normalize(root.get(0).path("generated_text").asText(""));
            } else {
                generatedText = normalize(root.path("generated_text").asText(""));
            }

            if (generatedText.isBlank()) {
                return Map.of();
            }

            int start = generatedText.indexOf('{');
            int end = generatedText.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return Map.of();
            }

            String jsonObject = generatedText.substring(start, end + 1);
            JsonNode insightNode = OBJECT_MAPPER.readTree(jsonObject);
            if (!insightNode.isObject()) {
                return Map.of();
            }

            String role = normalize(insightNode.path("role").asText(""));
            String category = normalize(insightNode.path("category").asText(""));
            if (role.isBlank() || category.isBlank()) {
                return Map.of();
            }

            return toCareerInsightResponse(insightNode, role, category);
        } catch (IOException | InterruptedException ex) {
            return Map.of();
        }
    }

    private Map<String, Object> buildCareerInsightFallback(String career, String categoryHint) {
        String normalizedCareer = normalize(career);
        String resolvedCategory = resolveCareerCategory(normalizedCareer, categoryHint);

        List<String> skills = switch (resolvedCategory.toLowerCase()) {
            case "technical" -> List.of("Problem solving", "Programming fundamentals", "Version control", "System design basics");
            case "aptitude" -> List.of("Quantitative reasoning", "Data interpretation", "Numerical accuracy", "Analytical thinking");
            case "logical" -> List.of("Critical reasoning", "Pattern recognition", "Decision frameworks", "Structured thinking");
            case "personality" -> List.of("Communication", "Empathy", "Team collaboration", "Conflict resolution");
            default -> List.of("Domain fundamentals", "Communication", "Project execution", "Continuous learning");
        };

        List<String> roadmap = List.of(
                "Build foundation with beginner-to-intermediate learning resources.",
                "Complete 2-3 practical projects aligned with " + normalizedCareer + ".",
                "Create a portfolio and optimize your resume for role-specific keywords.",
                "Apply for internships or entry-level roles and iterate with feedback.");

        List<String> responsibilities = List.of(
                "Understand business and project requirements.",
                "Execute role-specific tasks with quality and ownership.",
                "Collaborate with cross-functional stakeholders.",
                "Track outcomes and continuously improve workflows.");

        List<String> tools = switch (resolvedCategory.toLowerCase()) {
            case "technical" -> List.of("Git/GitHub", "IDE tools", "Debugging tools", "Cloud basics");
            case "aptitude" -> List.of("Excel/Sheets", "SQL", "BI dashboards", "Statistics tools");
            case "logical" -> List.of("Problem trees", "Flow diagrams", "Case frameworks", "Documentation tools");
            case "personality" -> List.of("Collaboration suites", "CRM/HR tools", "Communication tools", "Planning boards");
            default -> List.of("Documentation", "Spreadsheets", "Collaboration tools", "Task managers");
        };

        List<Map<String, String>> suggestions = switch (resolvedCategory.toLowerCase()) {
            case "technical" -> List.of(
                    Map.of("title", "Software Developer", "reason", "High overlap in coding and engineering workflows."),
                    Map.of("title", "QA Engineer", "reason", "Strong alignment with quality and technical testing."),
                    Map.of("title", "Cloud Engineer", "reason", "Relevant for infrastructure and deployment pathways."),
                    Map.of("title", "Technical Support Engineer", "reason", "Good practical path for troubleshooting-focused strengths."));
            case "aptitude" -> List.of(
                    Map.of("title", "Data Analyst", "reason", "Uses quantitative analysis and data interpretation skills."),
                    Map.of("title", "Business Analyst", "reason", "Balances numbers, logic, and business understanding."),
                    Map.of("title", "Financial Analyst", "reason", "Relies on strong numerical and analytical reasoning."),
                    Map.of("title", "Operations Analyst", "reason", "Process optimization depends on aptitude and metrics."));
            case "logical" -> List.of(
                    Map.of("title", "Product Analyst", "reason", "Decision quality and logical decomposition are core."),
                    Map.of("title", "Cybersecurity Analyst", "reason", "Pattern detection and reasoning are important."),
                    Map.of("title", "Process Analyst", "reason", "Improving systems requires structured logic."),
                    Map.of("title", "Quality Analyst", "reason", "Validation and root-cause analysis are logic-heavy."));
            default -> List.of(
                    Map.of("title", "Project Coordinator", "reason", "Helps build practical cross-functional experience."),
                    Map.of("title", "Operations Executive", "reason", "Strong option for structured execution and growth."),
                    Map.of("title", "Associate Specialist", "reason", "Role-focused development path with measurable outcomes."),
                    Map.of("title", "Program Analyst", "reason", "Blends coordination, insight, and impact tracking."));
        };

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("role", normalizedCareer);
        response.put("category", resolvedCategory);
        response.put("overview", normalizedCareer + " is a practical career path with strong growth potential when you combine consistent practice, project exposure, and role-specific skills.");
        response.put("skills", skills);
        response.put("roadmap", roadmap);
        response.put("responsibilities", responsibilities);
        response.put("tools", tools);
        response.put("salaryRange", "Varies by city, company, and experience level.");
        response.put("futureScope", "Positive long-term demand with continuous upskilling and real project work.");
        response.put("suggestions", suggestions);
        return response;
    }

    private String resolveCareerCategory(String career, String categoryHint) {
        if (!normalize(categoryHint).isBlank()) {
            return normalize(categoryHint);
        }

        String value = normalize(career).toLowerCase();
        if (value.contains("developer") || value.contains("engineer") || value.contains("cloud") || value.contains("devops")) {
            return "Technical";
        }
        if (value.contains("analyst") || value.contains("data") || value.contains("finance")) {
            return "Aptitude";
        }
        if (value.contains("security") || value.contains("product") || value.contains("process")) {
            return "Logical";
        }
        if (value.contains("hr") || value.contains("counselor") || value.contains("customer")) {
            return "Personality";
        }
        return "Career Interest";
    }

    private Map<String, Object> toCareerInsightResponse(JsonNode insightNode, String role, String category) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("role", role);
        response.put("category", category);
        response.put("overview", normalize(insightNode.path("overview").asText("")));
        response.put("skills", toStringList(insightNode.path("skills")));
        response.put("roadmap", toStringList(insightNode.path("roadmap")));
        response.put("responsibilities", toStringList(insightNode.path("responsibilities")));
        response.put("tools", toStringList(insightNode.path("tools")));
        response.put("salaryRange", normalize(insightNode.path("salaryRange").asText("Varies by location and experience.")));
        response.put("futureScope", normalize(insightNode.path("futureScope").asText("Stable career growth with continued upskilling.")));
        response.put("suggestions", toSuggestionList(insightNode.path("suggestions")));
        return response;
    }

    private List<String> toStringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String normalized = normalize(item.asText(""));
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return values;
    }

    private List<Map<String, String>> toSuggestionList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }

        List<Map<String, String>> suggestions = new ArrayList<>();
        for (JsonNode item : node) {
            String title = normalize(item.path("title").asText(""));
            if (title.isBlank()) {
                title = normalize(item.path("role").asText(""));
            }

            String reason = normalize(item.path("reason").asText(""));
            if (title.isBlank()) {
                continue;
            }

            if (reason.isBlank()) {
                reason = "Related role based on skill overlap.";
            }

            suggestions.add(Map.of("title", title, "reason", reason));
        }
        return suggestions;
    }

    private boolean isTopicRelevantText(String text, String topic) {
        String normalizedText = normalize(text).toLowerCase();
        String normalizedTopic = normalize(topic).toLowerCase();

        if (normalizedTopic.isBlank()) {
            return true;
        }

        if (normalizedText.contains(normalizedTopic)) {
            return true;
        }

        List<String> tokens = Arrays.stream(normalizedTopic.split("[^a-z0-9]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 3)
                .distinct()
                .toList();

        if (tokens.isEmpty()) {
            return normalizedText.contains(normalizedTopic);
        }

        long matches = tokens.stream().filter(normalizedText::contains).count();
        long minMatches = Math.min(2, tokens.size());
        return matches >= minMatches;
    }

    private String buildPracticeQuizTitle(String topic) {
        String normalizedTopic = normalize(topic);
        String safeTopic = normalizedTopic.isBlank() ? "General" : normalizedTopic;
        return "AI Practice - " + safeTopic + " - " + System.currentTimeMillis();
    }

    private boolean isAdmin(HttpSession session) {
        String role = normalize((String) session.getAttribute("userRole"));
        return "ADMIN".equalsIgnoreCase(role);
    }

    private boolean isLoggedIn(HttpSession session) {
        String email = normalize((String) session.getAttribute("userEmail"));
        String role = normalize((String) session.getAttribute("userRole"));
        return !email.isBlank() || !role.isBlank();
    }

    private String readFirst(Map<String, Object> payload, String firstKey, String secondKey) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        Object first = payload.get(firstKey);
        if (first != null) {
            String normalized = normalize(String.valueOf(first));
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        Object second = payload.get(secondKey);
        return second == null ? "" : normalize(String.valueOf(second));
    }

    private int readInt(Map<String, Object> payload, String key, int defaultValue) {
        if (payload == null || !payload.containsKey(key)) {
            return defaultValue;
        }
        Object value = payload.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private boolean readBoolean(Map<String, Object> payload, String key, boolean defaultValue) {
        if (payload == null || !payload.containsKey(key)) {
            return defaultValue;
        }
        Object value = payload.get(key);
        if (value == null) {
            return defaultValue;
        }
        String normalized = String.valueOf(value).trim().toLowerCase();
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
    }

    private int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstOf(Map<String, String> payload, String primary, String secondary) {
        String first = payload.get(primary);
        if (first != null && !first.isBlank()) {
            return first;
        }
        return payload.get(secondary);
    }

    private String normalizeOrPlaceholder(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "N/A" : normalized;
    }

    private String normalizeOption(String value) {
        String normalized = normalize(value);
        if (normalized.equalsIgnoreCase("N/A")) {
            return "";
        }
        return normalized;
    }

    private String toQuizKey(String category, String quizTitle) {
        return normalizeCategoryKey(category) + "::" + normalize(quizTitle).toLowerCase();
    }

    private String normalizeCategoryKey(String value) {
        String normalized = normalize(value).toLowerCase();
        if (normalized.endsWith(" assessment")) {
            normalized = normalized.substring(0, normalized.length() - " assessment".length());
        }
        return normalized;
    }
}
