from __future__ import annotations

import json
import threading
import time
from datetime import date
from decimal import Decimal

import pytest

from app.mail.models import MailMessage
from app.services.expense_parsers.base import ParsedTxn, ParserUnavailable
from app.services.expense_parsers.llm_bank_parser import LlmBankParser
from app.services.history_month_pilot import MonthPilot, PRIMARY_ACCOUNT, month_bounds, sender_bank


class FakeProvider:
    def __init__(self, pages, messages=None, account=PRIMARY_ACCOUNT):
        self.pages, self.messages = pages, messages or {}
        self.account, self.calls, self.body_reads = account, [], []

    def search_page(self, query, *, page_token=None, page_size=100):
        self.calls.append((query, page_token))
        result = self.pages[page_token]
        if isinstance(result, Exception):
            raise result
        return result

    def get_message(self, message_id):
        self.body_reads.append(message_id)
        return self.messages[message_id]


def msg(mid, sender="Alerts <x@hdfcbank.net>", text="alert", received=""):
    return MailMessage(mid, "Transaction alert", sender, received or "Fri, 01 May 2026 12:00:00 +0530",
                       text, text, internal_date_ms=1777615200000)


class RegexParser:
    name = "regex_fixture"

    def __init__(self):
        self.calls = []

    def parse(self, email):
        self.calls.append(email.message_id)
        if email.text == "match":
            return [ParsedTxn(date(2026, 5, 1), Decimal("50.00"), "debit", "upi")]
        return []


class UnavailableLlm(LlmBankParser):
    name = "llm_bank_parser"

    def parse(self, email):
        raise ParserUnavailable("offline")


class EmptyLlm(LlmBankParser):
    name = "llm_bank_parser"

    def parse(self, email):
        return []


def test_month_bounds_use_ist_and_next_month_is_exclusive():
    start, end = month_bounds("2026-05")
    assert start.isoformat() == "2026-05-01T00:00:00+05:30"
    assert end.isoformat() == "2026-06-01T00:00:00+05:30"
    with pytest.raises(ValueError, match="YYYY-MM"):
        month_bounds("2026-13")


@pytest.mark.parametrize("sender,bank", [
    ("a@hdfcbank.net", "HDFC"), ("a@alerts.hdfcbank.com", "HDFC"),
    ("a@icicibank.com", "ICICI"), ("a@axisbank.com", "Axis"),
    ("a@sbicard.com", "SBI"), ("a@evil-hdfcbank.net", ""),
    ("a@hdfcbank.net.attacker.test", ""),
])
def test_financial_sender_domains_have_exact_boundaries(sender, bank):
    assert sender_bank(sender) == bank


def test_rejects_non_primary_account_and_provider(tmp_path):
    with pytest.raises(ValueError, match="restricted"):
        MonthPilot(tmp_path, "2026-05", "kpajmalrasi@gmail.com")
    pilot = MonthPilot(tmp_path, "2026-05", PRIMARY_ACCOUNT)
    with pytest.raises(ValueError, match="Provider account"):
        pilot.collect(FakeProvider({}, account="kpajmalrasi@gmail.com"))


def test_paginates_caches_results_and_rerun_skips_same_message(tmp_path):
    regex = RegexParser()
    pilot = MonthPilot(tmp_path, "2026-05", PRIMARY_ACCOUNT, parsers=[regex])
    provider = FakeProvider(
        {None: ([{"id": "same", "from": "a@hdfcbank.net"}], "p2"),
         "p2": ([{"id": "same", "from": "a@hdfcbank.net"},
                 {"id": "other", "from": "customer@example.net"}], None)},
        {"same": msg("same", text="match")})
    pilot.collect(provider)
    assert pilot.state["search_complete"]
    assert len(pilot.state["messages"]) == 2
    assert regex.calls == ["same"]
    assert provider.body_reads == ["same"]
    assert pilot.report()["gross_by_currency_and_direction_in_month"] == {"INR:debit": "50.00"}
    assert pilot.report()["outcomes"]["unrelated_metadata_only"] == 1
    resumed = MonthPilot(tmp_path, "2026-05", PRIMARY_ACCOUNT, parsers=[regex])
    resumed.collect(provider)
    assert len(provider.calls) == 2
    assert regex.calls == ["same"]


