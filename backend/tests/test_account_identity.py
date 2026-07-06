"""Tests for canonical account-label normalization.

One physical card gets stored under inconsistent labels depending on which
parser wrote it. ``normalize_account`` collapses the known variants to one
canonical label (registry-only) while leaving anything unregistered untouched.
"""

from __future__ import annotations

from app.account_identity import normalize_account


def test_name_drift_variants_merge_to_canonical():
    # Same ICICI card (..0010) under a generic statement label and a specific
    # live label -> the one canonical label.
    assert normalize_account("ICICI Card ..0010") == "ICICI Amazon Pay ..0010"
    assert normalize_account("ICICI Amazon ..0010") == "ICICI Amazon Pay ..0010"


def test_last4_length_drift_merges_by_suffix():
    # The case the old (bank, last4) key couldn't handle: a 2-digit last4 is a
    # suffix of the 4-digit one for the same physical SBI card.
    assert normalize_account("SBI Card ..70") == "SBI Card ..1970"
    assert normalize_account("SBI Card ..1970") == "SBI Card ..1970"


def test_canonical_labels_are_idempotent():
    for label in ("ICICI Amazon Pay ..0010", "SBI Card ..1970"):
        assert normalize_account(label) == normalize_account(normalize_account(label))
        assert normalize_account(label) == label


def test_unregistered_card_passes_through_unchanged():
    assert normalize_account("HDFC RuPay CC ..5473") == "HDFC RuPay CC ..5473"
    assert normalize_account("Axis Flipkart ..1234") == "Axis Flipkart ..1234"


def test_label_without_last4_passes_through():
    assert normalize_account("Axis Savings") == "Axis Savings"
    assert normalize_account("HDFC RuPay CC") == "HDFC RuPay CC"


def test_empty_and_none_pass_through():
    assert normalize_account(None) is None
    assert normalize_account("") == ""


def test_wrong_bank_same_digits_does_not_merge():
    # Suffix overlap alone is not enough; the bank token must match too.
    assert normalize_account("HDFC Card ..0010") == "HDFC Card ..0010"
