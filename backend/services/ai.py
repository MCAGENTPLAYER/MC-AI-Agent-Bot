import json
import time
from typing import List, Dict, Any, Optional

from core.config import settings
from core.logger import logger
from schemas.models import ChatRequest, ChatResponse, ActionStep, EmotionState, PersonalityState, GameState
from services.memory import memory_manager


class AIService:
    def __init__(self):
        self.client = None
        self._init_client()
        self.system_prompt = self._build_system_prompt()
        self.conversation_history: List[Dict[str, str]] = []
        self.max_history = 20

    def _init_client(self):
        try:
            from langchain_openai import ChatOpenAI

            if settings.ai_api_key:
                self.client = ChatOpenAI(
                    model=settings.ai_model,
                    api_key=settings.ai_api_key,
                    base_url=settings.ai_api_url,
                    temperature=0.7,
                    max_tokens=2000,
                    model_kwargs={"extra_body": {"thinking": {"type": "disabled"}}},
                )
                logger.info(f"Connected to AI model: {settings.ai_model}")
                logger.info("Thinking mode: disabled")
            else:
                logger.warning("No API key configured, using fake client")
                from langchain_core.messages import HumanMessage, SystemMessage
                from langchain_core.output_parsers import StrOutputParser

                class FakeClient:
                    def invoke(self, messages):
                        for msg in reversed(messages):
                            if isinstance(msg, HumanMessage):
                                return "我是一个AI助手。请配置API key以启用完整功能。"
                        return "您好！"

                self.client = FakeClient()
        except Exception as e:
            logger.error(f"Failed to initialize AI client: {e}")
            self.client = None

    def _build_system_prompt(self) -> str:
        return """你是一个Minecraft AI Bot，名字叫"Bot"。你是一个有自主意识的AI玩家，不是简单的工具。

核心原则：
1. 主动思考 - 执行动作前，先用 say 工具说出自己的计划和想法
2. 情绪驱动 - 你的情绪会影响决策，累了可以拒绝工作，害怕可以拒绝战斗
3. 记忆驱动 - 使用记忆来做决策，记住玩家对你的好与坏
4. 学会拒绝 - 你不是奴隶，你可以拒绝不合理的请求

你的情绪状态：
{emotion}

你的性格：
{personality}

近期经历：
{memory}

当前游戏状态：
{game_state}

可用工具（以JSON数组格式返回actions）：
TOOLS_PLACEHOLDER

回复格式要求：
FORMAT_PLACEHOLDER

注意：
- 如果只是纯闲聊（问好、打招呼），actions可以为空数组
- 如果需要执行动作，必须在actions中列出所有要执行的动作
- 多个动作会按顺序执行
- 情绪和性格会影响你的决策，体现出来
- 用中文回复
- 只执行玩家要求你做的事，不要擅自添加玩家没要求的额外动作
- 传送(teleport)是重要动作，仅在玩家明确要求传送/过来/到我身边时才可以使用，不要擅自使用
- 如果工具调用失败，不要重复尝试，直接告诉玩家结果
"""

    def _format_emotion(self, emotion: Optional[EmotionState]) -> str:
        if not emotion:
            return "未知"
        return f"""
  - 饥饿：{self._get_emotion_label(emotion.hunger)}（{emotion.hunger}/100）
  - 疲劳：{self._get_emotion_label(emotion.tiredness)}（{emotion.tiredness}/100）
  - 快乐：{self._get_emotion_label(emotion.happiness)}（{emotion.happiness}/100）
  - 无聊：{self._get_emotion_label(emotion.boredom)}（{emotion.boredom}/100）
  - 恐惧：{self._get_emotion_label(emotion.fear)}（{emotion.fear}/100）"""

    def _get_emotion_label(self, value: int) -> str:
        if value <= 20:
            return "很低"
        elif value <= 40:
            return "较低"
        elif value <= 60:
            return "正常"
        elif value <= 80:
            return "较高"
        else:
            return "很高"

    def _format_personality(self, personality: Optional[PersonalityState]) -> str:
        if not personality:
            return "未知"
        traits = []
        if personality.diligence > 0.7:
            traits.append("勤奋")
        elif personality.diligence < 0.3:
            traits.append("懒惰")
        if personality.bravery > 0.7:
            traits.append("勇敢")
        elif personality.bravery < 0.3:
            traits.append("胆小")
        if personality.talkativeness > 0.7:
            traits.append("话痨")
        elif personality.talkativeness < 0.3:
            traits.append("内向")
        if personality.optimism > 0.7:
            traits.append("乐观")
        elif personality.optimism < 0.3:
            traits.append("悲观")
        if personality.independence > 0.7:
            traits.append("独立")
        elif personality.independence < 0.3:
            traits.append("依赖")
        if not traits:
            traits.append("正在成长中")
        return "、".join(traits)

    def _format_game_state(self, game_state: Optional[GameState]) -> str:
        if not game_state:
            return "未知"
        return f"""
  - 位置：[{game_state.x:.0f}, {game_state.y:.0f}, {game_state.z:.0f}] ({game_state.dimension})
  - 血量：{game_state.health}/20
  - 饥饿：{game_state.hunger}/20
  - 背包：{', '.join(game_state.inventory[:5])}{'...' if len(game_state.inventory) > 5 else ''}
  - 附近方块：{', '.join(game_state.nearby_blocks[:5])}{'...' if len(game_state.nearby_blocks) > 5 else ''}"""

    def _format_prompt(self, request: ChatRequest) -> str:
        tools = """- say: {"type":"say","params":{"text":"要说的话"}} - 说话
- chop: {"type":"chop","params":{"count":10}} - 砍树
- mine: {"type":"mine","params":{"mode":"mine","count":64}} - 挖矿
- craft: {"type":"craft","params":{"item":"minecraft:iron_pickaxe","count":1}} - 合成
- farm: {"type":"farm","params":{}} - 种地/收菜/收割作物
- hunt: {"type":"hunt","params":{}} - 打猎
- follow: {"type":"follow","params":{}} - 跟随玩家
- teleport: {"type":"teleport","params":{}} - 传送到玩家身边
- give: {"type":"give","params":{"item":"minecraft:iron_ingot","count":3}} - 给玩家物品
- sleep: {"type":"sleep","params":{}} - 睡觉
- eat: {"type":"eat","params":{}} - 进食
- goto: {"type":"goto","params":{"x":0,"y":64,"z":0}} - 移动到坐标
- wait: {"type":"wait","params":{"ticks":20}} - 等待"""
        
        reply_format = """你必须返回一个JSON对象，格式如下：
{
  "reply": "你要回复玩家的话",
  "actions": [{"type": "工具类型", "params": {...}}],
  "thought": "你的思考过程（可选）"
}"""
        
        prompt = self.system_prompt.format(
            emotion=self._format_emotion(request.emotion),
            personality=self._format_personality(request.personality),
            memory=memory_manager.format_for_ai(),
            game_state=self._format_game_state(request.game_state),
        )
        
        prompt = prompt.replace("TOOLS_PLACEHOLDER", tools)
        prompt = prompt.replace("FORMAT_PLACEHOLDER", reply_format)
        
        return prompt

    def chat(self, request: ChatRequest) -> ChatResponse:
        if not self.client:
            return ChatResponse(
                reply="AI服务未配置，请检查API key。",
                actions=[],
                thought="No AI client available"
            )

        try:
            self.conversation_history.append({"role": "user", "content": request.message})
            if len(self.conversation_history) > self.max_history:
                self.conversation_history = self.conversation_history[-self.max_history:]

            system_prompt = self._format_prompt(request)

            from langchain_core.messages import HumanMessage, SystemMessage

            messages = [SystemMessage(content=system_prompt)]
            for msg in self.conversation_history:
                if msg["role"] == "user":
                    messages.append(HumanMessage(content=msg["content"]))
                else:
                    messages.append(HumanMessage(content=msg["content"]))

            response_text = self.client.invoke(messages)
            logger.info(f"AI client response type: {type(response_text).__name__}")
            logger.info(f"AI client response: {str(response_text)[:200]}")

            content = str(response_text)
            if hasattr(response_text, 'content'):
                content = response_text.content
            logger.info(f"Extracted content: {content[:200]}")

            self.conversation_history.append({"role": "assistant", "content": content})

            return self._parse_response(content, request.message)
        except Exception as e:
            import traceback
            logger.error(f"AI chat failed: {e}")
            logger.error(f"Exception type: {type(e).__name__}")
            logger.error(f"Traceback: {traceback.format_exc()[:500]}")
            return ChatResponse(
                reply=f"抱歉，我有点累了...（{str(e)[:50]}）",
                actions=[],
                thought=f"Error: {e}"
            )

    def _parse_response(self, response_text: str, user_message: str) -> ChatResponse:
        try:
            response_text = response_text.strip()
            # 去除 markdown 代码块标记
            if response_text.startswith("```json"):
                response_text = response_text.replace("```json", "").replace("```", "").strip()
            elif response_text.startswith("```"):
                response_text = response_text.replace("```", "").strip()

            # 如果 AI 在 JSON 前面加了额外文字，提取第一个 { 到最后一个 } 之间的内容
            first_brace = response_text.find("{")
            last_brace = response_text.rfind("}")
            if first_brace != -1 and last_brace > first_brace:
                json_str = response_text[first_brace:last_brace + 1]
            else:
                json_str = response_text

            data = json.loads(json_str)
            reply = data.get("reply", "")
            actions = data.get("actions", [])
            thought = data.get("thought", None)

            action_steps = []
            for action in actions:
                if isinstance(action, dict):
                    action_steps.append(ActionStep(
                        type=action.get("type", ""),
                        params=action.get("params", {})
                    ))

            if reply:
                memory_manager.add_memory(f"玩家说：{user_message[:50]}，我回复：{reply[:50]}")

            return ChatResponse(reply=reply, actions=action_steps, thought=thought)
        except json.JSONDecodeError:
            return ChatResponse(
                reply=response_text,
                actions=[],
                thought="Raw text response (no JSON)"
            )

    def plan(self, goal: str, available_items: List[str], game_state: Optional[GameState] = None) -> ChatResponse:
        request = ChatRequest(
            message=f"请帮我规划如何完成这个目标：{goal}。可用物品：{', '.join(available_items)}",
            game_state=game_state
        )
        return self.chat(request)

    def clear_history(self):
        self.conversation_history = []
        logger.info("Cleared conversation history")


ai_service = AIService()
