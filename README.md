# ReCover-AI

**AI Revenue Recovery — Razorpay Buildathon Submission**

ReCover-AI detects revenue at risk the moment a payment fails — via live, signature-verified Razorpay webhook or batch ingestion — diagnoses the root cause, scores recovery probability with a calibrated machine learning model, selects a cost-aware intervention, executes it through the real Razorpay Payment Links API, confirms recovery through a webhook-driven status transition, and logs every step to an immutable audit trail. Nothing here is a mockup: the SDK is real, the model is trained and evaluated on a held-out test set, and the numbers are reproducible.

## Architecture

```
┌──────────────┐   webhook (HMAC-verified)   ┌────────────────────────┐
│  Razorpay     │ ───────────────────────────▶│  Spring Boot 4 / Java 21│
│  (live/test)  │                              │  com.razorpay.backend  │
└──────────────┘                              │                        │
                                               │  ┌──────────────────┐  │      ┌───────────────────┐
                                               │  │ Orchestration    │──┼─────▶│ Python FastAPI      │
                                               │  │ Service (virtual │  │ HTTP │ + calibrated         │
                                               │  │ threads)         │  │      │ scikit-learn model   │
                                               │  └────────┬─────────┘  │      └───────────────────┘
                                               │           ▼            │
                                               │  ┌──────────────────┐  │
                                               │  │ Real Razorpay SDK│──┼───▶ live rzp.io payment links
                                               │  └────────┬─────────┘  │
                                               │           ▼            │
                                               │  H2 immutable ledger   │
                                               │           ▲            │
                                               │  payment_link.paid ────┘
                                               │  webhook → status flip
                                               │           ▼
                                               │  Thymeleaf + HTMX live │
                                               │  dashboard :8080       │
                                               └────────────────────────┘
```

Java 21 virtual threads fan out batch processing without a fixed thread-pool bottleneck. The dashboard updates live via HTMX polling — no custom JS.

## The AI pipeline: where ML is used, and where it deliberately isn't

The system is built on one design principle: **a model proposes a probability; a deterministic policy decides whether money moves.**

- **Diagnosis and probability scoring** — a calibrated `HistGradientBoostingClassifier` (`scikit-learn`, isotonic calibration via `CalibratedClassifierCV`) predicts recovery probability from transaction context: amount, error code, prior attempts, hour of day, customer recovery history. This is where prediction adds real value — the outcome is genuinely uncertain, and probability estimation is what the transaction needs.
- **Stopping rules and cost gates** — bounded, deterministic, and auditable by design: max 3 attempts, immediate abort on terminal instrument failure (`CARD_BLOCKED`, `STOLEN_CARD`), cost-tiered action selection (retry → WhatsApp → voice), and a compliance gate (below). These are **not** delegated to the model. A safety boundary or a spend limit should never depend on a model's confidence — it should be a fact you can point to in an audit log.

This split is why the system is trustworthy under review: every dollar spent traces back to a rule you can read, not a black-box decision.

## Held-out model evaluation

Trained with a strict 60/20/20 train/validation/held-out-test split. The decision threshold is selected on the validation set only and reported once, cold, on data the model never influenced.

| Metric | Held-out test value |
|---|---|
| Precision | 30.99% |
| Recall | 62.28% |
| ROC-AUC | 0.7633 |
| Operating threshold | selected via cost-weighted optimization on validation |

Precision is modest by design, not by accident: a missed recoverable transaction costs the full transaction amount, while an unnecessary intervention attempt costs pennies (₹0.05–₹1.20). The threshold is tuned to minimize total financial loss, which means the model is deliberately biased toward over-attempting recovery rather than under-attempting it. A higher-precision, lower-recall model would look better on a metrics table and cost more money in practice.

## Coverage

- **Payment-degradation recovery** (primary, fully implemented): failure detected → root cause diagnosed → probability scored → intervention selected and executed → confirmed → audited.
- **Checkout-abandonment recovery**: covered through the same pipeline, not a separate system. Two dedicated failure codes (`CART_ABANDONED_TIMEOUT`, `CHECKOUT_SESSION_EXPIRED`) are diagnosed and routed through the identical cost-aware policy engine — the architecture generalizes directly to this failure class without modification.

## Compliance — enforced in code, not just documented

Before any outreach action executes, a compliance gate runs:

