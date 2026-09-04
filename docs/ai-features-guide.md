# PixelPlayer AI 功能使用指南

> 整理日期：2026-09-02。内容基于 `app/src/main` 代码梳理（设置 / 库 / 每日混音 / 播放器歌词），覆盖已接入的用户入口与后台机制。

---

## 一、功能总览

| 功能 | 入口 | 作用 | 是否需要 API Key |
|---|---|---|---|
| **AI 歌单生成（完整版）** | 库 → 播放列表 → 新建 → 「AI 创建」 | 按描述从本地曲库生成歌单并**保存** | 是 |
| **AI 歌单生成（快速版）** | 每日混音 → 右上菜单 → AI | 按描述生成歌单并**直接播放** | 是 |
| **AI 精炼每日混音** | 每日混音 → 输入想法 → 应用 | 用一句话重新打磨当前每日混音 | 是 |
| **AI 歌词翻译** | 播放器 → 歌词 → 更多 → Translate via AI | 歌词逐行双语翻译 | 是 |
| AI 元数据 / 标签 / 情绪分析等 | 引擎已支持（`AiSystemPromptType`） | 后台能力，UI 暂未开放入口 | — |

> 所有 AI 功能都**只从你的本地曲库选歌/处理**，不会引入外部曲目。

---

## 二、前置准备：配置 AI（一次配置，全功能可用）

### 2.1 进入设置
`设置 → AI 集成`（Settings → AI Integration）

### 2.2 选择服务商（Provider）
| 服务商 | 说明 / 获取 Key 的渠道 |
|---|---|
| Google Gemini | Google AI Studio（aistudio.google.com） |
| DeepSeek | DeepSeek Platform（api.deepseek.com） |
| Groq | Groq Console（console.groq.com） |
| Mistral | Mistral AI Platform（console.mistral.ai） |
| NVIDIA NIM | NVIDIA Build（build.nvidia.com） |
| Kimi (Moonshot) | Moonshot AI Platform（platform.moonshot.cn） |
| Zhipu GLM | Zhipu AI Open Platform（bigmodel.cn） |
| OpenAI | OpenAI Platform（platform.openai.com） |
| OpenRouter | openrouter.ai（聚合多家模型） |
| Ollama | 本地/云端 Ollama |
| 火山引擎 Ark | ark.cn-beijing.volces.com |
| Custom Provider | 自建/自托管 OpenAI 兼容端点，**需额外填 Base URL**，可留空 Key |

### 2.3 API Key、模型与 Base URL
- **API Key**：按服务商分别保存，互不干扰。
- **模型（AI Model）**：填入 Key 后会自动拉取可用模型列表（Gemini 会自动过滤非对话模型，如 embedding/图像/音频类）；也支持**手动输入模型名或端点 ID**。
- **Base URL**：仅 Custom Provider 显示，例如 `https://api.example.com/v1`。

### 2.4 连接测试
填好 Key 后点 **Test Connection**，可立即验证 Key 和网络是否可用。

### 2.5 高级参数（按需调整）
| 参数 | 作用 |
|---|---|
| Safe Token Limit | 开启后发给 AI 的歌曲数减半，省 token |
| System Prompt | 自定义 AI 的角色设定（可一键重置默认） |
| Temperature (0–2) | 越低越稳定，越高越有创意 |
| Top P (0–1) | 核采样，越高候选 token 越多样 |
| Top K (1–100) | 只考虑概率最高的 K 个 token |
| Max Tokens (128–8192) | 输出最大长度，越高越贵 |
| Presence / Frequency Penalty | 抑制重复话题/重复表达 |
| Sample Size (10–120) | 每次发歌给 AI 的数量，越多上下文越好但越贵 |
| Include Extended Fields | 是否把专辑/时长/收藏等信息也发给 AI |

### 2.6 用量统计
设置内 **AI Usage Report** 可查看累计 token 用量（Prompt / Output / Thought）、活动日志，并可清空记录。

---

## 三、AI 歌单生成（完整版，保存为歌单）

### 入口
1. 打开 **库 → 播放列表**。
2. 点 **新建歌单**。
3. 选择 **AI 创建**（未配置 API Key 时此项置灰，可点「设置 API Key」直接跳转）。

