<div align="center">

# 💸 ReCover-AI

### AI Revenue Recovery — Razorpay Hackathon Submission

**Every failed payment is money already earned, sitting one bad click away from being lost forever.**
ReCover-AI catches it, diagnoses it, fixes it — and proves the money it saved with real numbers, not vibes.

[What it does](#-what-it-does) · [Live architecture](#-architecture) · [The AI](#-the-ai-not-just-ifelse) · [Results](#-held-out-model-evaluation) · [Run it](#-run-it-in-2-minutes)

</div>

---

## ⚡ The 10-second pitch

A payment fails. Most systems shrug and move on — that's real GMV walking out the door. ReCover-AI:

1. **Catches it** the instant it happens (live webhook, signature-verified)
2. **Diagnoses it** with a trained ML model, not a hardcoded guess
3. **Fixes it** through the *real* Razorpay Payment Links API — actual clickable `rzp.io` links, not a mockup
4. **Proves it** with measured precision/recall on a held-out test set and a dollar-for-dollar counterfactual (recovered revenue vs. a do-nothing baseline)
5. **Logs everything** to an immutable audit trail, live on a dashboard, updating in real time

No smoke and mirrors. Real SDK, real links, real metrics.

## 🎯 What it does

| Problem | ReCover-AI's answer |
|---|---|
| Payment fails — is it fixable? | ML model trained on transaction context predicts recovery probability |
| Which fix is worth the cost? | Cost-aware policy: silent retry (₹0.05) → WhatsApp link (₹0.35) → voice call (₹1.20) — never spends more than the transaction is worth |
| Did it actually work? | Real Razorpay Payment Links, dispatched through the official Java SDK |
| Can I trust the numbers? | Every claim is backed by a held-out test-set metric, not a cherry-picked demo run |
| Is this compliant? | DND/TRAI gaps documented explicitly, not swept under the rug |

## 🏗 Architecture

```
┌──────────────┐   webhook (HMAC-verified)   ┌────────────────────────┐
│  Razorpay     │ ───────────────────────────▶│  Spring Boot 4 / Java 21│
│  (live/test)  │                              │  com.razorpay.backend  │
└──────────────┘                              │                        │
                                               │  ┌──────────────────┐  │      ┌───────────────────┐
                                               │  │ Orchestration    │──┼─────▶│ Python FastAPI      │
                                               │  │ Service (virtual │  │ HTTP │ + trained scikit-    │
                                               │  │ threads)         │  │      │ learn model :8000    │
                                               │  └────────┬─────────┘  │      └───────────────────┘
                                               │           ▼            │
                                               │  ┌──────────────────┐  │
                                               │  │ Real Razorpay SDK│──┼───▶ live rzp.io payment links
                                               │  └────────┬─────────┘  │
                                               │           ▼            │
                                               │  H2 immutable ledger   │
                                               │           ▼            │
                                               │  Thymeleaf + HTMX live │
                                               │  dashboard :8080       │
                                               └────────────────────────┘
```

**Genuinely full-stack, genuinely concurrent:** Java 21 virtual threads fan out 1,000-transaction batches without a fixed thread-pool bottleneck; the dashboard updates live via HTMX polling with zero custom JS.

## 🧠 The AI (not just if/else)

The recovery-probability model is a **calibrated `HistGradientBoostingClassifier`** — features: amount, error code, prior attempts, hour of day, customer recovery history. Trained with a strict **60/20/20 train/validation/held-out-test split**, threshold chosen on validation only, reported cold on test. This is the part most hackathon submissions skip; we didn't.

## 📊 Held-out model evaluation

*Numbers from `train_recovery_model.py`, computed on data the model never saw during training or threshold tuning:*

| Metric | Held-out test value |
|---|---|
| **Precision** | 67.6% |
| **Recall** | 98.2% |
| **F1** | 0.801 |
| **ROC-AUC** | 0.867 |
| Operating threshold | 0.20 |

**Confusion matrix (held-out test):**

| | Predicted: attempt recovery | Predicted: abort |
|---|---|---|
| **Actual: recoverable** | TP = 6,844 | FN = 122 |
| **Actual: not recoverable** | FP = 3,276 | TN = 1,758 |

**Financial impact (held-out test):**

| | Amount |
|---|---|
| 💰 Gross revenue recovered | **₹1,37,72,559** |
| Intervention cost (wasted + successful) | ₹3,542 |
| Missed revenue (recoverable, wrongly aborted) | ₹2,44,479 |
| 🏆 **Net economic benefit** | **₹1,34,24,538** |

*The threshold sits deliberately low (0.20) because a wasted ₹0.35 attempt costs almost nothing next to a missed transaction — the model is tuned to over-attempt, not under-attempt, recovery. That's a business decision baked into the math, not an accident.*

## 💵 Counterfactual: recovered revenue vs. doing nothing

Every recovered rupee is measured against a **do-nothing baseline** (0% self-resolve), not an inflated comparison:

| Metric | Value |
|---|---|
| Baseline (do-nothing) | ₹0 |
| ReCover-AI recovered | *live from dashboard "Gross Recovered"* |
| Net benefit vs. baseline | Gross Recovered − Intervention Cost |

Click "Trigger Batch Benchmark" on the dashboard to watch 1,000 transactions get diagnosed, acted on, and logged — live, in seconds.

## 🔒 Stopping rules & compliance — documented, not hand-waved

- **Bounded retries**: max 3 attempts per transaction, ever.
- **Terminal-failure short-circuit**: `CARD_BLOCKED`/`STOLEN_CARD` abort immediately — no wasted retries on a fraud signal.
- **Cost-bounded action selection**: the expensive channel (voice) only triggers above ₹1,500.
- **DND/TRAI**: this demo does **not** implement DND-registry checks, consent tracking, or DLT template registration — called out explicitly in-repo rather than glossed over, because a hackathon judge who catches an unstated compliance gap trusts the rest of the submission less.

## 🛠 Tech stack

`Spring Boot 4` · `Java 21 (virtual threads)` · `Thymeleaf + HTMX` · `H2` · `Python FastAPI` · `scikit-learn` (calibrated gradient boosting) · `Razorpay Java SDK` (live Payment Links + HMAC-verified webhooks)

## 🚀 Run it in 2 minutes

```bash
# Terminal 1 — AI engine
cd ai-engine
pip install -r requirements.txt
python train_recovery_model.py     # trains + reports the metrics above
python app.py                      # :8000

# Terminal 2 — Backend
cd backend
./mvnw spring-boot:run             # :8080
```

Open **http://localhost:8080/**, hit **Trigger Batch Benchmark**, watch it work.

H2 console: `http://localhost:8080/h2-console` — JDBC `jdbc:h2:mem:recoverdb`, user `sa`, no password.

## 🔌 API surface

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/failures/ingest-batch` | Batch process; empty body auto-generates 1,000 synthetic transactions |
| `GET` | `/api/v1/dashboard/stats` / `/metrics` | Live aggregate metrics (JSON / HTMX fragment) |
| `GET` | `/api/v1/dashboard/audit-rows` | Latest 50 audit rows, HTMX-polled |
| `POST` | `/api/v1/razorpay/webhook` | Real Razorpay `payment.failed` webhook, HMAC-signature-verified |

## 📒 Audit trail — nothing hidden

Every processed transaction writes one **immutable** row: `transaction_id`, `amount`, `error_code`, `action_taken`, `decision_trace` (the full reasoning, up to 1000 chars), `intervention_cost`, `recovered_amount`, `status`, `payment_link_url`, `created_at`. Queryable live via `/h2-console`. If a judge asks "how did it decide that?" — the answer is one query away.

## 🧭 What's next (said out loud, not buried)

- DND/TRAI compliance layer before any real customer contact
- Webhook-confirmed recovery status (payment actually completed, not just link dispatched)
- LLM-personalized outreach scripts instead of templated Hinglish copy
- Hosted demo instance

---

<div align="center">

**Built for the Razorpay AI Revenue Recovery hackathon track.**
Real SDK. Real ML. Real numbers.

</div>