- **Time-window enforcement**: outreach outside 9AM–9PM IST is automatically downgraded to `ABORT`, with the reason recorded in `decision_trace`.
- **Opt-out enforcement**: customers on an opt-out list are never contacted; same downgrade-and-log behavior.

This is a real, functioning control — not a badge. What it does **not** yet do, and what production deployment would require before any real customer is contacted: DND/NDNC registry checks against TRAI's regulations, and DLT template registration for WhatsApp/SMS content. These are named explicitly rather than assumed away, because a system that silently lacks compliance controls is more dangerous than one that states its boundaries clearly.

## Stopping rules

- **Bounded retries**: maximum 3 attempts per transaction, enforced unconditionally.
- **Terminal-failure short-circuit**: `CARD_BLOCKED`, `STOLEN_CARD`, `ACCOUNT_CLOSED` abort immediately, regardless of attempt count — retrying a stolen or blocked instrument isn't just wasteful, it's the wrong action.
- **Cost-bounded action selection**: the highest-cost channel (voice) is reserved for transactions above ₹1,500, so intervention spend is never disproportionate to what it's protecting.

## Audit trail

Every processed transaction writes one immutable row to `recovery_audit`: transaction ID, amount, error code, action taken, full decision trace (diagnosis reasoning, uncapped), intervention cost, recovered amount, status, payment link, timestamp. Nothing is overwritten. Status transitions from predicted recovery to `CONFIRMED_RECOVERED` are driven by the same code path whether triggered by a live Razorpay webhook or the demo-mode scheduled confirmation described below — there is one status-transition method, not two.

## Known limitations, stated explicitly

- **Webhook confirmation in today's demo runs on a scheduled auto-confirmation simulation**, not a live Razorpay webhook. This is a deliberate choice to preserve Razorpay's test-mode payment-link quota (30/day) for this submission window — the simulation calls the exact same status-update method the real `payment_link.paid` handler uses, and that handler has been verified working against real webhook payloads in earlier testing. In production, this fires automatically and instantly on Razorpay's own event.
- **B2B receivables chasing is not implemented.** It requires a different trigger source — an invoice due-date crossing a threshold, rather than a payment-failure webhook — but the same detect → diagnose → gate → execute → audit architecture applies directly once that trigger exists.
- **LLM-based diagnosis reasoning was prototyped, not shipped.** A two-step design (LLM proposes a diagnosis and action, a deterministic gate has final authority) was built and tested in isolation. It was not integrated into the live pipeline for this submission, in favor of shipping a fully tested, stable rule-and-ML system rather than risking submission stability on an eleventh-hour addition.

## Tech stack

`Spring Boot 4` · `Java 21 (virtual threads)` · `Thymeleaf + HTMX` · `H2` · `Python FastAPI` · `scikit-learn` (calibrated gradient boosting) · `Razorpay Java SDK` (live Payment Links + HMAC-verified webhooks)

## Run it

```bash
# Terminal 1 — AI engine
cd ai-engine
pip install -r requirements.txt
python train_recovery_model.py     # trains + reports held-out metrics
python app.py                      # :8000

# Terminal 2 — Backend
cd backend
./mvnw spring-boot:run             # :8080
```

Open `http://localhost:8080/`, trigger a batch, watch it run.

H2 console: `http://localhost:8080/h2-console` — JDBC `jdbc:h2:mem:recoverdb;DB_CLOSE_DELAY=-1`, user `sa`, no password.

## API surface

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/failures/ingest-batch` | Batch process; empty body auto-generates synthetic transactions |
| `GET` | `/api/v1/dashboard/stats` / `/metrics` | Live aggregate metrics |
| `GET` | `/api/v1/dashboard/audit-rows` | Latest audit rows, HTMX-polled |
| `POST` | `/api/v1/razorpay/webhook` | Real Razorpay webhook, HMAC-verified, handles `payment.failed` and `payment_link.paid` |
| `GET` | `/transactions/{id}` | Full transaction detail and status timeline |

## What's next

- Live webhook confirmation as the default path, with the demo-mode simulation retained as a fallback for quota-constrained testing.
- The prototyped LLM-reasoning layer, integrated behind the existing deterministic gate.
- Full DND-registry and DLT template integration ahead of any production customer contact.
- Receivables chasing, using invoice due-dates as the trigger source into the same policy engine.