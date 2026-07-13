"""Tests for the LLM catch-all bank-email parser.

Two-stage chain: a gate (is this even a transaction?) then field extraction,
only run if the gate says yes. A *definite* negative (unparseable response, or
the gate saying "not a transaction") degrades to "don't touch it" (empty list),
never fabricating a transaction. A *transient* backend failure (OpenClaw down /
OOM) instead raises ``ParserUnavailable`` so the collector defers the email
rather than skipping it forever.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from app.services.expense_parsers import get_parsers
from app.services.expense_parsers.base import BankEmail, ParserUnavailable
from app.services.expense_parsers.llm_bank_parser import (
    _MAX_PROMPT_CHARS,
    LlmBankParser,
    _prepare_email_text,
)
from app.services.openclaw import OpenClawUnavailable


class _FakeClient:
    """Returns canned chat_structured() responses in call order, or raises on
    the call index listed in `fail_at` (0 = gate call, 1 = extraction call)."""

    def __init__(self, responses=None, fail_at: int | None = None) -> None:
        self.responses = list(responses or [])
        self.fail_at = fail_at
        self.timeout = 120.0
        self.calls = 0

    def chat_structured(self, messages, schema, schema_name="response", temperature=0.0) -> str:
        idx = self.calls
        self.calls += 1
        if self.fail_at == idx:
            raise OpenClawUnavailable("down")
        return self.responses[idx]


_YES_GATE = '{"is_transaction": true, "reason": "a debit happened"}'
_NO_GATE = '{"is_transaction": false, "reason": "promotional offer"}'


def _email(text: str) -> BankEmail:
    return BankEmail(
        message_id="m1",
        subject="whatever",
        from_addr="HDFC Bank <alerts@hdfcbank.bank.in>",
        received="Fri, 03 Jul 2026 12:00:00 +0530",
        text=text,
        raw_body=text,
    )


def _fields(**overrides) -> str:
    import json

    data = {
        "direction": "debit",
        "bank": "HDFC",
        "amount": 110.0,
        "currency": "INR",
        "counterparty_name": "RAFEEQUE PATTATH",
        "counterparty_vpa": "paytm.s1hzt87@pty",
        "is_card": False,
        "is_upi": True,
        "is_rupay_upi": False,
        "last4": "7672",
        "txn_date": "2026-07-03",
        "reference": "125721524336",
    }
    data.update(overrides)
    return json.dumps(data)


# --- gate behavior ---------------------------------------------------------------


def test_non_transaction_email_is_skipped_without_extraction_call():
    client = _FakeClient([_NO_GATE])
    txns = LlmBankParser(client=client).parse(_email("Flat 20% cashback this weekend!"))
    assert txns == []
    assert client.calls == 1  # extraction never called


def test_gate_unavailable_defers_email():
    # Transient backend failure on the gate call must raise (defer), not skip —
    # otherwise a transaction email is lost while the LLM is down.
    client = _FakeClient(fail_at=0)
    with pytest.raises(ParserUnavailable):
        LlmBankParser(client=client).parse(_email("some email"))


def test_gate_unparseable_response_skips_email():
    client = _FakeClient(["not json"])
    assert LlmBankParser(client=client).parse(_email("some email")) == []


# --- extraction behavior ---------------------------------------------------------


def test_transaction_email_extracts_upi_debit():
    client = _FakeClient([_YES_GATE, _fields()])
    (txn,) = LlmBankParser(client=client).parse(_email("Rs.110 debited..."))
    assert txn.direction == "debit"
    assert txn.amount == Decimal("110.0")
    assert txn.source == "upi"
    assert txn.account == "HDFC Savings ..7672"
    assert txn.counterparty == "RAFEEQUE PATTATH (paytm.s1hzt87@pty)"
    assert txn.reference == "125721524336"
    assert txn.txn_date == date(2026, 7, 3)
    assert txn.currency == "INR"


def test_foreign_currency_is_preserved_not_defaulted_to_inr():
    """Regression: a ~USD 23 card alert was stored (and shown) as INR because the
    parser never extracted currency and ParsedTxn defaults to INR. The stated
    currency must ride through instead of everything being assumed rupee."""
    client = _FakeClient([_YES_GATE, _fields(amount=23.0, currency="USD", is_card=True, is_upi=False, last4="6116")])
    (txn,) = LlmBankParser(client=client).parse(_email("USD 23.00 spent on your card ..6116"))
    assert txn.amount == Decimal("23.0")
    assert txn.currency == "USD"


def test_currency_symbol_or_word_is_normalized_to_iso_code():
    """The schema asks for an ISO code but the model sometimes echoes the raw
    symbol/word — normalize it rather than persist "$" or "dollars"."""
    for raw, iso in [("$", "USD"), ("Rs.", "INR"), ("₹", "INR"), ("dollars", "USD"), ("", "INR"), ("garbage", "INR")]:
        client = _FakeClient([_YES_GATE, _fields(currency=raw)])
        (txn,) = LlmBankParser(client=client).parse(_email("txn"))
        assert txn.currency == iso, f"{raw!r} -> {txn.currency!r}"


def test_counterparty_is_built_in_code_not_trusted_from_model_formatting():
    """Regression: the model used to return one combined counterparty string,
    and its formatting varied between calls ("vpa (NAME)" vs "NAME (VPA:
    vpa)") — which broke reconcile.py's name-matching between a debit and its
    later credit, since the two sides of one real pair rendered differently
    and couldn't be matched as the same person. Building the string
    ourselves from two separate fields guarantees identical output for both
    sides of a pair regardless of how the model formats things per-call."""
    debit = _FakeClient([_YES_GATE, _fields(
        direction="debit", counterparty_name="MEHBOOBA PARAMBIL", counterparty_vpa="jessyparambil97@oksbi",
    )])
    credit = _FakeClient([_YES_GATE, _fields(
        direction="credit", counterparty_name="MEHBOOBA PARAMBIL", counterparty_vpa="jessyparambil97@oksbi",
    )])
    (debit_txn,) = LlmBankParser(client=debit).parse(_email("debit"))
    (credit_txn,) = LlmBankParser(client=credit).parse(_email("credit"))
    assert debit_txn.counterparty == credit_txn.counterparty == "MEHBOOBA PARAMBIL (jessyparambil97@oksbi)"


def test_counterparty_falls_back_to_vpa_only_when_no_name_given():
    client = _FakeClient([_YES_GATE, _fields(counterparty_name="", counterparty_vpa="q559887345@ybl")])
    (txn,) = LlmBankParser(client=client).parse(_email("txn"))
    assert txn.counterparty == "q559887345@ybl"


def test_counterparty_falls_back_to_name_only_when_no_vpa_given():
    client = _FakeClient([_YES_GATE, _fields(counterparty_name="WWW MOBIKWIK COM", counterparty_vpa="")])
    (txn,) = LlmBankParser(client=client).parse(_email("txn"))
    assert txn.counterparty == "WWW MOBIKWIK COM"


def test_counterparty_is_none_when_neither_given():
    client = _FakeClient([_YES_GATE, _fields(counterparty_name="", counterparty_vpa="")])
    (txn,) = LlmBankParser(client=client).parse(_email("txn"))
    assert txn.counterparty is None


def test_card_transaction_uses_card_live_source_and_label():
    client = _FakeClient([_YES_GATE, _fields(is_upi=False, is_card=True, last4="6116")])
    (txn,) = LlmBankParser(client=client).parse(_email("payment made using your card"))
    assert txn.source == "card_live"
    assert txn.account == "HDFC Card ..6116"


def test_bank_name_is_canonicalized_to_match_regex_parser_labels():
    """The model answers "HDFC Bank" where the regex/statement parsers emit
    "HDFC". account_identity keys identity on the first-word bank token, so an
    un-normalized "HDFC Bank Savings ..7672" would split the card off from the
    regex parser's canonical "HDFC Savings ..7672". Normalize at the source."""
    for chatty, canonical in [
        ("HDFC Bank", "HDFC Savings ..7672"),
        ("Axis Bank Ltd", "Axis Savings ..7672"),
        ("ICICI BANK", "ICICI Savings ..7672"),
        ("SBI", "SBI Savings ..7672"),
    ]:
        client = _FakeClient([_YES_GATE, _fields(bank=chatty, last4="7672")])
        (txn,) = LlmBankParser(client=client).parse(_email("txn"))
        assert txn.account == canonical, f"{chatty!r} -> {txn.account!r}"


