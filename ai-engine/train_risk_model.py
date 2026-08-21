"""
ReCover-AI :: Recovery Risk Model Trainer

Trains a scikit-learn LogisticRegression model that predicts the
probability a failed transaction will be successfully recovered, given
its amount, error code, and attempt count. This replaces the earlier
pure if/else policy with a real, trained model whose output
(`recovery_probability`) now feeds into the decision engine in app.py.

Historical labelled outcomes aren't available yet in this hackathon
build, so training data is synthesized from realistic priors (soft
technical failures recover far more often than hard card blocks; high
attempt counts and low amounts recover less often; etc). Swap
`generate_training_data()` for a real query against RecoveryAudit once
enough production outcomes exist.

Run:
    python train_risk_model.py
Produces:
    risk_model.joblib
"""

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, roc_auc_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from sklearn.compose import ColumnTransformer
import joblib

RANDOM_SEED = 42

ERROR_CODES = [
    "GATEWAY_TIMEOUT",
    "BAD_REQUEST_PAYMENT_TIMED_OUT",
    "INSUFFICIENT_FUNDS",
    "OTP_FAILED",
    "AUTHENTICATION_FAILED",
    "MANDATE_EXPIRED",
    "CARD_BLOCKED",
    "STOLEN_CARD",
    "ACCOUNT_CLOSED",
]

# Base recovery-probability priors per error code, before amount/attempt
# adjustments are applied. These encode the same domain intuition as the
# original rule-based engine (soft failures recover easily, hard blocks
# almost never do) but as continuous probabilities a model can learn from
# and generalize, rather than a hardcoded branch.
BASE_RECOVERY_RATE = {
    "GATEWAY_TIMEOUT": 0.93,
    "BAD_REQUEST_PAYMENT_TIMED_OUT": 0.90,
    "INSUFFICIENT_FUNDS": 0.55,
    "OTP_FAILED": 0.62,
    "AUTHENTICATION_FAILED": 0.58,
    "MANDATE_EXPIRED": 0.68,
    "CARD_BLOCKED": 0.04,
    "STOLEN_CARD": 0.01,
    "ACCOUNT_CLOSED": 0.02,
}


def generate_training_data(n_samples: int = 20000, seed: int = RANDOM_SEED) -> pd.DataFrame:
    rng = np.random.default_rng(seed)

    error_codes = rng.choice(ERROR_CODES, size=n_samples)
    amounts = rng.gamma(shape=2.0, scale=1800, size=n_samples).clip(50, 80000)
    attempts = rng.choice([0, 1, 2, 3], size=n_samples, p=[0.55, 0.25, 0.12, 0.08])

    probabilities = np.array([BASE_RECOVERY_RATE[code] for code in error_codes])

    # Higher amounts are modestly harder to recover (more customer hesitation).
    amount_penalty = np.clip((amounts - 1500) / 100000, 0, 0.15)
    # Each prior failed attempt erodes recovery probability.
    attempt_penalty = attempts * 0.12

    final_probabilities = np.clip(probabilities - amount_penalty - attempt_penalty, 0.01, 0.99)
    outcomes = rng.binomial(1, final_probabilities)

    return pd.DataFrame({
        "amount": amounts,
        "error_code": error_codes,
        "attempts_so_far": attempts,
        "recovered": outcomes,
    })


def build_pipeline() -> Pipeline:
    preprocessor = ColumnTransformer(transformers=[
        ("amount_scaled", StandardScaler(), ["amount", "attempts_so_far"]),
        ("error_code_ohe", OneHotEncoder(handle_unknown="ignore"), ["error_code"]),
    ])
    return Pipeline(steps=[
        ("preprocess", preprocessor),
        ("classifier", LogisticRegression(max_iter=1000, random_state=RANDOM_SEED)),
    ])


def main():
    df = generate_training_data()
    X = df[["amount", "error_code", "attempts_so_far"]]
    y = df["recovered"]

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=RANDOM_SEED, stratify=y
    )

    pipeline = build_pipeline()
    pipeline.fit(X_train, y_train)

    y_pred = pipeline.predict(X_test)
    y_proba = pipeline.predict_proba(X_test)[:, 1]

    accuracy = accuracy_score(y_test, y_pred)
    auc = roc_auc_score(y_test, y_proba)

    print(f"Validation accuracy: {accuracy:.4f}")
    print(f"Validation ROC-AUC:  {auc:.4f}")

    joblib.dump(pipeline, "risk_model.joblib")
    print("Saved trained model to risk_model.joblib")


if __name__ == "__main__":
    main()