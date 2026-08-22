"""HTTP-level tests for the tokenizer service."""

from fastapi.testclient import TestClient

from app.main import MAX_TEXT_CHARS, app

client = TestClient(app)


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_tokenize_returns_tokens():
    response = client.post("/tokenize", json={"text": "猫が好きです。"})
    assert response.status_code == 200
    body = response.json()
    assert body["token_count"] == len(body["tokens"])
    assert body["tokens"][0]["surface"] == "猫"
    assert body["tokens"][0]["normalized_form"] == "猫"


def test_mode_defaults_to_c():
    """The default must stay C — the Java client relies on not sending it."""
    whole = client.post("/tokenize", json={"text": "東京都庁"}).json()
    assert [t["surface"] for t in whole["tokens"]] == ["東京都庁"]


def test_mode_can_be_overridden():
    split = client.post("/tokenize", json={"text": "東京都庁", "mode": "A"}).json()
    assert len(split["tokens"]) > 1


def test_invalid_mode_is_rejected():
    response = client.post("/tokenize", json={"text": "猫", "mode": "Z"})
    assert response.status_code == 422


def test_oversized_text_is_rejected_not_processed():
    response = client.post("/tokenize", json={"text": "猫" * (MAX_TEXT_CHARS + 1)})
    assert response.status_code == 413


def test_correlation_id_is_echoed():
    """Spring propagates this so logs stitch across both runtimes."""
    response = client.get("/health", headers={"X-Correlation-Id": "abc-123"})
    assert response.headers["X-Correlation-Id"] == "abc-123"


def test_openapi_schema_is_generatable():
    """The Java client is generated from this, so it must always be valid."""
    schema = client.get("/openapi.json").json()
    assert "/tokenize" in schema["paths"]
    assert "TokenizeResponse" in schema["components"]["schemas"]
