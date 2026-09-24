from __future__ import annotations

from datetime import datetime, timezone

import pytest

from app.config import get_settings
from app.database import SessionLocal
from app.mail.gmail_provider import MailProviderError
from app.mail.models import MailMessage
from app.models.mail import MailMessageRecord
from app.models.priority import PriorityItem
from app.priority.calendar import parse_ics
from app.priority.classifier import PriorityModelUnavailable, classify
from app.services.openclaw import OpenClawBusy
from app.priority.service import PrioritySyncService
from app.repositories.mail_repo import MailAccountRepository


def _message(
    *,
    message_id: str = "m1",
    thread_id: str = "t1",
    subject: str = "Technical interview confirmed",
    text: str = (
        "Hi Ajmal, your technical interview for the Senior Android Engineer role "
        "is confirmed for August 28, 2026 at 3:00 PM IST. It will take 60 minutes. "
        "Join https://meet.google.com/abc-defg-hij"
    ),
    internal_ms: int = 1_777_365_600_000,
) -> MailMessage:
    return MailMessage(
        message_id=message_id,
        thread_id=thread_id,
        subject=subject,
        from_addr="Nadia Thomas <nadia@acme.example>",
        to_addrs="Ajmal <ajmal@example.com>",
        received="Tue, 25 Aug 2026 10:00:00 +0530",
        text=text,
        raw_body=text,
        internal_date_ms=internal_ms,
        headers={"to": "Ajmal <ajmal@example.com>"},
    )


class FakeProvider:
    def __init__(self, account: str, messages: list[MailMessage]):
        self.account = account
        self.messages = {message.message_id: message for message in messages}
        self.searches: list[str] = []

    def is_available(self) -> bool:
        return True

    def search(self, query: str, max_results: int = 25) -> list[dict]:
        self.searches.append(query)
        return [
            {
                "id": message.message_id,
                "threadId": message.thread_id,
                "subject": message.subject,
                "from": message.from_addr,
                "date": message.received,
                "internalDate": message.internal_date_ms,
            }
            for message in self.messages.values()
        ]

    def get_message(self, message_id: str) -> MailMessage:
        return self.messages[message_id]

    def list_attachments(self, message_id: str) -> list[dict]:
        return []

    def download_attachment(self, message_id: str, attachment_id: str, out_path: str) -> bool:
        raise AssertionError("no attachment expected")


def _triage(about: str, category: str, **flags) -> dict:
    return {
        "about": about,
        "mass_mail": False,
        "selling_something": False,
        "routine_notice": False,
        "his_hiring_process": False,
        "ajmal_must_act": False,
        **flags,
        "category": category,
        "confidence": "high",
    }


class FakeModel:
    """Stands in for the local model: records prompts, returns scripted JSON."""

    def __init__(self, triage: dict | None = None, details: dict | None = None, fail: bool = False):
        self.triage = triage or _triage("Interview confirmation for Ajmal", "interview", his_hiring_process=True)
        self.details = details or {
            "state": "scheduled",
            "summary": "Technical interview with Acme on 28 Aug at 3 PM IST.",
            "action": None,
            "deadline_quote": None,
            "deadline": None,
            "event_quote": "August 28, 2026 at 3:00 PM IST",
            "event_start": "2026-08-28T15:00",
            "event_end": "2026-08-28T16:00",
            "company": "Acme",
            "role": "Senior Android Engineer",
            "location": None,
        }
        self.fail = fail
        self.calls: list[tuple[str, list[dict]]] = []

    def chat_json(self, messages, schema, schema_name, max_tokens=512, **_):
        self.calls.append((schema_name, messages))
        if self.fail:
            raise OpenClawBusy("Local model is busy")
        return dict(self.triage if schema_name == "priority_triage" else self.details)


def _subject_model(**_kwargs) -> FakeModel:
    """Sync tests: alerts are noise, everything else is a scheduled interview."""

    class SubjectModel(FakeModel):
        def __init__(self):
            super().__init__()
            # Far enough ahead that the feed does not hide it as a past event.
            self.details = {**self.details, "event_start": "2027-02-10T15:00", "event_end": "2027-02-10T16:00"}

        def chat_json(self, messages, schema, schema_name, max_tokens=512, **kw):
            block = messages[-1]["content"]
            if "alert" in block.split("Subject:", 1)[1].split("\n", 1)[0].casefold():
                self.triage = _triage("Bulk job alert", "none", mass_mail=True)
            if "rescheduled" in block:
                self.details = {**self.details, "event_start": "2027-02-11T16:00", "event_end": None}
            return super().chat_json(messages, schema, schema_name, max_tokens, **kw)

    return SubjectModel()


