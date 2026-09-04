"""
ReCover-AI :: Synthetic Failure Generator
Creates a realistic batch of Indian payment failures matching typical
Razorpay failure distributions and streams them to the ingestion API.

Distribution (of 1000 total):
    40% soft gateway drops     -> GATEWAY_TIMEOUT / BAD_REQUEST_PAYMENT_TIMED_OUT
    35% user friction/UPI      -> INSUFFICIENT_FUNDS / OTP_FAILED / AUTHENTICATION_FAILED
    15% mandate failures       -> MANDATE_EXPIRED
    10% hard card blocks       -> CARD_BLOCKED / STOLEN_CARD / ACCOUNT_CLOSED
"""

import json
import random
import time
import uuid
from datetime import datetime, timedelta

import requests

INGEST_URL = "http://localhost:8080/api/v1/failures/ingest-batch"
TOTAL_RECORDS = 1000
BATCH_SIZE = 50
REQUEST_TIMEOUT = 10

FIRST_NAMES = [
    "Aarav", "Vivaan", "Aditya", "Vihaan", "Arjun", "Sai", "Krishna", "Ishaan",
    "Rohan", "Kabir", "Ananya", "Diya", "Saanvi", "Aadhya", "Kiara", "Myra",
    "Priya", "Neha", "Pooja", "Sneha", "Ravi", "Suresh", "Manoj", "Deepak",
    "Anjali", "Kavya", "Riya", "Nisha", "Amit", "Rajesh", "Vikram", "Sanjay",
]
LAST_NAMES = [
    "Sharma", "Verma", "Gupta", "Iyer", "Nair", "Reddy", "Patel", "Mehta",
    "Singh", "Kumar", "Rao", "Joshi", "Chopra", "Malhotra", "Bose", "Pillai",
    "Agarwal", "Kapoor", "Desai", "Menon",
]

CITIES = [
    "Mumbai", "Delhi", "Bengaluru", "Pune", "Hyderabad", "Chennai",
    "Kolkata", "Ahmedabad", "Jaipur", "Lucknow", "Surat", "Indore",
]

MERCHANT_CATEGORIES = [
    "e-commerce", "food-delivery", "edtech", "saas-subscription",
    "insurance-premium", "utility-bill", "ride-hailing", "otc-pharmacy",
]

SOFT_GATEWAY_CODES = ["GATEWAY_TIMEOUT", "BAD_REQUEST_PAYMENT_TIMED_OUT"]
USER_FRICTION_CODES = ["INSUFFICIENT_FUNDS", "OTP_FAILED", "AUTHENTICATION_FAILED"]
MANDATE_CODES = ["MANDATE_EXPIRED"]
HARD_BLOCK_CODES = ["CARD_BLOCKED", "STOLEN_CARD", "ACCOUNT_CLOSED"]

PAYMENT_RAILS = ["UPI", "CARD", "NETBANKING", "WALLET", "EMI"]
CHECKOUT_ABANDONMENT_CODES = ["CART_ABANDONED_TIMEOUT", "CHECKOUT_SESSION_EXPIRED"]

def random_phone() -> str:
    return f"+91{random.choice('6789')}{random.randint(10**8, 10**9 - 1)}"


def random_amount(error_code: str) -> float:
    if error_code in MANDATE_CODES:
        return round(random.choice([99, 199, 299, 499, 999, 1499, 1999]) + random.uniform(0, 0.99), 2)
    if error_code in CHECKOUT_ABANDONMENT_CODES:
        return round(random.uniform(200, 8000), 2)
    if error_code in USER_FRICTION_CODES:
        return round(random.uniform(150, 25000), 2)
    if error_code in HARD_BLOCK_CODES:
        return round(random.uniform(500, 75000), 2)
    return round(random.uniform(99, 15000), 2)


def pick_error_code() -> str:
    """Sample an error code according to the target failure distribution."""
    bucket = random.choices(
        population=["soft_gateway", "user_friction", "mandate", "hard_block", "checkout_abandonment"],
        weights=[37, 32, 15, 10, 6],
        k=1,
    )[0]
    if bucket == "soft_gateway":
        return random.choice(SOFT_GATEWAY_CODES)
    if bucket == "user_friction":
        return random.choice(USER_FRICTION_CODES)
    if bucket == "mandate":
        return random.choice(MANDATE_CODES)
    if bucket == "checkout_abandonment":
        return random.choice(CHECKOUT_ABANDONMENT_CODES)
    return random.choice(HARD_BLOCK_CODES)


def generate_failure_record() -> dict:
    error_code = pick_error_code()
    first_name = random.choice(FIRST_NAMES)
    last_name = random.choice(LAST_NAMES)

    return {
        "transactionId": f"pay_{uuid.uuid4().hex[:14]}",
        "amount": random_amount(error_code),
        "errorCode": error_code,
        "customerName": f"{first_name} {last_name}",
        "customerPhone": random_phone(),
        "attemptsSoFar": random.choices([0, 1, 2, 3], weights=[55, 25, 12, 8])[0],
    }


def stream_batches(total: int = TOTAL_RECORDS, batch_size: int = BATCH_SIZE) -> None:
    sent = 0
    failed_batches = 0

    while sent < total:
        current_batch_size = min(batch_size, total - sent)
        batch = [generate_failure_record() for _ in range(current_batch_size)]

        try:
            response = requests.post(
                INGEST_URL,
                json=batch,
                headers={"Content-Type": "application/json"},
                timeout=REQUEST_TIMEOUT
            )
            response.raise_for_status()
            sent += current_batch_size
            print(f"[OK] Sent {sent}/{total} records (batch of {current_batch_size}) "
                  f"-> status {response.status_code}")
        except requests.exceptions.RequestException as exc:
            failed_batches += 1
            print(f"[ERROR] Failed to send batch ({sent}/{total} sent so far): {exc}")
            if failed_batches >= 5:
                print("[ABORT] Too many consecutive failures. Is the ingest server running "
                      f"at {INGEST_URL}?")
                break

        time.sleep(0.05)

    print(f"\nDone. {sent}/{total} records streamed to {INGEST_URL}.")


if __name__ == "__main__":
    stream_batches()