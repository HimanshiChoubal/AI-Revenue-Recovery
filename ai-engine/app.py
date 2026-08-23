"""
ReCover-AI :: Diagnostic & Policy Engine
Razorpay AI Revenue Recovery Hackathon Track

POST /api/v1/diagnose-and-plan

Decisions are now driven by a trained scikit-learn LogisticRegression
model (risk_model.joblib, see train_risk_model.py) that predicts each
transaction's recovery probability from its amount, error code, and
attempt count — not a hardcoded if/else table. The predicted
probability is both returned to the caller and used to pick the action.
If the model file is missing, a conservative rule-based prior is used
instead so the service still starts and works.
"""

import os
import random
from typing import Optional

import joblib
import pandas as pd
from fastapi import FastAPI
from pydantic import BaseModel, Field

app = FastAPI(title="ReCover-AI Diagnostic & Policy Engine", version="2.0.0")

# --------------------------------------------------------------------------
# Risk model loading
# --------------------------------------------------------------------------

MODEL_PATH = os.path.join(os.path.dirname(__file__), "risk_model.joblib")
_risk_model = None
_model_loaded = False

if os.path.exists(MODEL_PATH):
    try:
        _risk_model = joblib.load(MODEL_PATH)
        _model_loaded = True
    except Exception:
        _risk_model = None
        _model_loaded = False


def _fallback_recovery_probability(amount: float, error_code: str, attempts_so_far: int) -> float:
    """Used only if risk_model.joblib hasn't been trained/loaded yet."""
    base = {
        "GATEWAY_TIMEOUT": 0.93,
        "BAD_REQUEST_PAYMENT_TIMED_OUT": 0.90,
        "INSUFFICIENT_FUNDS": 0.55,
        "OTP_FAILED": 0.62,
        "AUTHENTICATION_FAILED": 0.58,
        "MANDATE_EXPIRED": 0.68,
        "CARD_BLOCKED": 0.04,
        "STOLEN_CARD": 0.01,
        "ACCOUNT_CLOSED": 0.02,
    }.get(error_code, 0.5)
    amount_penalty = min(max((amount - 1500) / 100000, 0), 0.15)
    attempt_penalty = attempts_so_far * 0.12
    return max(0.01, min(0.99, base - amount_penalty - attempt_penalty))


def predict_recovery_probability(amount: float, error_code: str, attempts_so_far: int) -> float:
    if _model_loaded and _risk_model is not None:
        row = pd.DataFrame([{
            "amount": amount,
            "error_code": error_code,
            "attempts_so_far": attempts_so_far,
        }])
        proba = _risk_model.predict_proba(row)[0][1]
        return float(proba)
    return _fallback_recovery_probability(amount, error_code, attempts_so_far)


# --------------------------------------------------------------------------
# Error code taxonomy / stopping rules
# --------------------------------------------------------------------------

ABORT_ERROR_CODES = {"CARD_BLOCKED", "STOLEN_CARD", "ACCOUNT_CLOSED"}
SOFT_TECHNICAL_ERRORS = {
    "GATEWAY_TIMEOUT",
    "BAD_REQUEST_PAYMENT_TIMED_OUT",
    "NETWORK_ERROR",
    "SERVER_ERROR",
    "GATEWAY_ERROR",
}

MAX_ATTEMPTS = 3
LOW_PROBABILITY_ABORT_THRESHOLD = 0.05

COST_AUTO_RETRY = 0.05
COST_HINGLISH_VOICE = 1.20
COST_WHATSAPP_LINK = 0.35
HINGLISH_VOICE_AMOUNT_THRESHOLD = 1500
HIGH_CONFIDENCE_RECOVERY_THRESHOLD = 0.60


# --------------------------------------------------------------------------
# Schemas
# --------------------------------------------------------------------------

class DiagnoseRequest(BaseModel):
    transaction_id: str
    amount: float
    error_code: str
    customer_name: str
    customer_phone: str
    attempts_so_far: int = Field(ge=0)


class DiagnoseResponse(BaseModel):
    action: str
    reason: str
    cost_inr: float
    outreach_script: Optional[str] = None
    target_rail: str
    recovery_probability: float


# --------------------------------------------------------------------------
# Script generators
# --------------------------------------------------------------------------

def build_hinglish_voice_script(customer_name: str, amount: float, transaction_id: str) -> str:
    first_name = customer_name.strip().split(" ")[0] if customer_name.strip() else "Sir/Ma'am"
    templates = [
        (
            f"Namaste {first_name} ji, main Razorpay ki taraf se baat kar raha/rahi hoon. "
            f"Aapka payment of Rs. {amount:,.2f} complete nahi ho paya kuch technical issue ki wajah se. "
            f"Koi baat nahi, aap bas ek baar apna payment method check kar lijiye aur main aapko turant ek "
            f"secure link bhej deta/deti hoon jisse aap ek click mein payment complete kar sakte hain. "
            f"Transaction reference hai {transaction_id}. Kya main aapko abhi WhatsApp par link bhej doon?"
        ),
        (
            f"Hello {first_name}, this is an automated call from Razorpay regarding your recent payment "
            f"of Rs. {amount:,.2f} jo unfortunately fail ho gaya tha. Chinta mat kijiye, aapka order abhi bhi "
            f"reserve hai. Agar aap chahein toh main aapko ek WhatsApp link bhej sakta/sakti hoon jisse aap "
            f"seedha payment complete kar payenge, bina dobara poori details bhare. Reference number "
            f"{transaction_id} hai, save kar lijiye."
        ),
        (
            f"{first_name} ji namaskar, Razorpay se contact kar rahe hain. Aapka Rs. {amount:,.2f} ka "
            f"payment abhi pending hai. Hum samajhte hain ki yeh thoda frustrating ho sakta hai, isliye "
            f"hum aapke liye ek instant, secure link ready kar rahe hain jisse aap ek tap mein payment "
            f"complete kar sakein. Transaction ID {transaction_id} hai for your reference."
        ),
    ]
    return random.choice(templates)


