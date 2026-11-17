import numpy as np

from app.services import embedder


class FakeModel:
    def __init__(self):
        self.seen = None

    def embed(self, texts, batch_size):
        self.seen = list(texts)
        return (np.full(embedder.DIMENSIONS, 0.1, dtype=np.float32) for _ in texts)


def test_embed_texts_returns_vectors_and_replaces_empty_text(monkeypatch):
    fake = FakeModel()
    monkeypatch.setattr(embedder, "_model", lambda: fake)

    vectors = embedder.embed_texts(["hola", ""])

    assert len(vectors) == 2 and len(vectors[0]) == embedder.DIMENSIONS
    assert fake.seen == ["hola", " "]
