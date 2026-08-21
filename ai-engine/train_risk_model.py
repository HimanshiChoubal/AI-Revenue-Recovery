import numpy as np
import pandas as pd
import json
import joblib
from sklearn.model_selection import train_test_split
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.calibration import CalibratedClassifierCV
from sklearn.metrics import precision_score, recall_score, f1_score, roc_auc_score, confusion_matrix

def generate_realistic_data(n_samples=60000):
    np.random.seed(42)
    
    # 1. Base transaction features
    amounts = np.random.lognormal(mean=7.5, sigma=0.85, size=n_samples)
    amounts = np.clip(amounts, 200, 45000)
    
    account_age = np.random.exponential(scale=240, size=n_samples) + 1
    past_return_rate = np.random.beta(a=1.5, b=8, size=n_samples)
    days_since_delivery = np.random.poisson(lam=4.5, size=n_samples) + 1
    discount_pct = np.random.choice([0, 10, 20, 30, 50], size=n_samples, p=[0.45, 0.25, 0.15, 0.1, 0.05])

    # 2. Risk separation logic with interaction signals
    # Abuse correlates with high return history + high value + late return request + new account
    z = (
        -5.2
        + (past_return_rate * 6.5)
        + (np.log(amounts) * 0.45)
        + (days_since_delivery * 0.18)
        - (np.log(account_age) * 0.40)
        + ((past_return_rate * (days_since_delivery > 7)) * 2.2)
    )
    probs = 1 / (1 + np.exp(-z))
    is_abusive = np.random.binomial(1, probs)

    df = pd.DataFrame({
        'order_amount': amounts,
        'account_age_days': account_age.astype(int),
        'past_return_rate': past_return_rate,
        'days_since_delivery': days_since_delivery,
        'discount_pct': discount_pct,
        'is_abusive': is_abusive
    })
    return df

def train_and_evaluate():
    df = generate_realistic_data()
    X = df.drop(columns=['is_abusive'])
    y = df['is_abusive']

    # Strict split: 60% Train, 20% Val, 20% Held-Out Test
    X_train_val, X_test, y_train_val, y_test = train_test_split(X, y, test_size=0.20, random_state=42, stratify=y)
    X_train, X_val, y_train, y_val = train_test_split(X_train_val, y_train_val, test_size=0.25, random_state=42, stratify=y_train_val)

    # Train calibrated boosting model
    base_clf = HistGradientBoostingClassifier(
        random_state=42, 
        max_iter=120, 
        learning_rate=0.08, 
        min_samples_leaf=25,
        l2_regularization=1.5
    )
    model = CalibratedClassifierCV(base_clf, method='isotonic', cv=5)
    model.fit(X_train, y_train)

    # Find optimal cost threshold on VALIDATION set
    val_probs = model.predict_proba(X_val)[:, 1]
    FP_COST = 500.0  # ₹500 customer friction / review cost
    
    thresholds = [0.35, 0.50, 0.65, 0.75]
    threshold_tradeoffs = []
    best_thresh, min_loss = 0.5, float('inf')

    for t in np.linspace(0.2, 0.85, 66):
        preds = (val_probs >= t).astype(int)
        fp = ((preds == 1) & (y_val == 0)).sum()
        fn_loss = X_val.loc[(preds == 0) & (y_val == 1), 'order_amount'].sum()
        total_loss = (fp * FP_COST) + fn_loss
        
        if total_loss < min_loss:
            min_loss = total_loss
            best_thresh = t

    for t in thresholds:
        preds = (val_probs >= t).astype(int)
        threshold_tradeoffs.append({
            "threshold": float(t),
            "precision": float(precision_score(y_val, preds, zero_division=0)),
            "recall": float(recall_score(y_val, preds)),
            "f1": float(f1_score(y_val, preds))
        })

    # Run on UNTOUCHED Held-Out Test Set
    test_probs = model.predict_proba(X_test)[:, 1]
    test_preds = (test_probs >= best_thresh).astype(int)

    precision = float(precision_score(y_test, test_preds, zero_division=0))
    recall = float(recall_score(y_test, test_preds))
    f1 = float(f1_score(y_test, test_preds))
    auc = float(roc_auc_score(y_test, test_probs))
    tn, fp, fn, tp = confusion_matrix(y_test, test_preds).ravel()

    fp_cost_total = float(fp * FP_COST)
    tp_saved_total = float(X_test.loc[(test_preds == 1) & (y_test == 1), 'order_amount'].sum())
    fn_loss_total = float(X_test.loc[(test_preds == 0) & (y_test == 1), 'order_amount'].sum())
    net_benefit = tp_saved_total - fp_cost_total - fn_loss_total

    metrics_payload = {
        "precision": precision,
        "recall": recall,
        "f1_score": f1,
        "roc_auc": auc,
        "operating_threshold": float(round(best_thresh, 3)),
        "confusion_matrix": {"TP": int(tp), "FP": int(fp), "TN": int(tn), "FN": int(fn)},
        "financial_impact": {
            "gross_abuse_blocked_inr": tp_saved_total,
            "false_positive_ops_cost_inr": fp_cost_total,
            "false_negative_leakage_inr": fn_loss_total,
            "net_economic_benefit_inr": net_benefit
        },
        "threshold_tradeoffs": threshold_tradeoffs
    }

    joblib.dump({'model': model, 'threshold': best_thresh}, 'risk_model.joblib')
    with open('model_metrics.json', 'w') as f:
        json.dump(metrics_payload, f, indent=2)

    print(f"Model trained successfully. Held-out Precision: {precision*100:.2f}%, Recall: {recall*100:.2f}%, AUC: {auc:.4f}")

if __name__ == '__main__':
    train_and_evaluate()