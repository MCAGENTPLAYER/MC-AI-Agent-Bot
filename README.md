# AI Bot - Minecraft Forge 模组
# AI Bot - Minecraft Forge Mod

一个为 Minecraft 1.20.1 Forge 设计的 AI 机器人模组，支持两种工作模式。
An AI robot mod designed for Minecraft 1.20.1 Forge, supporting two working modes.

## 模式介绍
## Mode Introduction

### 内置 AI 模式（local，默认）
### Built-in AI Mode (local, default)
- **无需安装后端**，配置 API Key 即可使用
  - **No backend required**, just configure API Key to use
- 直接调用 DeepSeek / OpenAI 兼容 API
  - Directly call DeepSeek / OpenAI compatible API
- 全部工具功能完整可用（砍树/挖矿/合成/烹饪/种地等）
  - All tools fully functional (chop trees/mine/craft/cook/farm, etc.)
- 保留最近 10 轮对话上下文
  - Keep last 10 rounds of conversation context

### 外接模式（remote）
### Remote Mode (remote)
- 需要运行 Python 后端服务
  - Requires running Python backend service
- 额外提供：情绪系统、性格系统、情景记忆（语义搜索）、实时状态广播
  - Additional features: emotion system, personality system, episodic memory (semantic search), real-time status broadcasting

## 前置依赖
## Prerequisites

| 依赖 | 版本 | 说明 |
| Dependency | Version | Description |
|------|------|------|
| [Minecraft Forge](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) | 47.4.20+ | 必需 / Required |

### 模组兼容性
### Mod Compatibility

烹饪功能支持 [森罗物语](https://www.curseforge.com/minecraft/mc-mods/kaleidoscope-cookery) 模组（炒锅/炖锅/砧板），没有该模组时烹饪功能不可用，不影响其他功能。
The cooking feature supports the [Kaleidoscope Cookery](https://www.curseforge.com/minecraft/mc-mods/kaleidoscope-cookery) mod (wok/stockpot/chopping board). Without this mod, the cooking feature will not be available, but other functions are not affected.

## 快速开始
## Quick Start

### 1. 安装模组
### 1. Install the Mod
将构建好的 `ai_bot-1.0.4.jar` 放入 `.minecraft/versions/<你的版本>/mods/` 文件夹。
Place the built `ai_bot-1.0.4.jar` into `.minecraft/versions/<your_version>/mods/` folder.

### 2. 配置 API Key
### 2. Configure API Key
在 `.minecraft/ai_bot/config.json` 中配置：
Configure in `.minecraft/ai_bot/config.json`:

```json
{
  "ai_mode": "local",
  "api_key": "sk-yourDeepSeekKey",
  "model": "deepseek-v4-flash",
  "max_history": 20
}
```

### 3. 进入游戏
### 3. Enter the Game
使用 `/summon ai_bot:ai_bot` 召唤机器人，在聊天框中对它说话即可。
Use `/summon ai_bot:ai_bot` to summon the bot, then talk to it in the chat box.

## 工具列表
## Tool List

| 类别 | 工具 |
| Category | Tools |
|------|------|
| 基础动作 / Basic Actions | `chop_tree` `mine_block` `follow` `goto` `farm` `hunt` `eat` |
| 合成烹饪 / Craft & Cook | `craft_item` `cook` |
| 物品状态 / Item Status | `check_inventory` `give_item` `check_status` |
| 任务链 / Task Chain | `plan_tasks` `check_chain` `resume_chain` `skip_step` `cancel_chain` |
| 任务书 / Quest Book | `scan_quests` `do_task` `check_tasks` `get_next_doable` |
| 环境识别 / Environment | `scan_environment` `scan_recipes` `identify` |
| 社交 / Social | `say` `ask` `need` |

## 从源码构建
## Build from Source

### 1. 构建
### 1. Build

```bash
./gradlew build
```

构建产物：
Build output:
- `build/libs/ai_bot-<version>.jar` — 普通包，直接放入 `mods` 即可使用
  - Normal package, place directly in `mods` folder to use

## 开源说明
## License

MIT License

Copyright (c) 2026

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.