### 可填参数
| 分组 | 参数 | 说明 |
|---|---|---|
| 意图 | 歌单名称（可选） | 留空则自动命名 |
| 意图 | 核心描述 | 如「黄昏海边开车听的暖调合成器」 |
| 方向 | 情绪 / 活动 / 年代 | 三组 chip 单选，每组都支持「自定义」输入 |
| 策展引擎 | 能量 1–5 | 1=舒缓，5=高能快节奏 |
| 策展引擎 | 探索度 1–5 | 1=最常听的收藏，5=冷门遗珠 |
| 策展引擎 | 歌单大小（最小/最大） | 默认 12–24，合法范围 5–150 |
| 过滤器 | 优先风格 / 排除风格 | 逗号分隔，如 synthwave, indie pop |
| 过滤器 | 偏好语言 | 如 English、Spanish、instrumental |
| 过滤器 | 优先收藏 / 排除脏标 | 开关 |

页面底部有 **Prompt 预览**，实时拼装你最终发给 AI 的描述，生成前可检查。

### 生成结果
- 生成成功后自动**保存为歌单**（标记为 AI 生成）。
- **命名规则**：优先用你填的名称；未填时从描述自动提取（取前两个有意义的词，如 `Sunset Drive`），或按关键词兜底（Workout Mix / Focus Flow / Chill Vibes / Party Mix / Night Vibes / Road Trip / Love Notes / Blue Hour / Fresh Mix）；同名自动加序号（如 `Chill Vibes 2`）。

### 使用建议
- 描述给足**场景 + 风格 + 情绪**三要素，效果最稳。
- 想更「熟悉」就把探索度调低、开「优先收藏」；想发现冷门就调高探索度。

---

## 四、每日混音（Daily Mix）与 AI 快速歌单

### 背景
Daily Mix 是本机基于收听习惯（播放次数、评分等）**离线打分**生成的混音，AI 复用它筛出的候选，减少 token 消耗。

### 两个 AI 入口
1. **快速生成并播放**：每日混音页 → 右上菜单 → **AI**，在底部弹窗输入描述 + 最小/最大歌曲数（默认 5–15），生成后**直接开始播放**，不保存为歌单。
2. **精炼当前混音**：每日混音页输入一句想法（如「更欢快一点」）点应用，AI 基于当前混音重新筛歌，替换当前每日混音。

### 失败重试
出错时会显示可点击的错误提示条，点一下即可重试。

---

## 五、AI 歌词翻译

### 入口
播放器 → 歌词面板 → 更多（…）菜单 → **Translate via AI**。

### 行为
- 把当前歌词**翻译成界面所在语言**，**逐行对照、保留原时间戳**，可边听边看。
- 若歌词已翻译、或本就在目标语言，会给出提示而不重复翻译。
- 未配置 API 时提示「API 未配置」。

---

## 六、后台机制（了解即可）

| 机制 | 说明 |
|---|---|
| 结果缓存 | 相同请求 30 分钟内命中缓存直接返回，省 token 和省时间 |
| 请求超时 | 单次最多等 60 秒，超时报「服务慢，请重试」 |
| 服务商自动切换 | 主服务商失败时自动尝试备用服务商链 |
| 模型自动恢复 | 模型下架/不可用时，自动拉取可用模型并切回支持的模型 |
| 服务商冷却 | 服务商级故障后冷却 5 分钟，避免反复重试 |
| 后台任务 | 生成类任务可走 WorkManager 后台执行；**播放中自动推迟**，避免与播放抢 CPU/内存 |
| 用量记录 | 每次调用估算并记录 token 用量，可在设置查看 |

### 常见错误提示解读
| 提示含义 | 处理方式 |
|---|---|
| 未配置/无效 API Key | 去「设置 → AI 集成」检查 Key |
| 无额度 / 配额不足 | 检查服务商账户余额或更换 Key |
| 模型不可用 | 应用会自动切换，也可在设置手动换模型 |
| 请求超时 | 服务商繁忙，稍后重试 |
| 限流（429） | 等约 30 秒再试 |
| 内容被安全过滤 | 换个说法描述 |
| 返回格式异常 | 换更强大的模型（如 Gemini Pro / 更大参数模型） |

---

## 七、快速上手清单
1. 设置 → AI 集成 → 选服务商 → 填 API Key →（可选）选模型 → Test Connection。
2. 想存一个歌单：库 → 新建歌单 → AI 创建 → 填描述 → Generate。
3. 想立刻听：每日混音 → 菜单 → AI → 填描述 → 生成即播。
4. 想看外文歌词：播放器 → 歌词 → 更多 → Translate via AI。