def test_unknown_bank_name_passes_through_unchanged():
    client = _FakeClient([_YES_GATE, _fields(bank="Kotak Mahindra", last4="7672")])
    (txn,) = LlmBankParser(client=client).parse(_email("txn"))
    assert txn.account == "Kotak Mahindra Savings ..7672"


def test_rupay_upi_card_transaction_uses_upi_source():
    """RuPay-via-UPI is both a card AND a UPI rail — source should reflect the
    rail (upi), matching how the regex parsers categorize the same case."""
    client = _FakeClient([_YES_GATE, _fields(is_upi=True, is_card=True, is_rupay_upi=True, last4="5473")])
    (txn,) = LlmBankParser(client=client).parse(_email("RuPay credit card UPI debit"))
    assert txn.source == "upi"


def test_plain_bank_transfer_uses_bank_live_source():
    """Not "bank_account" — that source name already belongs to the separate
    statement-PDF pipeline (statement_parsers/bank_accounts.py) and means a
    monthly statement line there, not a live alert."""
    client = _FakeClient([_YES_GATE, _fields(is_upi=False, is_card=False, last4="")])
    (txn,) = LlmBankParser(client=client).parse(_email("NEFT credited to your account"))
    assert txn.source == "bank_live"
    assert txn.account is None  # no last4 to label with


