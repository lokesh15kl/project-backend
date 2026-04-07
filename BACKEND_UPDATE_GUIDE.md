# Backend Update Guide for Written & Audio Questions

## Files Added:
1. **QuestionResponse.java** - New entity to store written/audio responses
2. **QuestionResponseRepository.java** - Repository for QuestionResponse

## Files to Update:
1. **Quiz.java** - Add `questionType` field
2. **QuizApiController.java** - Add new endpoint for typed submissions

## Step 1: Update Quiz Entity

Add this field to `Quiz.java`:

```java
@Column(nullable = true)
private String questionType; // mcq, written, audio

public String getQuestionType() {
    return questionType != null ? questionType : "mcq";
}

public void setQuestionType(String questionType) {
    this.questionType = questionType;
}
```

## Step 2: Add New Endpoint to QuizApiController

Add this endpoint to `QuizApiController.java`:

```java
@Autowired
private QuestionResponseRepository questionResponseRepo;

@PostMapping("/submitQuizWithAudio")
@Transactional
public int submitQuizWithAudio(@RequestParam Map<String, String> params,
                               @RequestParam(value = "writtenAnswers", required = false) String writtenAnswersJson,
                               HttpSession session,
                               Model model) throws Exception {

    User user = (User) session.getAttribute("loggedUser");
    if (user == null) {
        return 0;
    }

    String category = (String) session.getAttribute("currentCategory");
    String quizTitle = (String) session.getAttribute("currentQuiz");

    List<Quiz> quizList = quizRepo.findByCategoryAndQuizTitle(category, quizTitle);

    int score = 0;

    // Score MCQ questions
    for (Quiz q : quizList) {
        String qType = q.getQuestionType() != null ? q.getQuestionType() : "mcq";
        
        if ("mcq".equals(qType)) {
            String userAnswer = params.get("q" + q.getId());
            if (q.getCorrectAnswer().equals(userAnswer)) {
                score++;
            }
        } else if ("written".equals(qType) || "audio".equals(qType)) {
            String userAnswer = params.get("q" + q.getId());
            if (userAnswer != null && !userAnswer.isEmpty()) {
                // For written/audio, store response and mark as answered
                QuestionResponse qr = new QuestionResponse();
                qr.setUserEmail(user.getEmail());
                qr.setCategory(category);
                qr.setQuizTitle(quizTitle);
                qr.setQuestionId(q.getId());
                qr.setQuestionType(qType);
                qr.setTextAnswer(userAnswer);
                qr.setSubmittedAt(LocalDateTime.now());
                questionResponseRepo.save(qr);
                
                // Count as answered (automatic point)
                score++;
            }
        }
    }

    // Save overall score
    Score s = new Score();
    s.setUserEmail(user.getEmail());
    s.setCategory(category);
    s.setQuizTitle(quizTitle);
    s.setScore(score);
    scoreRepo.save(s);

    return score;
}
```

## Step 3: Add Import Statements to QuizApiController

```java
import java.time.LocalDateTime;
```

## Frontend Alignment:

The frontend now sends to `/api/submitQuizWithAudio` with FormData containing:
- `q<id>` - All answers (MCQ, written, or AUDIO_RECORDED marker)
- `category` - Quiz category
- `quizTitle` - Quiz name
- `audioResponses` - Actual audio files (if any)
- `questionMeta` - Question metadata

The endpoint will:
1. Extract answers from FormData
2. Score MCQ questions normally
3. Store written/audio responses in QuestionResponse table
4. Return final score