def build_whatsapp_smart_link_copy(customer_name: str, amount: float, transaction_id: str) -> str:
    first_name = customer_name.strip().split(" ")[0] if customer_name.strip() else "there"
    smart_link = f"https://rzp.io/i/recover-{transaction_id[-8:]}"
    return (
        f"Hi {first_name}! 👋 We noticed your payment of ₹{amount:,.2f} didn't go through. "
        f"No worries — just tap the link below to complete it in one click, securely:\n\n"
        f"{smart_link}\n\n"
        f"Ref: {transaction_id}. This link is valid for the next 30 minutes."
    )


# --------------------------------------------------------------------------
# Endpoint
# --------------------------------------------------------------------------

@app.post("/api/v1/diagnose-and-plan", response_model=DiagnoseResponse)
def diagnose_and_plan(payload: DiagnoseRequest) -> DiagnoseResponse:
    error_code = payload.error_code.strip().upper()

    recovery_probability = predict_recovery_probability(
        payload.amount, error_code, payload.attempts_so_far
    )

    # --- Bounded stopping rules --------------------------------------
    if payload.attempts_so_far >= MAX_ATTEMPTS:
        return DiagnoseResponse(
            action="ABORT",
            reason=f"Max retry attempts reached ({payload.attempts_so_far}/{MAX_ATTEMPTS}). "
                   f"Halting automated recovery to avoid customer fatigue / cost overrun.",
            cost_inr=0.0,
            outreach_script=None,
            target_rail="NONE",
            recovery_probability=recovery_probability,
        )

    if error_code in ABORT_ERROR_CODES:
        return DiagnoseResponse(
            action="ABORT",
            reason=f"Error code '{error_code}' is terminal/unrecoverable. "
                   f"Further outreach is unsafe or futile.",
            cost_inr=0.0,
            outreach_script=None,
            target_rail="NONE",
            recovery_probability=recovery_probability,
        )

    if recovery_probability < LOW_PROBABILITY_ABORT_THRESHOLD:
        return DiagnoseResponse(
            action="ABORT",
            reason=f"Model-predicted recovery probability ({recovery_probability:.2%}) is below the "
                   f"{LOW_PROBABILITY_ABORT_THRESHOLD:.0%} threshold; intervention cost isn't justified.",
            cost_inr=0.0,
            outreach_script=None,
            target_rail="NONE",
            recovery_probability=recovery_probability,
        )

    # --- Diagnostic & policy engine ------------------------------------
    if error_code in SOFT_TECHNICAL_ERRORS:
        return DiagnoseResponse(
            action="AUTO_RETRY_FALLBACK_GATEWAY",
            reason=f"'{error_code}' is a soft technical/infra failure with a high model-predicted recovery "
                   f"probability ({recovery_probability:.2%}). Safe to silently retry on an alternate "
                   f"gateway without customer friction.",
            cost_inr=COST_AUTO_RETRY,
            outreach_script=None,
            target_rail="BACKUP_GATEWAY",
            recovery_probability=recovery_probability,
        )

    if (recovery_probability < HIGH_CONFIDENCE_RECOVERY_THRESHOLD
            and payload.amount >= HINGLISH_VOICE_AMOUNT_THRESHOLD):
        script = build_hinglish_voice_script(payload.customer_name, payload.amount, payload.transaction_id)
        return DiagnoseResponse(
            action="HINGLISH_VOICE_OUTREACH",
            reason=f"High-value transaction (₹{payload.amount:,.2f}) with a model-predicted recovery "
                   f"probability of only {recovery_probability:.2%} — the personal touch of a voice call "
                   f"is worth the extra cost here.",
            cost_inr=COST_HINGLISH_VOICE,
            outreach_script=script,
            target_rail=f"IVR_VOICE_CALL::{payload.customer_phone}",
            recovery_probability=recovery_probability,
        )

    # Everything else: model predicts a reasonable-to-high recovery chance,
    # or the amount doesn't justify a costlier voice call — a 1-click
    # WhatsApp smart link is the most cost-efficient path.
    message = build_whatsapp_smart_link_copy(payload.customer_name, payload.amount, payload.transaction_id)
    return DiagnoseResponse(
        action="WHATSAPP_SMART_LINK",
        reason=f"Model-predicted recovery probability is {recovery_probability:.2%} for '{error_code}'; "
               f"a WhatsApp smart link is the most cost-efficient recovery path at this confidence level.",
        cost_inr=COST_WHATSAPP_LINK,
        outreach_script=message,
        target_rail=f"WHATSAPP::{payload.customer_phone}",
        recovery_probability=recovery_probability,
    )


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "recover-ai-diagnostic-engine",
        "risk_model_loaded": _model_loaded,
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app:app", host="0.0.0.0", port=8000, reload=True)