def test_failed_second_page_is_incomplete_then_resumes_without_losing_first(tmp_path):
    regex = RegexParser()
    pilot = MonthPilot(tmp_path, "2026-05", PRIMARY_ACCOUNT, parsers=[regex])
    provider = FakeProvider(
        {None: ([{"id": "first", "from": "a@hdfcbank.net"}], "page2"),
         "page2": RuntimeError("temporary outage")},
        {"first": msg("first", text="match")})
    pilot.collect(provider)
    assert not pilot.state["search_complete"]
    assert pilot.state["next_page_token"] == "page2"
    assert len(pilot.state["messages"]) == 1
    provider.pages["page2"] = ([{"id": "first", "from": "a@hdfcbank.net"},
                                {"id": "second", "from": "a@hdfcbank.net"}], None)
    provider.messages["second"] = msg("second", text="none")
    resumed = MonthPilot(tmp_path, "2026-05", PRIMARY_ACCOUNT, parsers=[regex])
    resumed.collect(provider)
    assert resumed.state["search_complete"]
    assert len(resumed.state["messages"]) == 2
    assert provider.calls[-1][1] == "page2"


def test_no_llm_leaves_candidates_pending_and_llm_zero_is_uncertain(tmp_path):
    regex = RegexParser()
    provider = FakeProvider({None: ([{"id": "x", "from": "a@axisbank.com"}], None)},
                            {"x": msg("x", sender="a@axisbank.com", text="no match")})
    no_llm = MonthPilot(tmp_path / "no-llm", "2026-05", PRIMARY_ACCOUNT,
                        no_llm=True, parsers=[regex])
    no_llm.collect(provider)
    assert no_llm.state["messages"]["x"]["status"] == "pending_llm"
    llm = EmptyLlm()
    provider2 = FakeProvider({None: ([{"id": "y", "from": "a@axisbank.com"}], None)},
                             {"y": msg("y", sender="a@axisbank.com", text="no match")})
    with_llm = MonthPilot(tmp_path / "llm", "2026-05", PRIMARY_ACCOUNT,
                          parsers=[RegexParser(), llm])
    with_llm.collect(provider2)
    assert with_llm.state["messages"]["y"]["status"] == "llm_no_rows_returned"


def test_llm_unavailable_remains_retryable(tmp_path):
    provider = FakeProvider({None: ([{"id": "x", "from": "a@axisbank.com"}], None)},
                            {"x": msg("x", sender="a@axisbank.com", text="no match")})
    pilot = MonthPilot(tmp_path, "2026-05", PRIMARY_ACCOUNT,
                       parsers=[RegexParser(), UnavailableLlm()])
    pilot.collect(provider)
    assert pilot.state["messages"]["x"]["status"] == "retryable_error"
    assert "offline" in pilot.state["messages"]["x"]["error"]


def test_metadata_nonfinancial_is_not_opened_and_attachments_not_downloaded(tmp_path):
    provider = FakeProvider({None: ([{"id": "noise", "from": "person@example.net"}], None)})
    pilot = MonthPilot(tmp_path, "2026-05", PRIMARY_ACCOUNT, parsers=[RegexParser()])
    pilot.collect(provider)
    assert provider.body_reads == []
    report = json.loads((tmp_path / "report.json").read_text())
    assert report["attachments_downloaded_or_parsed"] == 0


def test_two_workers_bound_and_overlap_independent_message_processing(tmp_path):
    lock = threading.Lock()
    active, peak = [0], [0]

    class SlowRegex:
        name = "slow_regex"

        def parse(self, email):
            with lock:
                active[0] += 1
                peak[0] = max(peak[0], active[0])
            time.sleep(0.45)
            with lock:
                active[0] -= 1
            return []

    provider = FakeProvider(
        {None: ([{"id": "x", "from": "a@axisbank.com"},
                 {"id": "y", "from": "a@axisbank.com"}], None)},
        {"x": msg("x", sender="a@axisbank.com"),
         "y": msg("y", sender="a@axisbank.com")})
    pilot = MonthPilot(tmp_path, "2026-05", PRIMARY_ACCOUNT,
                       no_llm=True, parsers=[SlowRegex()], workers=2)
    pilot.collect(provider)
    assert peak[0] == 2
    assert set(pilot.report()["outcomes"]) == {"pending_llm"}
