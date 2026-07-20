from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any


class GameState(BaseModel):
    dimension: str = "overworld"
    x: float = 0.0
    y: float = 64.0
    z: float = 0.0
    health: float = 20.0
    hunger: int = 20
    inventory: List[str] = []
    nearby_blocks: List[str] = []
    nearby_entities: List[str] = []


class EmotionState(BaseModel):
    hunger: int = 50
    tiredness: int = 50
    happiness: int = 50
    boredom: int = 50
    fear: int = 50


class PersonalityState(BaseModel):
    diligence: float = 0.5
    bravery: float = 0.5
    talkativeness: float = 0.5
    optimism: float = 0.5
    independence: float = 0.5


class ChatRequest(BaseModel):
    message: str = Field(..., description="玩家发送的消息")
    game_state: Optional[GameState] = None
    emotion: Optional[EmotionState] = None
    personality: Optional[PersonalityState] = None


class ActionStep(BaseModel):
    type: str = Field(..., description="动作类型")
    params: Dict[str, Any] = Field(default_factory=dict, description="动作参数")


class ChatResponse(BaseModel):
    reply: str = Field(..., description="AI 回复内容")
    actions: List[ActionStep] = Field(default_factory=list, description="要执行的动作序列")
    thought: Optional[str] = None


class MemoryEntry(BaseModel):
    id: str
    content: str
    timestamp: float
    importance: float = 1.0
    location: Optional[str] = None


class PlanRequest(BaseModel):
    goal: str = Field(..., description="目标")
    available_items: List[str] = Field(default_factory=list)
    game_state: Optional[GameState] = None


class PlanResponse(BaseModel):
    plan: List[ActionStep] = Field(default_factory=list)
    explanation: Optional[str] = None
