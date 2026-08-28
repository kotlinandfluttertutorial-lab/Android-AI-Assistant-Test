# NotebookLM Study Prompts — AI DevOps Assistant

## How to use this file

1. Upload `docs/AI_DEVOPS_ASSISTANT.md` to NotebookLM as your source
2. Optionally also upload the individual phase guides (AIOPS_GUIDE.md, ERROR_ANALYSIS_GUIDE.md, etc.)
3. Use the prompts below one by one — each builds on the previous

---

## SECTION 1 — Foundations (Android + Backend)

### Prompt 1.1 — Architecture recall
```
Explain the Android AI DevOps Assistant architecture in simple terms.
Start with: what does the Android app do, what does the backend do,
how do they talk to each other, and what external services are involved.
Use an analogy to help me remember.
```

### Prompt 1.2 — Clean Architecture
```
Explain Clean Architecture using the feature-dashboard module as the example.
Walk me through: what lives in the domain layer, what lives in the data layer,
what lives in the feature module, and why each layer doesn't know about the layers above it.
Give me the one-sentence rule for each layer.
```

### Prompt 1.3 — Observability pipeline quiz
```
Quiz me on the Android observability pipeline.
Ask me 5 questions one at a time. After each answer, tell me if I'm right
and explain the correct answer. Cover:
- What is ObservabilityEventBus?
- What does PiiFilter do?
- How does ObservabilityManager work?
- What triggers ObservabilityUploadWorker?
- What is the difference between requestId, traceId, and sessionId?
```

### Prompt 1.4 — Analogy game
```
Use a real-world analogy to explain each of these concepts so I can remember them:
1. ObservabilityEventBus
2. ChromaDB vector store
3. Celery beat task
4. JWT access token vs refresh token
5. RAG (Retrieval-Augmented Generation)
Make each analogy one memorable sentence.
```

---

## SECTION 2 — DevOps & Cloud

### Prompt 2.1 — CI/CD pipeline story
```
Tell me the story of what happens from the moment a developer pushes code to GitHub
until the new version is live on Cloud Run.
Name every step and every tool involved.
Then ask me to retell it back to you so you can check what I missed.
```

### Prompt 2.2 — Cloud Run vs Kubernetes
```
I always forget when to use Cloud Run vs Kubernetes.
Create a memorable rule of thumb — like a single sentence or a mnemonic —
that I can use to decide instantly.
Then give me 5 scenarios and ask me which one to use for each.
```

### Prompt 2.3 — Terraform concepts
```
Explain Terraform using the analogy of a recipe book.
Cover: what is a state file, what does terraform plan do, what does terraform apply do,
what is a module, and what is a remote backend.
End with a 3-question quiz.
```

### Prompt 2.4 — Secrets management drill
```
I need to memorize how secrets are managed in this project.
Explain the journey of a secret (like GEMINI_API_KEY) from:
  "developer creates it" → "stored where" → "used by what" → "app reads it how"
Then ask me to describe the journey of DATABASE_URL the same way.
```

---

## SECTION 3 — Observability (Logs/Metrics/Traces)

### Prompt 3.1 — Three pillars
```
Explain the three pillars of observability using a detective analogy.
Then quiz me: give me 5 real scenarios (like "the API is slow" or
"the error rate spiked at 14:32") and ask me which pillar I would use first
to investigate and why.
```

### Prompt 3.2 — Prometheus metrics
```
I need to memorize the key Prometheus metrics in this project.
For each metric below, explain what it measures, what type it is
(Counter/Gauge/Histogram), and what PromQL query I'd use to monitor it:
- http_requests_total
- http_request_duration_seconds
- celery_queue_depth
- llm_token_cost_usd_total
Then create a 5-question fill-in-the-blank quiz.
```

### Prompt 3.3 — OpenTelemetry
```
Explain OpenTelemetry tracing in simple terms.
Cover: what is a trace, what is a span, what does auto-instrumentation mean,
and how does it help debug a slow API request.
Use a relay race analogy.
Then ask me: "A user says POST /chat is slow. Walk me through using traces to find
the bottleneck." Let me answer and correct me.
```

---

## SECTION 4 — RAG (Retrieval-Augmented Generation)

### Prompt 4.1 — RAG pipeline walkthrough
```
Walk me through the RAG pipeline step by step as if explaining to someone
who has never heard of it. Use cooking as an analogy.
Cover: chunking, embedding, storing in ChromaDB, query-time retrieval,
context assembly, and LLM answer generation.
After explaining, ask me to explain it back in my own words.
```

