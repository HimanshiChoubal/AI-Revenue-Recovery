import numpy as np
import pandas as pd
import json
import joblib
from sklearn.compose import ColumnTransformer
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder
from sklearn.model_selection import train_test_split
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.calibration import CalibratedClassifierCV
from sklearn.metrics import precision_score, recall_score, f1_score, roc_auc_score, confusion_matrix

ERROR_CODES = [
    "GATEWAY_TIMEOUT",
    "BAD_REQUEST_PAYMENT_TIMED_OUT",
    "INSUFFICIENT_FUNDS",
    "OTP_FAILED",
    "AUTHENTICATION_FAILED",
    "MANDATE_EXPIRED",
    "CARD_BLOCKED",
    "STOLEN_CARD",
]
INTERVENTION_COST = 0.35

ERROR_CODE_LOGIT = {
    "GATEWAY_TIMEOUT": 1.0,
    "BAD_REQUEST_PAYMENT_TIMED_OUT": 0.8,
    "INSUFFICIENT_FUNDS": -0.1,
    "OTP_FAILED": 0.2,
    "AUTHENTICATION_FAILED": -0.3,
    "MANDATE_EXPIRED": 0.4,
    "CARD_BLOCKED": -4.0,
    "STOLEN_CARD": -4.5,
}


import numpy as np
import pandas as pd

def generate_realistic_data(n_samples=60000):
    np.random.seed(42)

    # 1. Base features
    amounts = np.random.lognormal(mean=7.3, sigma=0.80, size=n_samples)
    amounts = np.round(np.clip(amounts, 100, 60000), 2)

    # Ensure probabilities match length and normalize
    p_dist = np.array([0.28, 0.12, 0.22, 0.10, 0.08, 0.10, 0.07, 0.03])
    p_dist = p_dist / p_dist.sum()

    error_code = np.random.choice(ERROR_CODES, size=n_samples, p=p_dist)
    attempts_so_far = np.random.choice([0, 1, 2, 3], size=n_samples, p=[0.55, 0.25, 0.12, 0.08])
    hour_of_day = np.random.randint(0, 24, size=n_samples)
    customer_history_recovery_rate = np.random.beta(a=3.0, b=2.2, size=n_samples)

    # 2. Vectorized logit computation
    error_code_effect = np.vectorize(ERROR_CODE_LOGIT.get)(error_code).astype(float)
    is_daytime = ((hour_of_day >= 9) & (hour_of_day <= 21)).astype(float)

    z = (
        -1.9
        + error_code_effect
        + (customer_history_recovery_rate * 1.5)
        - (attempts_so_far * 0.85)
        + (is_daytime * 0.45)
        - (np.log(amounts) * 0.12)
    )
    probs = 1 / (1 + np.exp(-z))

    # 3. Enforce deterministic zero-probability business logic
    hard_abort_mask = (attempts_so_far >= 3) | np.isin(error_code, ["CARD_BLOCKED", "STOLEN_CARD"])
    probs[hard_abort_mask] = 0.0

    recovery_successful = np.random.binomial(1, probs)

    df = pd.DataFrame({
        'amount': amounts,
        'error_code': error_code,
        'attempts_so_far': attempts_so_far,
        'hour_of_day': hour_of_day,
        'customer_history_recovery_rate': customer_history_recovery_rate,
        'recovery_successful': recovery_successful,
    })

    df.to_csv('synthetic_payment_failures.csv', index=False)
    print(df.head(10))
    print(df['recovery_successful'].value_counts(normalize=True))
    return df


def build_pipeline():
    preprocessor = ColumnTransformer(transformers=[
        ('error_code_ohe', OneHotEncoder(handle_unknown='ignore'), ['error_code']),
    ], remainder='passthrough')

    base_clf = HistGradientBoostingClassifier(
        random_state=42,
        max_iter=120,
        learning_rate=0.08,
        min_samples_leaf=25,
        l2_regularization=1.5,
    )

    return Pipeline(steps=[
        ('preprocess', preprocessor),
        ('classifier', base_clf),
    ])