@pytest.fixture(autouse=True)
def _model(monkeypatch):
    monkeypatch.setattr("app.priority.classifier.OpenClawClient", _subject_model)


def test_model_output_maps_to_priority_item_in_ist():
    model = FakeModel()
    result = classify(_message(), client=model, account_address="ajmal@example.com")

    assert [name for name, _ in model.calls] == ["priority_triage", "priority_details"]
    assert result.importance == "high"
    assert result.labels == ["interview"]
    assert result.state == "scheduled"
    assert result.details.event_start.isoformat() == "2026-08-28T09:30:00+00:00"
    assert result.details.event_end.isoformat() == "2026-08-28T10:30:00+00:00"
    assert result.details.role == "Senior Android Engineer"
    assert result.details.meeting_url == "https://meet.google.com/abc-defg-hij"
    assert result.why_important == "Interview confirmation for Ajmal. (part of your hiring process)"


def test_prompt_carries_facts_and_compacts_links():
    model = FakeModel()
    message = MailMessage(
        **{**_message().__dict__, "label_ids": ("INBOX", "CATEGORY_UPDATES", "IMPORTANT")}
    )
    classify(message, client=model, account_address="ajmal@example.com")
    system, user = model.calls[0][1]
    assert "Ajmal" in system["content"]
    block = user["content"]
    assert "Ajmal is: in To" in block
    assert "Gmail category: Updates (Gmail marked it Important)" in block
    assert "[link: meet.google.com]" in block and "abc-defg-hij" not in block


def test_none_category_is_suppressed_without_detail_call():
    model = FakeModel(triage=_triage("Weekly job digest", "none", mass_mail=True))
    result = classify(_message(subject="Weekly job alert"), client=model)
    assert result.importance == "suppressed"
    assert result.labels == []
    assert [name for name, _ in model.calls] == ["priority_triage"]


def test_policy_overrides_a_category_contradicted_by_the_models_own_answers():
    promo = FakeModel(triage=_triage("Free live class invite", "event", selling_something=True))
    blast = FakeModel(triage=_triage("Job board blast", "job", mass_mail=True))
    receipt = FakeModel(triage=_triage("Buy order complete", "account", routine_notice=True))
    ats = FakeModel(triage=_triage("Interview reminder", "interview", mass_mail=True, his_hiring_process=True))
    assert classify(_message(), client=promo).importance == "suppressed"
    assert classify(_message(), client=blast).importance == "suppressed"
    assert classify(_message(), client=receipt).importance == "suppressed"
    assert classify(_message(), client=ats).labels == ["interview"]


def test_job_board_relays_are_not_jobs_but_board_interviews_stay():
    board = MailMessage(**{**_message().__dict__, "from_addr": "Recruiter <abc123@naukri.com>"})
    job = FakeModel(triage=_triage("Recruiter message", "job", his_hiring_process=True))
    interview = FakeModel(triage=_triage("Interview slot", "interview", his_hiring_process=True))
    assert classify(board, client=job).importance == "suppressed"
    assert classify(board, client=interview).labels == ["interview"]
    assert "naukri.com is a job board" in interview.calls[0][1][1]["content"]


def test_times_are_dropped_unless_quoted_from_the_email():
    model = FakeModel(details={
        "state": "needs_action", "summary": "Confirm interest.", "action": "Reply confirming interest",
        "deadline_quote": "by 30 September", "deadline": "2026-09-30T23:59",
        "event_quote": "August 28, 2026 at 3:00 PM IST", "event_start": "2026-08-28T15:00",
        "event_end": None, "company": None, "role": None, "location": None,
    })
    result = classify(_message(), client=model)
    assert result.details.due_at is None  # "by 30 September" is not in the email
    assert result.details.event_start.isoformat() == "2026-08-28T09:30:00+00:00"


def test_unavailable_model_defers_instead_of_guessing():
    with pytest.raises(PriorityModelUnavailable):
        classify(_message(), client=FakeModel(fail=True))


def test_calendar_invite_is_authoritative_for_time_and_cancellation():
    invite = parse_ics(
        """BEGIN:VCALENDAR
METHOD:CANCEL
BEGIN:VEVENT
DTSTART;TZID=India Standard Time:20260924T160000
DTEND;TZID=India Standard Time:20260924T163000
SUMMARY:LTM interview
END:VEVENT
END:VCALENDAR"""
    )
    result = classify(_message(), invite, client=FakeModel())
    assert result.state == "cancelled"
    assert result.importance == "normal"
    assert result.details.event_start.isoformat() == "2026-09-24T10:30:00+00:00"
    assert result.details.has_calendar_invite is True