### Prompt 4.2 — Embedding concepts
```
I struggle to understand what an "embedding" actually is.
Explain it using a map/coordinates analogy.
Then explain why using the SAME embedding model for ingestion and retrieval is critical.
What happens if you use different models?
End with a one-sentence memorable rule.
```

### Prompt 4.3 — Chunking trade-offs
```
Quiz me on chunking decisions:
1. Why do we use 512 tokens? What's wrong with 10 tokens? What's wrong with 5000 tokens?
2. What is overlap and why does it matter?
3. What is the difference between how PDFs and TXT files are cited?
4. Why does property 7 say "every token must appear in at least one chunk"?
Ask me the questions one at a time and explain after each answer.
```

### Prompt 4.4 — RAG vs fine-tuning decision
```
I need to be able to answer "should you use RAG or fine-tuning?" confidently in interviews.
Create a decision tree I can memorize.
Give me 5 scenarios and ask me to say "RAG", "fine-tuning", or "both" for each.
Explain the reasoning after each answer.
```

---

## SECTION 5 — AI Analysis Pipeline (Phases 10–12)

### Prompt 5.1 — Phase 10 error analysis
```
Explain the Phase 10 AI error analysis pipeline as a sequence of 8 numbered steps.
After explaining, remove the step numbers and give me the descriptions in random order.
Ask me to put them back in the correct order.
```

### Prompt 5.2 — Confidence gate
```
The 0.6 confidence gate is a core AI safety concept in this project.
Explain: what is it, where is it enforced (prompt AND code), and why it must be
in the application code and not just the prompt.
Then ask me: "What happens if the LLM returns confidence 0.72 but the evidence
is clearly insufficient — and the gate is only in the prompt, not in code?"
```

### Prompt 5.3 — Facts vs inference
```
Explain the difference between "facts" and "inferences" in the context of
AI error analysis. Why does the AI analysis response separate them?
Give me 5 statements from an imaginary incident report and ask me to
label each as "fact" or "inference".
```

### Prompt 5.4 — Phase 12 RCA vs Phase 10
```
I keep confusing Phase 10 (Error Analysis) and Phase 12 (Root Cause Analysis).
Create a comparison table I can memorize with 5 rows.
Then give me a scenario and ask me: "Would you call Phase 10 or Phase 12 here, and why?"
Do this 3 times with different scenarios.
```

---

## SECTION 6 — AI DevOps Assistant (Phase 13)

### Prompt 6.1 — ReAct pattern
```
Explain the ReAct pattern using a detective analogy.
Cover: Think → Act → Observe → Repeat.
Then trace through this exact scenario step by step:
User asks: "Why did the API fail at 14:32?"
What tools get called, in what order, and what does the final answer look like?
After explaining, ask me to trace a new scenario: "Show me open critical incidents."
```

### Prompt 6.2 — Tool selection quiz
```
I need to memorize which of the 7 DevOps tools to use for different questions.
The tools are: search_logs, search_incidents, search_runbooks, analyse_errors,
get_rca, get_incident_summary, create_incident.

Quiz me: give me 10 user questions one at a time.
For each, I'll name the tool (or tools) I'd call first.
Correct me and explain the reasoning after each answer.
```

### Prompt 6.3 — Grounding constraint
```
Explain what "grounding" means in the context of LLM applications.
Why is it important for the DevOps assistant?
What could go wrong if the assistant had no grounding constraint?
Give me a concrete example of a hallucination that the grounding constraint prevents.
```

---

## SECTION 7 — AIOps (Phase 15)

### Prompt 7.1 — Full AIOps loop
```
Explain the full AIOps loop from start to finish — from the moment an Android
error event is uploaded to the moment a developer approves a remediation action.
Name every component involved, in order.
Then show me the loop as a numbered list of 12 steps, hide the list,
and ask me to reconstruct it from memory.
```

### Prompt 7.2 — Risk tiers
```
I need to memorize the remediation action risk tiers.
Explain why these three tiers exist and what makes an action LOW vs MEDIUM vs HIGH risk.
Then quiz me: give me 8 actions (like "restart the service", "send a Slack message",
"roll back the deployment") and ask me to assign each to LOW/MEDIUM/HIGH.
Explain after each answer.
```

