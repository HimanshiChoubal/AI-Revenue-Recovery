"""
ReCover-AI :: Diagnostic & Policy Engine
Razorpay AI Revenue Recovery Hackathon Track

POST /api/v1/diagnose-and-plan
"""

import random
from typing import Optional

from fastapi import FastAPI
from pydantic import BaseModel, Field

app = FastAPI(title="ReCover-AI Diagnostic & Policy Engine", version="1.0.0")

# --------------------------------------------------------------------------
# Error code taxonomy
# --------------------------------------------------------------------------

ABORT_ERROR_CODES = {"CARD_BLOCKED", "STOLEN_CARD", "ACCOUNT_CLOSED"}

SOFT_TECHNICAL_ERRORS = {
    "GATEWAY_TIMEOUT",
    "BAD_REQUEST_PAYMENT_TIMED_OUT",
    "NETWORK_ERROR",
    "SERVER_ERROR",
    "GATEWAY_ERROR",
}

USER_FRICTION_ERRORS = {
    "INSUFFICIENT_FUNDS",
    "OTP_FAILED",
    "AUTHENTICATION_FAILED",
    "PAYMENT_DECLINED",
    "MANDATE_EXPIRED" # Added to handle subscription tracks!
}

MAX_ATTEMPTS = 3

COST_AUTO_RETRY = 0.05
COST_HINGLISH_VOICE = 1.20
COST_WHATSAPP_LINK = 0.35
HINGLISH_VOICE_AMOUNT_THRESHOLD = 1500


# --------------------------------------------------------------------------
# Schemas (Using aliases to PERFECTLY match Spring Boot's camelCase JSON)
# --------------------------------------------------------------------------

class DiagnoseRequest(BaseModel):
    # Aliases map Java's 'transactionId' to Python's 'transaction_id' automatically
    transaction_id: str = Field(alias="transactionId")
    amount: float
    error_code: str = Field(alias="errorCode")
    customer_name: str = Field(alias="customerName")
    customer_phone: str = Field(alias="customerPhone")
    attempts_so_far: int = Field(alias="attemptsSoFar", ge=0)


class DiagnoseResponse(BaseModel):
    action: str
    reason: str
    # Aliases map Python's 'cost_inr' back to Java's 'costInr' in the JSON response
    cost_inr: float = Field(alias="costInr")
    outreach_script: Optional[str] = Field(default=None, alias="outreachScript")
    target_rail: str = Field(alias="targetRail")
    
    class Config:
        populate_by_name = True


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

    # --- Bounded stopping rules --------------------------------------
    if payload.attempts_so_far >= MAX_ATTEMPTS:
        return DiagnoseResponse(
            action="ABORT",
            reason=f"Max retry attempts reached ({payload.attempts_so_far}/{MAX_ATTEMPTS}). "
                   f"Halting automated recovery to avoid customer fatigue / cost overrun.",
            cost_inr=0.0,
            outreach_script=None,
            target_rail="NONE",
        )

    if error_code in ABORT_ERROR_CODES:
        return DiagnoseResponse(
            action="ABORT",
            reason=f"Error code '{error_code}' is terminal/unrecoverable. "
                   f"Further outreach is unsafe or futile.",
            cost_inr=0.0,
            outreach_script=None,
            target_rail="NONE",
        )

    # --- Diagnostic & policy engine ------------------------------------
    if error_code in SOFT_TECHNICAL_ERRORS:
        return DiagnoseResponse(
            action="AUTO_RETRY_FALLBACK_GATEWAY",
            reason=f"'{error_code}' is a soft technical/infra failure. "
                   f"Safe to silently retry on an alternate gateway without customer friction.",
            cost_inr=COST_AUTO_RETRY,
            outreach_script=None,
            target_rail="BACKUP_GATEWAY",
        )

    if error_code in USER_FRICTION_ERRORS and payload.amount >= HINGLISH_VOICE_AMOUNT_THRESHOLD:
        script = build_hinglish_voice_script(payload.customer_name, payload.amount, payload.transaction_id)
        return DiagnoseResponse(
            action="HINGLISH_VOICE_OUTREACH",
            reason=f"'{error_code}' indicates user-side friction on a high-value transaction "
                   f"(₹{payload.amount:,.2f} ≥ ₹{HINGLISH_VOICE_AMOUNT_THRESHOLD}). "
                   f"Personal voice outreach in Hinglish maximizes recovery probability.",
            cost_inr=COST_HINGLISH_VOICE,
            outreach_script=script,
            target_rail=f"IVR_VOICE_CALL::{payload.customer_phone}",
        )

    # Everything else (including low-value user friction and mandate failures)
    message = build_whatsapp_smart_link_copy(payload.customer_name, payload.amount, payload.transaction_id)
    return DiagnoseResponse(
        action="WHATSAPP_SMART_LINK",
        reason=f"'{error_code}' does not warrant a costlier voice call at this amount/attempt stage. "
               f"A 1-click WhatsApp smart link is the most cost-efficient recovery path.",
        cost_inr=COST_WHATSAPP_LINK,
        outreach_script=message,
        target_rail=f"WHATSAPP::{payload.customer_phone}",
    )


@app.get("/health")
def health():
    return {"status": "ok", "service": "recover-ai-diagnostic-engine"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app:app", host="0.0.0.0", port=8000, reload=True)