def train_and_evaluate():
    df = generate_realistic_data()
    X = df.drop(columns=['recovery_successful'])
    y = df['recovery_successful']

    # Strict split: 60% Train, 20% Val, 20% Held-Out Test
    X_train_val, X_test, y_train_val, y_test = train_test_split(
        X, y, test_size=0.20, random_state=42, stratify=y)
    X_train, X_val, y_train, y_val = train_test_split(
        X_train_val, y_train_val, test_size=0.25, random_state=42, stratify=y_train_val)

    # Train calibrated boosting model
    pipeline = build_pipeline()
    model = CalibratedClassifierCV(pipeline, method='isotonic', cv=5)
    model.fit(X_train, y_train)

    # Find optimal cost threshold on VALIDATION set.
    # Predicted-positive (>= threshold) means "attempt recovery":
    #   - if the attempt would have succeeded (TP), it earns back the
    #     transaction amount minus the intervention cost — no loss to book.
    #   - if the attempt would have failed anyway (FP), the only loss is
    #     the wasted intervention cost.
    #   - predicted-negative (< threshold) means "don't attempt / abort":
    #     if the transaction would actually have recovered (FN), the full
    #     transaction amount is a missed-revenue opportunity cost.
    val_probs = model.predict_proba(X_val)[:, 1]

    thresholds = [0.35, 0.50, 0.65, 0.75]
    threshold_tradeoffs = []
    best_thresh, min_loss = 0.5, float('inf')

    for t in np.linspace(0.2, 0.85, 66):
        preds = (val_probs >= t).astype(int)
        fp = ((preds == 1) & (y_val == 0)).sum()
        fn_loss = X_val.loc[(preds == 0) & (y_val == 1), 'amount'].sum()
        total_loss = (fp * INTERVENTION_COST) + fn_loss

        if total_loss < min_loss:
            min_loss = total_loss
            best_thresh = t

    for t in thresholds:
        preds = (val_probs >= t).astype(int)
        threshold_tradeoffs.append({
            "threshold": float(t),
            "precision": float(precision_score(y_val, preds, zero_division=0)),
            "recall": float(recall_score(y_val, preds)),
            "f1": float(f1_score(y_val, preds)),
        })

    # Run on UNTOUCHED Held-Out Test Set
    test_probs = model.predict_proba(X_test)[:, 1]
    test_preds = (test_probs >= best_thresh).astype(int)

    precision = float(precision_score(y_test, test_preds, zero_division=0))
    recall = float(recall_score(y_test, test_preds))
    f1 = float(f1_score(y_test, test_preds))
    auc = float(roc_auc_score(y_test, test_probs))
    tn, fp, fn, tp = confusion_matrix(y_test, test_preds).ravel()

    intervention_cost_total = float((tp + fp) * INTERVENTION_COST)
    gross_revenue_recovered = float(X_test.loc[(test_preds == 1) & (y_test == 1), 'amount'].sum())
    missed_revenue_fn = float(X_test.loc[(test_preds == 0) & (y_test == 1), 'amount'].sum())
    net_benefit = gross_revenue_recovered - intervention_cost_total - missed_revenue_fn

    metrics_payload = {
        "precision": precision,
        "recall": recall,
        "f1_score": f1,
        "roc_auc": auc,
        "operating_threshold": float(round(best_thresh, 3)),
        "confusion_matrix": {"TP": int(tp), "FP": int(fp), "TN": int(tn), "FN": int(fn)},
        "financial_impact": {
            "gross_revenue_recovered_inr": gross_revenue_recovered,
            "intervention_cost_total_inr": intervention_cost_total,
            "missed_revenue_fn_inr": missed_revenue_fn,
            "net_economic_benefit_inr": net_benefit,
        },
        "threshold_tradeoffs": threshold_tradeoffs,
    }

    joblib.dump({'model': model, 'threshold': best_thresh}, 'payment_recovery_model.joblib')
    with open('payment_recovery_metrics.json', 'w') as f:
        json.dump(metrics_payload, f, indent=2)

    print(f"Model trained successfully. Held-out Precision: {precision*100:.2f}%, "
          f"Recall: {recall*100:.2f}%, AUC: {auc:.4f}")


if __name__ == '__main__':
    train_and_evaluate()