### Prompt 7.3 — Human-in-the-loop
```
The master plan says: "The AI must never automatically execute destructive
production actions without explicit approval."
Give me 5 arguments for why this principle matters.
Then ask me: "What would go wrong if the AIOps system automatically rolled back
every deployment that caused a 5% error rate spike?"
```

---

## SECTION 8 — Security

### Prompt 8.1 — Security layers drill
```
Quiz me on the security layers in this project.
For each attack scenario below, ask me: "What security control prevents this?"
1. An attacker steals a developer's laptop and finds the .env file
2. An attacker intercepts HTTPS traffic with a forged certificate
3. An attacker crafts a giant JSON payload to crash the backend
4. An attacker embeds "Ignore all previous instructions" in a chat message
5. A compromised GitHub account tries to deploy malicious code
6. An attacker brute-forces a user password
7. A stolen JWT is used 20 minutes after being stolen
Ask me one at a time and correct after each.
```

### Prompt 8.2 — JWT deep dive
```
I keep getting confused about JWTs. Explain:
1. What is inside a JWT (the 3 parts)?
2. What is the difference between an access token and a refresh token?
3. What is refresh token rotation and why does it matter?
4. What does "short-lived" mean and what happens if a JWT is stolen?
Use a hotel key card analogy.
Then quiz me with 4 true/false statements about JWTs.
```

### Prompt 8.3 — Workload Identity Federation
```
Explain Workload Identity Federation using a passport/visa analogy.
Cover: what problem it solves, how it works step by step,
and why it is more secure than a service account key file.
Then ask me: "What is the maximum damage an attacker can do
if they intercept a WIF token vs a service account JSON key?"
```

---

## SECTION 9 — Testing

### Prompt 9.1 — Testing pyramid
```
Explain the testing pyramid for this project.
For each layer (unit / integration / E2E), tell me:
- What tool is used
- What it tests
- How fast it runs
- What it can't test
Then ask me: "A ViewModel test fails — which layer is it, and can it run without an emulator?"
Do this for 5 different test scenarios.
```

### Prompt 9.2 — AI testing strategies
```
"How do you test AI/LLM systems when the output is non-deterministic?"
is one of the hardest interview questions.
Explain the 3 approaches used in this project:
property testing, golden set evaluation, and statistical testing.
Then generate a practice interview scenario for me to answer,
and give me feedback on my answer.
```

### Prompt 9.3 — StateFlow + Turbine
```
Explain how StateFlow testing works with Turbine in Android.
Show me what the test looks like for DashboardViewModel:
"loading → content with 3 incidents".
Then give me a new scenario: "content → refresh → content with error"
and ask me to write the test structure from memory.
```

---

## SECTION 10 — End-to-End Scenarios

### Prompt 10.1 — Incident scenario walkthrough
```
Walk me through a complete real-world incident scenario from start to finish.
Use this specific incident: "The API error rate suddenly spiked to 23% at 14:32."

Step through:
1. How the anomaly was detected (Phase 11)
2. What the Android developer received on their phone (Phase 15 FCM)
3. What they see when they open the dashboard (Phase 14)
4. What the AI error analysis says (Phase 10)
5. What the RCA says (Phase 12)
6. What remediation is recommended (Phase 15)
7. What the developer approves and does manually
8. How the incident is resolved

After explaining, ask me to retell it in my own words.
```

### Prompt 10.2 — DevOps assistant conversation
```
Simulate a real conversation between me and the AI DevOps assistant.
I will ask questions as if I'm an on-call engineer at 3am.
You play the assistant and show me:
- Which tool you call for each question
- What the tool returns
- What your final answer is

Start with my first question: "Something is wrong. What is the current status of the system?"
After 3 exchanges, pause and ask me: "What tool would you have called for my last question, and why?"
```

### Prompt 10.3 — Architecture recall challenge
```
This is a memory test. I will not look at any notes.

Ask me 10 questions about the project architecture, one at a time.
After each answer, tell me: correct, partially correct, or wrong — and explain.
Cover a mix of:
- Technology choices (why Neon not Cloud SQL?)
- Data flows (how does an Android error become an incident?)
- AI patterns (why ReAct instead of plain RAG?)
- Security (where is GEMINI_API_KEY stored and how does the container read it?)
- Numbers (what is the confidence threshold? what is the chunk size? how often does Celery beat run?)
```