def test_extraction_unavailable_defers_email():
    # Gate says "yes" but the extraction call hits a transient backend failure:
    # raise (defer) rather than skip, so the confirmed-transaction email isn't lost.
    client = _FakeClient([_YES_GATE], fail_at=1)
    with pytest.raises(ParserUnavailable):
        LlmBankParser(client=client).parse(_email("some txn"))


def test_extraction_unparseable_response_skips_email():
    client = _FakeClient([_YES_GATE, "not json"])
    assert LlmBankParser(client=client).parse(_email("some txn")) == []


def test_extraction_tolerates_raw_ddmmyy_date_despite_schema_asking_for_iso():
    """A JSON schema only constrains a string field's type, not its format —
    in live testing the model sometimes echoed the email's raw "01-07-26"
    instead of converting to YYYY-MM-DD when bundled with many other fields
    in one call. An otherwise-good extraction shouldn't be discarded over
    this one field."""
    client = _FakeClient([_YES_GATE, _fields(txn_date="01-07-26")])
    (txn,) = LlmBankParser(client=client).parse(_email("some txn"))
    assert txn.txn_date == date(2026, 7, 1)


def test_extraction_unparseable_date_skips_email():
    client = _FakeClient([_YES_GATE, _fields(txn_date="not a date")])
    assert LlmBankParser(client=client).parse(_email("some txn")) == []


def test_extraction_missing_required_key_skips_email():
    import json

    bad = json.dumps({"direction": "debit"})  # missing everything else
    client = _FakeClient([_YES_GATE, bad])
    assert LlmBankParser(client=client).parse(_email("some txn")) == []


def test_invalid_direction_is_rejected_even_if_returned():
    """Defense in depth: the JSON schema constrains the model to debit/credit,
    but a fake/misbehaving client bypasses that, so the parser itself must
    still guard against an invalid value rather than trust it blindly."""
    client = _FakeClient([_YES_GATE, _fields(direction="unknown")])
    assert LlmBankParser(client=client).parse(_email("some txn")) == []


def test_zero_amount_is_rejected():
    client = _FakeClient([_YES_GATE, _fields(amount=0)])
    assert LlmBankParser(client=client).parse(_email("some txn")) == []


def test_disabled_by_setting_skips_without_calling_client(monkeypatch):
    from app.services.expense_parsers import llm_bank_parser

    monkeypatch.setattr(llm_bank_parser.settings, "llm_bank_parser_enabled", False)
    client = _FakeClient([_YES_GATE, _fields()])
    assert LlmBankParser(client=client).parse(_email("some txn")) == []
    assert client.calls == 0


# --- registry / query shape --------------------------------------------------


def test_registry_has_llm_bank_parser():
    assert any(p.name == "llm_bank_parser" for p in get_parsers())


def test_gmail_query_has_no_subject_filter_and_covers_both_banks():
    query = LlmBankParser().gmail_query
    assert "subject:" not in query
    # Bare domains, so a change of sending address (alerts@ -> txnalerts@) can't
    # silently drop a bank's transactions.
    assert "hdfcbank.bank.in" in query
    assert "axis.bank.in" in query
    assert "alerts@" not in query


def test_gmail_query_can_be_scoped_to_one_bank_domain():
    """Needed for a per-bank historical rebuild — different banks can have
    very different statement coverage, so a rebuild often needs a different
    "since" date per bank rather than one shared query for both."""
    query = LlmBankParser(domains=["hdfcbank.bank.in"]).gmail_query
    assert "hdfcbank.bank.in" in query
    assert "axis.bank.in" not in query


# --- prompt preparation (fit the small model's context window) ------------------


def test_prepare_email_text_strips_tracking_urls():
    """Bank alerts pad the body with long opaque tracking/redirect URLs that
    tokenize terribly and blew past the model's 2048-token window (-> HTTP 400,
    email deferred forever). They carry no transaction signal, so they're removed
    while the real text — amount, party, date — is kept."""
    body = (
        "Rs.150.00 is debited from your account ending 7672 towards VPA "
        "8593060686@fam (Mohammed Shereen K) on 10-07-26. "
        "View: https://t13.mailers.hdfcbank.bank.in/r/?id=t2823b532-824a&e=" + ("A" * 400)
    )
    out = _prepare_email_text(body)
    assert "https://" not in out
    assert "Rs.150.00 is debited" in out
    assert "8593060686@fam" in out  # the VPA is plain text, not a URL — must survive


def test_prepare_email_text_caps_length():
    out = _prepare_email_text("word " * 5000)
    assert len(out) <= _MAX_PROMPT_CHARS
