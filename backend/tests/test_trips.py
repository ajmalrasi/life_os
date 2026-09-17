def _payload(mutation: str, revision=None, title="Delhi"):
    return {
        "title": title,
        "base_revision": revision,
        "mutation_id": mutation,
        "payload": {"schema_version": 1, "days": [{"id": "d1"}]},
    }


def test_trip_backup_is_idempotent_and_revision_guarded(client):
    first = client.put("/trips/delhi", json=_payload("one"))
    assert first.status_code == 200
    assert first.json()["revision"] == 1

    retry = client.put("/trips/delhi", json=_payload("one"))
    assert retry.status_code == 200
    assert retry.json()["revision"] == 1

    stale = client.put("/trips/delhi", json=_payload("two"))
    assert stale.status_code == 409

    update = client.put("/trips/delhi", json=_payload("two", revision=1, title="Delhi family trip"))
    assert update.status_code == 200
    assert update.json()["revision"] == 2
    assert client.get("/trips/delhi").json()["title"] == "Delhi family trip"


def test_deleted_trip_does_not_reappear_in_listing(client):
    client.put("/trips/delhi", json=_payload("one"))
    assert len(client.get("/trips").json()) == 1
    assert client.delete("/trips/delhi").status_code == 204
    assert client.get("/trips").json() == []
    assert client.get("/trips/delhi").status_code == 404


def test_trip_attachment_round_trip(client):
    client.put("/trips/delhi", json=_payload("one"))
    uploaded = client.put(
        "/trips/delhi/attachments/photo-1",
        content=b"small-image",
        headers={"content-type": "image/jpeg", "x-filename": "memory.jpg"},
    )
    assert uploaded.status_code == 204
    downloaded = client.get("/trips/delhi/attachments/photo-1")
    assert downloaded.content == b"small-image"
    assert downloaded.headers["content-type"].startswith("image/jpeg")
