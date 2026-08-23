# ReCover-AI

**AI Revenue Recovery** — detects at-risk revenue from failed Razorpay payments, diagnoses the root cause, picks the cheapest effective intervention, executes it through the real Razorpay API, and proves the money it recovered against a measured counterfactual baseline. Every decision is logged to an immutable audit trail.

## What this solves

Every failed payment is revenue sitting on the table. Most teams either do nothing (leave it on the table) or blast every failure with the same expensive intervention (call center, generic email) regardless of whether the failure is even recoverable. ReCover-AI instead:

1. **Detects** revenue at risk the moment a payment fails (webhook or batch ingestion).
2. **Diagnoses** *why* it failed — soft gateway issue vs. user-side friction vs. terminal instrument failure.
3. **Picks an intervention** sized to the failure: silent retry, a WhatsApp smart link, or a voice call — never spending more to recover a transaction than the transaction is worth.
4. **Executes** the intervention through the real Razorpay Payment Links API.
5. **Measures** money actually recovered, against what a do-nothing baseline would have recovered, on the same batch.
6. **Logs** every step — diagnosis, action, cost, outcome — to a queryable H2 audit ledger, live on a dashboard.

## Architecture

```
┌──────────────┐   webhook / batch    ┌───────────────────────┐
│  Razorpay     │ ────────────────────▶│  Spring Boot backend   │
│  (webhooks)   │  (signature-verified) │  com.razorpay.backend  │
└──────────────┘                       │                        │
                                        │  ┌──────────────────┐  │      ┌──────────────────┐
                                        │  │ Orchestration    │──┼─────▶│ Python AI engine  │
                                        │  │ Service          │  │ HTTP │ FastAPI :8000      │
                                        │  └────────┬─────────┘  │      │ diagnose-and-plan  │
                                        │           │            │      └──────────────────┘
                                        │           ▼            │
                                        │  ┌──────────────────┐  │
                                        │  │ Razorpay SDK      │──┼───▶ rzp.io payment links
                                        │  │ (payment link)     │  │
                                        │  └────────┬─────────┘  │
                                        │           ▼            │
                                        │  ┌──────────────────┐  │
                                        │  │ H2 audit ledger   │  │
                                        │  └────────┬─────────┘  │
                                        │           ▼            │
                                        │  Thymeleaf + HTMX       │
                                        │  live dashboard :8080   │
                                        └───────────────────────┘
```

**Two services run together:**
1. `ai-engine/` — Python FastAPI diagnostic & recovery-planning engine (port 8000). Diagnoses the failure and recommends an action, cost, and confidence score. `train_recovery_model.py` trains the calibrated recovery-probability model behind it.
2. `backend/` — Spring Boot 4 / Java 21 orchestration layer (port 8080). Calls the AI engine, executes the decision via the Razorpay SDK, persists to H2, and serves the live dashboard.

## Decision policy

| Diagnosis | Action | Typical cost |
|---|---|---|
| `attempts_so_far >= 3`, or terminal instrument failure (`CARD_BLOCKED`, `STOLEN_CARD`, `ACCOUNT_CLOSED`) | `ABORT` | ₹0 |
| Soft technical/gateway failure (`GATEWAY_TIMEOUT`, `BAD_REQUEST_PAYMENT_TIMED_OUT`) | `AUTO_RETRY` | ₹0.05 |
| User-side friction, amount ≥ ₹1,500 | `VOICE_OUTREACH` | ₹1.20 |
| Everything else | `WHATSAPP_LINK` | ₹0.35 |

If the Razorpay API is unreachable or keys are placeholders, the backend falls back to a deterministic mock `rzp.io` link so demo runs still populate recovered metrics realistically. If the AI engine itself is unreachable, the backend applies the same bounded policy locally so recovery never silently stalls.

## Stopping rules

Recovery attempts are bounded, not unlimited:

- **Attempt cap** — no more than 3 recovery attempts per transaction. A 4th attempt is never made; the transaction is marked `ABORT`ed and written off.
- **Terminal-failure short-circuit** — `CARD_BLOCKED`, `STOLEN_CARD`, and `ACCOUNT_CLOSED` abort immediately regardless of attempt count. Retrying a stolen or blocked card isn't just wasteful — it's the wrong thing to do.
- **Cost-bounded action selection** — the more expensive action (voice) is only used above the ₹1,500 threshold, so intervention spend is never disproportionate to the transaction it's trying to recover.

## Compliance: DND / TRAI