def test_implausible_or_missing_times_are_not_trusted():
    model = FakeModel(details={
        "state": "scheduled", "summary": "Interview.", "action": "Confirm the slot",
        "deadline_quote": "August 28, 2026", "deadline": "1970-01-01T00:00",
        "event_quote": None, "event_start": None, "event_end": None,
        "company": "null", "role": None, "location": None,
    })
    result = classify(_message(), client=model)
    # A "scheduled" item with no usable time becomes an action, not a fake event.
    assert result.state == "needs_action"
    assert result.details.action_text == "Confirm the slot"
    assert result.details.due_at is None
    assert result.details.company is None


def test_ics_parser_skips_vtimezone_and_maps_windows_zone_names():
    invite = parse_ics(
        """BEGIN:VCALENDAR
BEGIN:VTIMEZONE
TZID:India Standard Time
BEGIN:STANDARD
DTSTART:16010101T000000
TZOFFSETFROM:+0530
TZOFFSETTO:+0530
END:STANDARD
END:VTIMEZONE
BEGIN:VEVENT
DTSTART;TZID=India Standard Time:20260924T160000
DTEND;TZID=India Standard Time:20260924T163000
SUMMARY:Interview with LTM
LOCATION:Microsoft Teams\\, online
END:VEVENT
END:VCALENDAR"""
    )
    assert invite.start.isoformat() == "2026-09-24T10:30:00+00:00"
    assert invite.end.isoformat() == "2026-09-24T11:00:00+00:00"
    assert invite.timezone_name == "Asia/Kolkata"
    assert invite.summary == "Interview with LTM"
    assert invite.location == "Microsoft Teams, online"


def test_sync_defers_messages_while_the_model_is_unavailable(client, monkeypatch):
    monkeypatch.setattr("app.priority.classifier.OpenClawClient", lambda **_: FakeModel(fail=True))
    provider = FakeProvider("career@example.com", [_message()])
    with SessionLocal() as db:
        MailAccountRepository(db).register("career@example.com", "priority", "Career inbox")
        service = PrioritySyncService(db)
        monkeypatch.setattr(service, "_provider", lambda _account: provider)
        report = service.sync()
        assert report.items_new == 0
        assert report.deferred == 1
        assert report.pending == 1
        assert db.query(MailMessageRecord).one().processing_state == "retry"


def test_ics_parser_preserves_uid_time_location_and_status():
    invite = parse_ics(
        """BEGIN:VCALENDAR
BEGIN:VEVENT
UID:interview-123@example.com
DTSTART;TZID=Asia/Kolkata:20260828T150000
DTEND;TZID=Asia/Kolkata:20260828T160000
LOCATION:Google Meet
URL:https://meet.google.com/abc-defg-hij
STATUS:CONFIRMED
END:VEVENT
END:VCALENDAR"""
    )
    assert invite is not None
    assert invite.uid == "interview-123@example.com"
    assert invite.start.isoformat().startswith("2026-08-28T09:30")
    assert invite.location == "Google Meet"
    assert invite.status == "confirmed"


def test_priority_sync_is_account_scoped_durable_and_idempotent(client, monkeypatch):
    message = _message()
    providers: dict[str, FakeProvider] = {
        "career@example.com": FakeProvider("career@example.com", [message]),
        "finance@example.com": FakeProvider("finance@example.com", []),
    }
    with SessionLocal() as db:
        accounts = MailAccountRepository(db)
        accounts.register("finance@example.com", "finance", "Finance inbox")
        accounts.register("career@example.com", "priority", "Career inbox")
        service = PrioritySyncService(db)
        monkeypatch.setattr(service, "_provider", lambda account: providers[account.provider_account_id])

        first = service.sync()
        second = service.sync()

        assert first.items_new == 1
        assert second.items_new == 0
        assert db.query(PriorityItem).count() == 1
        assert db.query(MailMessageRecord).count() == 1
        assert providers["finance@example.com"].searches == []

    response = client.get("/priority")
    assert response.status_code == 200
    item = response.json()[0]
    assert item["account_label"] == "Career inbox"
    assert item["account_address"] == "career@example.com"
    assert item["state"] == "scheduled"


