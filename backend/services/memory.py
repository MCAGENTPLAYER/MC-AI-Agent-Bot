import json
from pathlib import Path
from typing import List, Optional, Dict, Any

from core.config import settings
from core.logger import logger
from schemas.models import MemoryEntry


class MemoryManager:
    def __init__(self):
        self.memories: List[MemoryEntry] = []
        self.max_memories = 200
        self._load_memories()

    def _load_memories(self):
        file_path = settings.memory_dir / "memories.json"
        if file_path.exists():
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.memories = [MemoryEntry(**m) for m in data]
                logger.info(f"Loaded {len(self.memories)} memories")
            except Exception as e:
                logger.error(f"Failed to load memories: {e}")

    def _save_memories(self):
        file_path = settings.memory_dir / "memories.json"
        try:
            with open(file_path, "w", encoding="utf-8") as f:
                json.dump([m.model_dump() for m in self.memories], f, ensure_ascii=False, indent=2)
        except Exception as e:
            logger.error(f"Failed to save memories: {e}")

    def add_memory(self, content: str, importance: float = 1.0, location: Optional[str] = None):
        import uuid
        entry = MemoryEntry(
            id=str(uuid.uuid4())[:8],
            content=content,
            timestamp=float(__import__("time").time()),
            importance=importance,
            location=location,
        )
        self.memories.append(entry)
        self.memories.sort(key=lambda x: x.timestamp, reverse=True)
        if len(self.memories) > self.max_memories:
            self.memories = self.memories[:self.max_memories]
        self._save_memories()
        logger.debug(f"Added memory: {content[:50]}...")

    def get_recent_memories(self, limit: int = 10) -> List[MemoryEntry]:
        return self.memories[:limit]

    def search_memories(self, query: str, limit: int = 5) -> List[MemoryEntry]:
        if not self.memories:
            return []
        try:
            from langchain.embeddings import FakeEmbeddings
            from langchain.vectorstores import FAISS
            from langchain.schema import Document

            embeddings = FakeEmbeddings(size=1024)
            docs = [
                Document(page_content=m.content, metadata={"id": m.id})
                for m in self.memories
            ]
            db = FAISS.from_documents(docs, embeddings)
            results = db.similarity_search(query, k=limit)
            matched_ids = {doc.metadata["id"] for doc in results}
            return [m for m in self.memories if m.id in matched_ids][:limit]
        except Exception as e:
            logger.warning(f"Vector search failed, falling back to keyword: {e}")
            return self._keyword_search(query, limit)

    def _keyword_search(self, query: str, limit: int) -> List[MemoryEntry]:
        query_lower = query.lower()
        scored = [
            (m, sum(query_lower.count(word) for word in m.content.lower().split()))
            for m in self.memories
        ]
        scored = [(m, s) for m, s in scored if s > 0]
        scored.sort(key=lambda x: x[1], reverse=True)
        return [m for m, _ in scored[:limit]]

    def clear(self):
        """清空所有记忆"""
        self.memories = []
        self._save_memories()
        logger.info("All memories cleared")

    def format_for_ai(self) -> str:
        recent = self.get_recent_memories(10)
        if not recent:
            return "近期没有特殊经历"
        lines = []
        for m in recent:
            age = self._format_age(m.timestamp)
            loc = f" 在 {m.location}" if m.location else ""
            imp = "（非常重要）" if m.importance > 0.8 else ""
            lines.append(f"- {m.content}{loc}{imp}（{age}）")
        return "\n".join(lines)

    def _format_age(self, timestamp: float) -> str:
        now = __import__("time").time()
        diff = now - timestamp
        if diff < 60:
            return "刚刚"
        elif diff < 3600:
            return f"{int(diff // 60)}分钟前"
        elif diff < 86400:
            return f"{int(diff // 3600)}小时前"
        else:
            return f"{int(diff // 86400)}天前"


memory_manager = MemoryManager()