Voice and WhatsApp outreach touch a regulated channel in India (TRAI's Telecom Commercial Communications Customer Preference Regulations — NDNC/DND). This build is a hackathon demo and does **not** currently implement:

- DND/NDNC registry checks before placing a voice call or WhatsApp message
- Consent/opt-in tracking per customer for transactional outreach
- Time-of-day restrictions on outreach (TRAI restricts commercial calls to specific hours)
- A registered DLT (Distributed Ledger Technology) template ID for the WhatsApp/SMS content, as required for commercial messaging in India

**Before any real customer is contacted, ReCover-AI would need:** a DND-registry check integrated ahead of `VOICE_OUTREACH`/`WHATSAPP_LINK` dispatch, all outreach scoped to transactional (not promotional) intent — which is generally exempt from DND restrictions, but the content and template still need to qualify — DLT template registration for the WhatsApp copy, and an audit field recording consent basis per contact attempt. None of this is implemented yet; it's called out explicitly here rather than glossed over, since the problem statement asks for compliance to be documented, not assumed.

## Measuring recovered revenue against a counterfactual

Running a batch benchmark reports the counterfactual explicitly: **recovered revenue is only credited when ReCover-AI's actual action differs from a do-nothing baseline**. The do-nothing baseline is "the transaction stays failed" — so `recovered_amount` in the audit ledger is compared against a hypothetical baseline where 0% of failures are ever recovered.

| Metric | Value |
|---|---|
| Baseline (do-nothing) revenue recovered | ₹0 — no failed transaction self-resolves without intervention |
| ReCover-AI revenue recovered | ₹— (fill in from a live batch run — see dashboard "Gross Recovered") |
| Total intervention cost | ₹— (dashboard "Intervention Cost") |
| Net benefit vs. baseline | Gross Recovered − Intervention Cost |

Run the 1,000-txn "Trigger Batch Benchmark" from the dashboard and fill in the numbers above before submitting.

## Held-out model evaluation (`train_recovery_model.py`)

The recovery-probability model behind the diagnostic engine is trained with a strict 60/20/20 train/validation/held-out-test split, calibrated with isotonic regression (`CalibratedClassifierCV` over `HistGradientBoostingClassifier`), with the decision threshold chosen on the validation set only and reported once, cold, on the held-out test set:

| Metric | Held-out test value |
|---|---|
| Precision | 67.6% |
| Recall | 98.2% |
| F1 | 0.801 |
| ROC-AUC | 0.867 |
| Operating threshold | 0.20 |

**Confusion matrix (held-out test):**

| | Predicted: attempt recovery | Predicted: abort |
|---|---|---|
| **Actual: recoverable** | TP = 6,844 | FN = 122 |
| **Actual: not recoverable** | FP = 3,276 | TN = 1,758 |

**Financial impact (held-out test, at the chosen threshold):**

| | Amount |
|---|---|
| Gross revenue recovered | ₹1,37,72,559 |
| Total intervention cost (wasted + successful attempts) | ₹3,542 |
| Missed revenue (recoverable transactions incorrectly aborted) | ₹2,44,479 |
| Net economic benefit | ₹1,34,24,538 |

**Threshold tradeoff (validation set):**

| Threshold | Precision | Recall | F1 |
|---|---|---|---|
| 0.35 | 73.3% | 93.9% | 0.824 |
| 0.50 | 78.9% | 85.2% | 0.819 |
| 0.65 | — | — | — |
| 0.75 | — | — | — |

The optimal threshold (0.20) sits low because the false-positive cost (a wasted ₹0.35 intervention attempt) is negligible next to the false-negative cost (the full transaction amount, lost because a recoverable transaction was wrongly aborted) — so the model is deliberately tuned to over-attempt rather than under-attempt recovery. Re-run `python train_recovery_model.py` to regenerate this table from a fresh synthetic sample.

## Running it

```bash
# Terminal 1 — Python AI engine
cd ai-engine
pip install -r requirements.txt
python train_recovery_model.py       # optional: (re)generate payment_recovery_model.joblib
python app.py                        # serves on :8000

# Terminal 2 — Spring Boot backend
cd backend
./mvnw spring-boot:run               # serves on :8080
```

Open the dashboard at **http://localhost:8080/**. Click "Trigger Batch Benchmark" to run 1,000 synthetic Indian payment failures through the full pipeline and watch the ledger and metric cards update live.

H2 console: http://localhost:8080/h2-console — JDBC URL `jdbc:h2:mem:recoverdb`, user `sa`, blank password.

## Configuration

```properties
razorpay.key.id=rzp_test_xxxxxxxxxxxx
razorpay.key.secret=xxxxxxxxxxxxxxxxxxxx
razorpay.webhook.secret=xxxxxxxxxxxxxxxxxxxx
ai.engine.url=http://localhost:8000/api/v1/diagnose-and-plan
```

Webhook payloads to `POST /api/v1/razorpay/webhook` are rejected with `401` unless `X-Razorpay-Signature` verifies against `razorpay.webhook.secret` (HMAC-SHA256, via the official SDK's `Utils.verifyWebhookSignature`). Signature checking is skipped — with a warning logged — only when the secret is left at its placeholder value, for local demos.

## API surface

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/failures/ingest` | Ingest and process a single failure event |
| `POST` | `/api/v1/failures/ingest-batch` | Ingest a batch; empty body auto-generates 1,000 synthetic transactions |
| `GET` | `/api/v1/dashboard/stats` | Aggregate metrics as JSON |
| `GET` | `/api/v1/dashboard/metrics` | Aggregate metrics as an HTML fragment (HTMX-polled) |
| `GET` | `/api/v1/dashboard/audit-rows` | Latest 50 audit rows as an HTML fragment (HTMX-polled) |
| `POST` | `/api/v1/razorpay/webhook` | Real/simulated Razorpay `payment.failed` webhook, signature-verified |

## Audit trail

Every processed transaction writes one immutable row to `recovery_audit` (H2), visible live on the dashboard and queryable via `/h2-console`: `transaction_id`, `customer_name`/`phone`, `amount`, `error_code`, `action_taken`, `decision_trace` (the diagnosis reasoning, up to 1000 chars), `intervention_cost`, `recovered_amount`, `status`, `payment_link_url`, `created_at`. Nothing is overwritten or deleted — the ledger is append-only, so the full reasoning trail behind every dashboard number is always reconstructable.

## Known limitations / next steps

- DND/TRAI compliance checks are not implemented (see Compliance section above) — required before any real customer contact.
- Decision policy in the backend fallback path is rule-based; the trained `HistGradientBoostingClassifier` in `ai-engine/` is the primary source of the recovery-probability score when its artifact is present.
- No auth on the dashboard or ingest endpoints (fine for a demo, not for production).
- Voice/WhatsApp scripts are templated, not generated per-customer by an LLM.
- Deployed only for local demo; no hosted instance.