def test_new_message_in_same_thread_updates_one_priority_item(client, monkeypatch):
    first = _message(message_id="m1", thread_id="thread")
    second = _message(
        message_id="m2",
        thread_id="thread",
        subject="Interview rescheduled",
        text=(
            "Hi Ajmal, your interview has been rescheduled to August 29, 2026 at "
            "4:00 PM IST. Join https://meet.google.com/new-room"
        ),
        internal_ms=first.internal_date_ms + 86_400_000,
    )
    provider = FakeProvider("career@example.com", [first])
    with SessionLocal() as db:
        MailAccountRepository(db).register("career@example.com", "priority", "Career inbox")
        service = PrioritySyncService(db)
        monkeypatch.setattr(service, "_provider", lambda _account: provider)
        assert service.sync().items_new == 1
        provider.messages = {second.message_id: second}
        report = service.sync()
        assert report.items_updated == 1
        assert db.query(PriorityItem).count() == 1
        assert db.query(MailMessageRecord).count() == 2
        item = db.query(PriorityItem).one()
        assert item.subject == "Interview rescheduled"
        assert item.meeting_url == "https://meet.google.com/new-room"
        assert len(service.priority.history(item.id)) == 2


def test_full_sync_reclassifies_and_hides_existing_noise(client, monkeypatch):
    provider = FakeProvider("career@example.com", [_message()])
    with SessionLocal() as db:
        MailAccountRepository(db).register("career@example.com", "priority", "Career inbox")
        service = PrioritySyncService(db)
        monkeypatch.setattr(service, "_provider", lambda _account: provider)
        assert service.sync().items_new == 1

        promotional = _message(
            subject="Weekly job alert",
            text="Hi Ajmal, recommended jobs for you. Review the latest openings.",
        )
        provider.messages = {promotional.message_id: promotional}
        provider.search = lambda _query, max_results=25: []
        report = service.sync(full=True)

        assert report.items_updated == 1
        item = db.query(PriorityItem).one()
        assert item.importance == "suppressed"

    assert client.get("/priority").json() == []


def test_account_auth_error_is_reported_without_becoming_empty_success(client, monkeypatch):
    class BrokenProvider(FakeProvider):
        def search(self, query: str, max_results: int = 25) -> list[dict]:
            raise MailProviderError("Gmail authorization expired for career@example.com")

    with SessionLocal() as db:
        MailAccountRepository(db).register("career@example.com", "priority", "Career inbox")
        service = PrioritySyncService(db)
        monkeypatch.setattr(
            PrioritySyncService,
            "_provider",
            lambda self, account: BrokenProvider(account.provider_account_id, []),
        )
        report = service.sync()
        assert report.account_errors == ["Gmail authorization expired for career@example.com"]
        assert report.items_new == 0

    response = client.get("/mail/accounts")
    assert response.status_code == 200
    account = response.json()[0]
    assert account["priority_last_error"] == "Gmail authorization expired for career@example.com"


def test_unconfigured_priority_sync_is_explicit_failure(client):
    response = client.post("/priority/sync/check")
    assert response.status_code == 200
    assert response.json()["successful"] is False
    assert response.json()["account_errors"] == ["No Priority mail account is registered."]


def test_priority_update_and_feedback_are_persistent(client, monkeypatch):
    provider = FakeProvider("career@example.com", [_message()])
    with SessionLocal() as db:
        MailAccountRepository(db).register("career@example.com", "priority", "Career inbox")
        service = PrioritySyncService(db)
        monkeypatch.setattr(service, "_provider", lambda _account: provider)
        item_id = service.sync().new_items[0].id

    done = client.patch(f"/priority/{item_id}", json={"state": "done"})
    assert done.status_code == 200
    assert done.json()["state"] == "done"
    assert client.get("/priority").json() == []
    history = client.get(f"/priority/{item_id}")
    assert history.status_code == 200
    assert len(history.json()["history"]) == 1


def test_full_resync_keeps_items_the_user_marked_done(client, monkeypatch):
    provider = FakeProvider("career@example.com", [_message()])
    with SessionLocal() as db:
        MailAccountRepository(db).register("career@example.com", "priority", "Career inbox")
        service = PrioritySyncService(db)
        monkeypatch.setattr(service, "_provider", lambda _account: provider)
        item_id = service.sync().new_items[0].id
        service.priority.apply_update(service.priority.get(item_id), state="done")
        service.sync(full=True)
        assert service.priority.get(item_id).user_state == "done"
