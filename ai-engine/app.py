import json
import os
import joblib
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

app = FastAPI(title="AI Risk Manager Inference Server")

def load_artifacts():
    try:
        data = joblib.load('risk_model.joblib')
        with open('model_metrics.json', 'r') as f:
            metrics = json.load(f)
        return data['model'], data['threshold'], metrics
    except Exception:
        return None, 0.5, {}

model, threshold, metrics = load_artifacts()

class ReturnEvent(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)
    
    transaction_id: str
    order_amount: float
    account_age_days: int
    past_return_rate: float
    days_since_delivery: int
    discount_pct: float

class RiskDecision(BaseModel):
    action: str
    risk_score: float
    reason: str
    intervention_cost: float
    served_by_ai: bool

@app.get("/health")
def health():
    return {
        "status": "UP",
        "model_loaded": model is not None,
        "threshold": threshold
    }

@app.get("/api/v1/model-metrics")
def get_metrics():
    if not metrics:
        raise HTTPException(status_code=503, detail="Metrics not generated. Run train_risk_model.py")
    return metrics

@app.post("/api/v1/score-return", response_model=RiskDecision)
@app.post("/api/v1/diagnose-and-plan", response_model=RiskDecision)
def score_return(event: ReturnEvent):
    if model is None:
        raise HTTPException(status_code=503, detail="Model unavailable. Ensure risk_model.joblib exists.")

    df = pd.DataFrame([{
        'order_amount': float(event.order_amount),
        'account_age_days': int(event.account_age_days),
        'past_return_rate': float(event.past_return_rate),
        'days_since_delivery': int(event.days_since_delivery),
        'discount_pct': float(event.discount_pct)
    }])

    prob = float(model.predict_proba(df)[0, 1])

    if prob >= threshold:
        return RiskDecision(
            action="MANUAL_REVIEW",
            risk_score=round(prob, 4),
            reason=f"Model risk score ({prob:.3f}) exceeded cost-optimal threshold ({threshold:.3f})",
            intervention_cost=500.0,
            served_by_ai=True
        )
    else:
        return RiskDecision(
            action="AUTO_REFUND",
            risk_score=round(prob, 4),
            reason=f"Low risk score ({prob:.3f}) below threshold ({threshold:.3f}) — Automated Refund",
            intervention_cost=0.0,
            served_by_ai=True
        )