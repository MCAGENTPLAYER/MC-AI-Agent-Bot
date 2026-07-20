from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse

from core.config import settings
from core.logger import logger, init_dirs
from schemas.models import ChatRequest, ChatResponse, MemoryEntry, PlanRequest, PlanResponse, GameState, EmotionState, PersonalityState
from services.ai import ai_service
from services.memory import memory_manager

app = FastAPI(title="AI Bot Server", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

active_connections = []
current_game_state = GameState()
current_emotion_state = EmotionState()
current_personality_state = PersonalityState()


@app.on_event("startup")
async def startup():
    init_dirs()
    logger.info("AI Bot Server started")


@app.on_event("shutdown")
async def shutdown():
    logger.info("AI Bot Server shutting down")


@app.get("/")
async def root():
    return FileResponse("static/index.html")


app.mount("/static", StaticFiles(directory="static"), name="static")


@app.get("/api/status")
async def status():
    return {
        "status": "online",
        "model": settings.ai_model,
        "memory_count": len(memory_manager.memories),
        "history_count": len(ai_service.conversation_history),
        "connection_count": len(active_connections),
        "api_configured": bool(settings.ai_api_key and settings.ai_api_key != "你的真实API密钥"),
    }


@app.get("/api/settings")
async def get_settings():
    """获取当前配置（隐藏API密钥）"""
    return {
        "ai_api_url": settings.ai_api_url,
        "ai_model": settings.ai_model,
        "api_key_configured": bool(settings.ai_api_key and settings.ai_api_key != "你的真实API密钥"),
    }


@app.post("/api/settings")
async def update_settings(data: dict):
    """更新配置"""
    try:
        if "ai_api_key" in data and data["ai_api_key"]:
            settings.update_api_key(data["ai_api_key"])
            # 重新初始化AI服务
            from services.ai import ai_service
            ai_service.__init__()
        
        if "ai_api_url" in data and data["ai_api_url"]:
            settings.update_api_url(data["ai_api_url"])
        
        if "ai_model" in data and data["ai_model"]:
            settings.update_model(data["ai_model"])
        
        logger.info("Settings updated successfully")
        return {"status": "ok", "message": "配置已更新"}
    except Exception as e:
        logger.error(f"Failed to update settings: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/chat")
async def chat(request: ChatRequest) -> ChatResponse:
    logger.info(f"Received chat request: {request.message[:50]}")
    response = ai_service.chat(request)
    logger.info(f"AI response: {response.reply[:50]}")
    return response


@app.post("/api/plan")
async def plan(request: PlanRequest) -> PlanResponse:
    logger.info(f"Received plan request: {request.goal[:50]}")
    response = ai_service.plan(request.goal, request.available_items, request.game_state)
    return PlanResponse(plan=response.actions, explanation=response.reply)


@app.post("/api/memory")
async def add_memory(content: str, importance: float = 1.0, location: str = None):
    memory_manager.add_memory(content, importance, location)
    return {"status": "ok"}


@app.get("/api/memory/search")
async def search_memory(q: str, limit: int = 5):
    results = memory_manager.search_memories(q, limit)
    return [r.model_dump() for r in results]


@app.get("/api/memory/recent")
async def recent_memory(limit: int = 10):
    results = memory_manager.get_recent_memories(limit)
    return [r.model_dump() for r in results]


@app.post("/api/memory/clear")
async def clear_memory():
    memory_manager.clear()
    return {"status": "ok", "message": "所有记忆已清空"}


@app.post("/api/state/game")
async def update_game_state(state: GameState):
    global current_game_state
    current_game_state = state
    logger.debug(f"Updated game state: [{state.x:.0f}, {state.y:.0f}, {state.z:.0f}]")
    await broadcast_state()
    return {"status": "ok"}


@app.post("/api/state/emotion")
async def update_emotion_state(state: EmotionState):
    global current_emotion_state
    current_emotion_state = state
    logger.debug(f"Updated emotion: hunger={state.hunger}, tiredness={state.tiredness}")
    await broadcast_state()
    return {"status": "ok"}


@app.post("/api/state/personality")
async def update_personality_state(state: PersonalityState):
    global current_personality_state
    current_personality_state = state
    logger.debug(f"Updated personality: diligence={state.diligence}")
    await broadcast_state()
    return {"status": "ok"}


async def broadcast_state():
    import json
    message = {
        "game_state": current_game_state.model_dump(),
        "emotion": current_emotion_state.model_dump(),
        "personality": current_personality_state.model_dump(),
    }
    for connection in active_connections:
        try:
            await connection.send_text(json.dumps(message))
        except Exception:
            pass


async def broadcast_chat(response):
    import json
    message = response.model_dump()
    logger.info(f"Broadcasting chat to {len(active_connections)} connections: {message.get('reply', '')[:50]}")
    for connection in active_connections:
        try:
            await connection.send_text(json.dumps(message))
            logger.info("Message sent successfully")
        except Exception as e:
            logger.error(f"Failed to send message: {e}")


@app.post("/api/history/clear")
async def clear_history():
    ai_service.clear_history()
    return {"status": "ok"}


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    active_connections.append(websocket)
    logger.info(f"WebSocket connection opened. Total: {len(active_connections)}")

    buffer = ""

    try:
        while True:
            data = await websocket.receive_text()
            buffer += data

            import json
            try:
                message = json.loads(buffer)
                logger.info(f"WebSocket received: {json.dumps(message)[:150]}")

                msg_type = message.get("type")

                if msg_type == "chat":
                    request = ChatRequest(
                        message=message.get("message", ""),
                        game_state=GameState(**message.get("game_state", {})) if message.get("game_state") else None,
                        emotion=EmotionState(**message.get("emotion", {})) if message.get("emotion") else None,
                        personality=PersonalityState(**message.get("personality", {})) if message.get("personality") else None,
                    )
                    response = ai_service.chat(request)
                    await broadcast_chat(response)

                elif msg_type == "state_update":
                    if message.get("game_state"):
                        await update_game_state(GameState(**message["game_state"]))
                    if message.get("emotion"):
                        await update_emotion_state(EmotionState(**message["emotion"]))
                    if message.get("personality"):
                        await update_personality_state(PersonalityState(**message["personality"]))
                    await websocket.send_text(json.dumps({"status": "ok"}))

                elif msg_type == "ping":
                    await websocket.send_text(json.dumps({"type": "pong"}))

                else:
                    logger.warning(f"Unknown message type: {msg_type}")

                buffer = ""

            except json.JSONDecodeError:
                logger.warning(f"JSON decode failed! Buffer length: {len(buffer)}, buffer content: {buffer[:200]}")
                logger.warning(f"Last received chunk: {data[:100]}")

    except WebSocketDisconnect:
        active_connections.remove(websocket)
        logger.info(f"WebSocket connection closed. Total: {len(active_connections)}")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        app,
        host=settings.server_host,
        port=settings.server_port,
        log_level=settings.log_level,
    )