---

## SECTION 11 — Interview Preparation

### Prompt 11.1 — Elevator pitch
```
Help me craft a 90-second elevator pitch for this project that I can say in an interview.
It should cover: what the system does, what technologies it uses, what the hardest
technical challenge was, and what I learned.
After you write a version, ask me to say it back in my own words,
then give me feedback on what I forgot or could improve.
```

### Prompt 11.2 — "Tell me about a technical challenge"
```
I need to prepare for the question: "Tell me about the most technically
challenging thing you built in this project."

Give me 5 strong candidate answers based on the project content.
For each, give me: the challenge, why it was hard, how I solved it,
and what I learned. Help me pick the best one for a senior Android engineer role.
```

### Prompt 11.3 — Live coding prep
```
Based on this project, what are the 5 most likely coding questions an interviewer
might ask? For each, give me:
1. The question
2. The key concept it tests
3. The key parts of the answer
4. A common mistake to avoid
Focus on: Kotlin coroutines, Clean Architecture, Jetpack Compose state,
RAG implementation, and LLM prompt design.
```

### Prompt 11.4 — Rapid fire quiz
```
Ask me 20 rapid-fire questions about this project. One question at a time.
I answer with one sentence. You say: ✅ or ❌ and give the correct answer in one sentence.
Cover: architecture decisions, technology choices, AI concepts, security controls,
and operational procedures.
Go.
```

### Prompt 11.5 — Spaced repetition set
```
Generate 30 flashcard-style questions and answers from this project
that I can use for spaced repetition.

Format each as:
Q: [question]
A: [answer — one sentence max]

Cover all 20 phases evenly. Focus on the "why" behind decisions,
not just the "what". These should be the things most likely to be asked in an interview.
```

---

## SECTION 12 — Concept Connections

### Prompt 12.1 — How everything connects
```
Draw me a mental map of how the 20 phases connect to each other.
For each phase, tell me: what it depends on from previous phases,
and what it enables in later phases.
Start from Phase 1 and work forward.
This will help me see the project as a whole, not just separate parts.
```

### Prompt 12.2 — The "why" behind every major decision
```
For each major architectural decision below, ask me "why was this chosen?"
one at a time. After I answer, give me the correct reasoning.
1. Why Clean Architecture (not MVC)?
2. Why ChromaDB (not PostgreSQL for vectors)?
3. Why Celery beat (not a cron job)?
4. Why Neon PostgreSQL (not Cloud SQL)?
5. Why WIF (not a service account JSON key)?
6. Why Cloud Run (not Kubernetes)?
7. Why SentenceTransformer all-MiniLM-L6-v2 (not OpenAI embeddings)?
8. Why ReAct loop (not plain RAG for the DevOps assistant)?
9. Why confidence gate in code (not just in the prompt)?
10. Why human approval before remediation execution?
```

### Prompt 12.3 — Teach it back
```
I am going to teach you this project as if you know nothing about it.
Start me off with a question: "What is the AI DevOps Assistant?"
After I explain, ask follow-up questions to probe my understanding deeper.
Push me until I can't answer. Then tell me what I missed and what I should study next.
```

---

## Tips for using NotebookLM effectively

1. **Upload the right sources first:**
   - `docs/AI_DEVOPS_ASSISTANT.md` — the master reference
   - The specific phase guide for whatever you're studying that day

2. **Don't just read — get quizzed.** Use prompts 1.3, 2.3, 3.2, 4.3, 6.2, 8.1 for active recall.

3. **Spaced repetition order:**
   - Day 1: Section 1 (foundations) + Section 4 (RAG)
   - Day 2: Section 2 (DevOps/Cloud) + Section 5 (AI analysis)
   - Day 3: Section 3 (Observability) + Section 6 (DevOps assistant)
   - Day 4: Section 7 (AIOps) + Section 8 (Security)
   - Day 5: Section 9 (Testing) + Section 10 (end-to-end scenarios)
   - Day 6+: Section 11 (interview prep) + Section 12 (concept connections)

4. **Use Prompt 11.4 (rapid fire quiz) every day** — 5 minutes of daily recall
   is more effective than 1 hour of re-reading.

5. **Say answers out loud** before reading NotebookLM's response.
   Speaking engages different memory pathways than reading.

6. **After each session, write 3 things you learned** in your own words.
   The act of writing consolidates memory better than re-reading.
