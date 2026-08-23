import json
import os
import logging
import joblib
import pandas as pd
from datetime import datetime
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

logger = logging.getLogger(__name__)

app = FastAPI(title="ReCover-AI Diagnostic & Recovery Planning Engine")


def load_artifacts():
    """
    Reusable model-loading boilerplate. If a trained payment-recovery
    model (payment_recovery_model.joblib + payment_recovery_metrics.json)
    exists, it's loaded and used to derive confidence_score. Until then,
    diagnose_and_plan() falls back to a deterministic rule-based policy,
    so the service works either way.
    """
    try:
        data = joblib.load('payment_recovery_model.joblib')
        with open('payment_recovery_metrics.json', 'r') as f:
            metrics = json.load(f)
        return data['model'], data.get('threshold', 0.5), metrics
    except Exception:
        return None, 0.5, {}


model, threshold, metrics = load_artifacts()


class PaymentFailureEvent(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    transaction_id: str
    amount: float
    error_code: str
    attempts_so_far: int = Field(0, ge=0)
    customer_history_recovery_rate: float = Field(0.5, ge=0.0, le=1.0)


class RecoveryPlan(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    diagnosis: str
    recommended_action: str
    estimated_cost: float
    confidence_score: float


@app.get("/health")
def health():
    return {
        "status": "UP",
        "model_loaded": model is not None,
        "threshold": threshold,
    }


@app.get("/api/v1/model-metrics")
def get_metrics():
    if not metrics:
        raise HTTPException(status_code=503, detail="Metrics not generated. Train a payment recovery model first.")
    return metrics


# --------------------------------------------------------------------------
# Domain-specific policy: error-code taxonomy, bounded stopping rules, cost table
# --------------------------------------------------------------------------

ABORT_ERROR_CODES = {"CARD_BLOCKED", "STOLEN_CARD", "ACCOUNT_CLOSED"}
SOFT_TECHNICAL_ERRORS = {"GATEWAY_TIMEOUT", "BAD_REQUEST_PAYMENT_TIMED_OUT", "NETWORK_ERROR", "SERVER_ERROR"}
USER_FRICTION_ERRORS = {"INSUFFICIENT_FUNDS", "OTP_FAILED", "AUTHENTICATION_FAILED", "MANDATE_EXPIRED"}

MAX_ATTEMPTS = 3
HIGH_VALUE_THRESHOLD = 1500.0

COST_AUTO_RETRY = 0.05
COST_WHATSAPP_LINK = 0.35
COST_VOICE_OUTREACH = 1.20
COST_ABORT = 0.0


def diagnose(error_code: str) -> str:
    if error_code in SOFT_TECHNICAL_ERRORS:
        return "Soft technical/infra failure — gateway-side issue, not customer-side."
    if error_code in USER_FRICTION_ERRORS:
        return "User-side friction — customer action likely needed to complete payment."
    if error_code in ABORT_ERROR_CODES:
        return "Terminal instrument failure — this payment method cannot be retried."
    return "Unrecognized error code — treated as user-side friction by default."


def plan_action(event: PaymentFailureEvent) -> RecoveryPlan:
    error_code = event.error_code.strip().upper()
    diagnosis = diagnose(error_code)

    if event.attempts_so_far >= MAX_ATTEMPTS:
        return RecoveryPlan(
            diagnosis=diagnosis,
            recommended_action="ABORT",
            estimated_cost=COST_ABORT,
            confidence_score=0.95,
        )

    if error_code in ABORT_ERROR_CODES:
        return RecoveryPlan(
            diagnosis=diagnosis,
            recommended_action="ABORT",
            estimated_cost=COST_ABORT,
            confidence_score=0.97,
        )

    if error_code in SOFT_TECHNICAL_ERRORS:
        return RecoveryPlan(
            diagnosis=diagnosis,
            recommended_action="AUTO_RETRY",
            estimated_cost=COST_AUTO_RETRY,
            confidence_score=0.90,
        )

    if error_code in USER_FRICTION_ERRORS and event.amount >= HIGH_VALUE_THRESHOLD:
        return RecoveryPlan(
            diagnosis=diagnosis,
            recommended_action="VOICE_OUTREACH",
            estimated_cost=COST_VOICE_OUTREACH,
            confidence_score=0.75,
        )

    return RecoveryPlan(
        diagnosis=diagnosis,
        recommended_action="WHATSAPP_LINK",
        estimated_cost=COST_WHATSAPP_LINK,
        confidence_score=0.70,
    )


def plan_action_with_model(event: PaymentFailureEvent) -> RecoveryPlan:
    """
    If a trained payment-recovery model is available, use it to refine
    confidence_score (e.g. predicted recovery probability) around the
    same rule-based action choice. Falls back to plan_action() untouched
    if the model doesn't load or scoring fails for any reason.
    """
    base_plan = plan_action(event)
    if model is None:
        return base_plan

    try:
        df = pd.DataFrame([{
            'amount': float(event.amount),
            'error_code': event.error_code,
            'attempts_so_far': int(event.attempts_so_far),
            'hour_of_day': datetime.now().hour,
            'customer_history_recovery_rate': float(event.customer_history_recovery_rate),
        }])
        prob = float(model.predict_proba(df)[0, 1])
        return RecoveryPlan(
            diagnosis=base_plan.diagnosis,
            recommended_action=base_plan.recommended_action,
            estimated_cost=base_plan.estimated_cost,
            confidence_score=round(prob, 4),
        )
    except Exception as e:
        logger.warning(f"Recovery model scoring failed, falling back to rule-based plan: {e}")
        return base_plan


@app.post("/api/v1/diagnose-and-plan", response_model=RecoveryPlan)
def diagnose_and_plan(event: PaymentFailureEvent):
    return plan_action_with_model(event)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app:app", host="127.0.0.1", port=8000, reload=True)