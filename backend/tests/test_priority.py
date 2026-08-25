from __future__ import annotations

from datetime import datetime, timezone

from app.config import get_settings
from app.database import SessionLocal
from app.mail.gmail_provider import MailProviderError
from app.mail.models import MailMessage
from app.models.mail import MailMessageRecord
from app.models.priority import PriorityItem
from app.priority.calendar import parse_ics
from app.priority.classifier import classify
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


def test_interview_classification_extracts_schedule_and_calendar_fields():
    result = classify(_message())
    assert result.importance == "high"
    assert result.state == "scheduled"
    assert result.labels == ["work", "interview"]
    assert result.details.role == "Senior Android Engineer"
    assert result.details.event_start is not None
    assert result.details.event_start.astimezone(timezone.utc).isoformat().startswith("2026-08-28T09:30")
    assert result.details.event_end is not None
    assert result.details.meeting_url == "https://meet.google.com/abc-defg-hij"
    assert result.details.has_calendar_invite is False


def test_job_digest_and_application_receipt_are_suppressed():
    digest = _message(
        subject="Your weekly job alert",
        text="Recommended jobs for you. Interview tips and new openings. Unsubscribe",
    )
    result = classify(digest)
    assert result.importance == "suppressed"
    assert result.evidence == ["bulk_or_application_receipt"]


def test_direct_personal_request_is_actionable():
    personal = _message(
        subject="Documents needed",
        text="Hi Ajmal, could you please send the signed form by August 28, 2026 at 5:00 PM IST?",
    )
    result = classify(personal)
    assert result.importance == "high"
    assert result.state == "needs_action"
    assert "personal" in result.labels
    assert "document" in result.labels


def test_scheduling_deadline_does_not_become_a_confirmed_interview_time():
    scheduling = _message(
        subject="Choose your interview slot",
        text=(
            "Hi Ajmal, please select a suitable interview time by August 28, 2026 at "
            "5:00 PM IST using https://calendly.com/acme/interview"
        ),
    )
    result = classify(scheduling)
    assert result.state == "needs_action"
    assert result.details.event_start is None
    assert result.details.due_at is not None
    assert result.details.action_url == "https://calendly.com/acme/interview"


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
