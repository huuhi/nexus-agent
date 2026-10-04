# skills/ — 技能目录（官方技能）+ 技能库（用户技能）

> 对应决策 **D3**（`重构计划.md` §七）与任务 **P2-1 / P2-2**；用户技能为 2026-10-04 新增。
> 本目录放**官方**技能。**用户自己上传/生成的技能不在这里**，见下方「用户技能」一节。
> 实现见 `nexus-agent-service/.../skills/`：
> - `OfficialSkillSource` —— 扫本目录
> - `SkillLoader` —— 官方 + 用户合并成一份可用清单
> - `SkillPackageParser` —— 上传包解析与安全校验
> - `SkillGeneratePrompt` —— AI 生成技能的提示词

## 一、官方技能：目录约定（与 Claude Code 一致）

```
skills/                     ← 根目录（nexus.agent.skill.root-dir，默认即本目录）
└── my-skill/               ← 一个子目录 = 一个技能
    ├── SKILL.md            ← 必需；YAML frontmatter + 给模型看的正文
    ├── notes.txt           ← 可选文档资源；read_resource 可读（加载时读入内存，不宜过大）
    └── scripts/xxx.py      ← 可选脚本目录；read_resource 读不到（见下方「scripts 目录」）
```

**SKILL.md 格式**：

```markdown
---
name: my-skill
description: 一句话说明「什么时候」该用这个技能（模型靠它决定是否激活）
---

# 技能正文

这里写给模型看的完整步骤……
```

- `name`：技能名，全局唯一；请求里的 `skills: ["my-skill"]` 按它匹配。
- `description`：写给**模型**看的触发条件描述，写清楚「什么场景用、什么场景不要用」。
- 正文：激活后模型拿到的完整指令（`activate_skill` 工具的返回值）。
- 没有 `SKILL.md` 的子目录会被**静默跳过**（日志 DEBUG 级）。

### scripts 目录（与文档资源的区别）

`FileSystemSkillLoader` **刻意排除** `scripts/` 子目录，`read_resource` 读不到它。
库的约定是「脚本供**执行**、文档供**阅读**」：

| 内容 | 放哪 | 模型怎么用 |
|---|---|---|
| 给模型读的参考文档、模板、数据 | 技能目录任意位置（`scripts/` 除外） | `read_resource(skillName, relativePath)` |
| 供沙盒执行的脚本 | `scripts/` 子目录 | 模型无法直接读取；如需执行，让技能正文指示模型通过沙盒自行创建/写入脚本 |

文档资源在**加载时即读入内存**（随 60s 缓存刷新），单个文件不宜过大。

## 运行机制（对模型暴露的两个工具）

1. 启动后 `SkillLoader` 扫描根目录，缓存结果（默认 60s，`refresh-interval` 可配）；
2. 每次对话，可用技能清单注入系统提示词的 `{{runtimeCapabilities}}` 占位符；
3. 模型按需调用 `activate_skill(skillName)` 取得技能正文，严格按步骤执行；
4. 技能引用的资源文件用 `read_resource(skillName, relativePath)` 读取。

**请求侧行为**（`ChatDTO.skills`）：

| 请求 | 效果 |
|---|---|
| 不传 / 空数组 | 启用**全部**技能（客户端不必先知道有哪些） |
| `["my-skill"]` | 只启用指定的；名字不存在时**只告警不报错**（技能可能刚被删除，不该让对话失败） |

## 官方技能的配置项（`nexus.agent.skill.*`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关。关闭后官方与用户技能**都**不生效，等价于无技能 |
| `root-dir` | `skills` | **仅官方技能**的根目录；支持 `~` 与相对路径（相对应用工作目录） |
| `refresh-interval` | `60s` | **仅官方技能**的扫描缓存时长。新增官方技能最多延迟这段时间生效，**无需重启**。设 `0s` 则每次请求重扫。用户技能实时查库，不受它影响 |

完整示例见 `application-dev.yml.example` / `application-prod.yml` 的 `nexus.agent.skill` 段。

> ⚠️ **`root-dir` 是相对「应用工作目录」解析的**，不同启动方式工作目录不同：
>
> | 启动方式 | 工作目录 | 默认 `skills` 实际指向 |
> |---|---|---|
> | IDE 直接跑启动类（项目根为 working dir） | 仓库根 | ✅ `仓库根/skills` |
> | `mvn spring-boot:run -pl nexus-agent-web` | 模块目录 | ❌ `nexus-agent-web/skills`（不存在 → 静默降级为无技能） |
> | `java -jar nexus-agent-web/target/*.jar` | 执行命令的目录 | 视执行位置而定 |
>
> 因此**换启动方式后如果发现技能「消失」**，先看启动日志里的
> `Skill 目录不存在，Skill 能力为空：<解析出的绝对路径>`，再把 `root-dir` 改成绝对路径。
> 这个日志每个进程只打一次（避免刷屏）。

## 安全说明（P2-2 验收项）

- **无路径穿越风险**：`read_resource` 不是运行时拼路径读文件，而是从技能加载时
  **预先索引的资源清单**里按名称匹配（`langchain4j-skills` 的
  `ReadResourceToolExecutor` 实现）。模型传 `../../etc/passwd` 之类只会匹配失败，
  返回可用资源列表——读不到根目录之外的任何文件。
- **目录内容即信任边界**：扫描的是服务端本地目录，内容完全由部署者控制。
- ⚠️ **绝对不要把用户可写的上传目录配成 `root-dir`** —— 用户技能走的是
  数据库 + 内存装配（见下一节），**不解压落盘**，正是因为这条。

---

## 二、用户技能（2026-10-04 新增，对应 minmax 的「技能」页）

### 2.1 为什么存数据库而不是解压到目录

常见做法是把用户上传的 zip 解压到 `skills/users/<userId>/`。那样要处理四件事：
**路径穿越（zip slip）、zip 炸弹、删除时机、多用户目录隔离**。

而 `langchain4j-skills` 的 `Skills.from(Collection<? extends Skill>)` 接受任意
`Skill` 实现 —— 用 `DefaultSkill.builder()` 可以在内存里直接造一个技能，
正文和资源（`DefaultSkillResource`）一起装上。

于是**落盘这一步被整个消掉**，上面三个安全问题也就不存在了。
所以 `SkillLoader` 的类型从具体的 `FileSystemSkill` 放宽成了接口 `Skill`：
官方技能来自文件，用户技能来自内存，只有共同父接口能同时装下两者。

| | 官方技能 | 用户技能 |
|---|---|---|
| 存储 | 本目录（部署期放进 git） | `user_skill` 表（`docs/sql/010`） |
| 运行时 | `FileSystemSkillLoader` 扫目录 | `UserSkillServiceImpl.toMemorySkill` 转 `DefaultSkill` |
| 刷新 | TTL 缓存（默认 60s） | **实时查库**（上传完立刻生效） |
| 可见性 | 所有人 | `PRIVATE`（自己）/ `PUBLIC`（社区共享） |
| 可写 | 只能改文件 | 作者可改/删/下架 |

### 2.2 技能包格式

与 Claude Code / Anthropic Agent Skills 一致：

```
my-skill.zip
├── SKILL.md        必需。YAML frontmatter 提供 name / description
├── notes.md         可选。read_resource 能读到
└── scripts/x.py     被刻意忽略（见下方说明）
```

单文件 `.md` 也允许（就是 SKILL.md 本身），`.skill` 按 zip 处理。

`scripts/` 被忽略的原因：库刻意不索引它（`read_resource` 读不到），
约定是「**脚本供执行、文档供阅读**」。存进去也只是死数据，
反而会让人误以为上传脚本就能跑 —— 需要执行时让技能正文指示模型通过沙盒自行创建。

### 2.3 上传是两步的

1. `POST /api/skill/upload` —— **只解析不落库**，返回草稿（name / description / content / resources）；
2. 用户确认后 `POST /api/skill/save` —— 才真正入库。

理由：解析可能失败（缺 frontmatter、名字被占），而且让人有机会看清模型到底写了什么。
AI 生成（`/ai-generate`）同理，也是先返回草稿。

### 2.4 AI 生成技能

`POST /api/skill/ai-generate` 用**系统默认模型**（注入的 `ChatModel`）写 SKILL.md，
提示词见 `SkillGeneratePrompt.SYSTEM`。两个要点：

- 要求模型输出**纯 Markdown 原文**（不带代码块围栏），这样生成结果能走
  **与上传完全相同的校验路径** —— 模型不听话（比如起名带中文）会被同一个解析器拦下来；
- 可选 `referenceResources`：把用户自己的模板/参考文档传进来。
  不传的话模型只能凭空编内容，生成的技能没什么用。

### 2.5 安全边界

| 风险 | 处理 |
|---|---|
| zip slip（路径穿越） | 条目名含 `..` / 绝对路径 / 反斜杠 → **整包拒绝** |
| zip 炸弹 | 条目数 ≤200、单条目 ≤1MB、总解压 ≤4MB；**用实际读到的字节计数**，不信 zip 头里声明的 size（可伪造） |
| 可执行内容 | 只收白名单文本扩展名（md/txt/json/yaml/yml/csv/html/xml）；`scripts/` 直接忽略 |
| 技能名注入 | 必须匹配 `^[a-z0-9][a-z0-9-]{0,63}$`，且**全局唯一**（模型 `activate_skill` 只认名字，重名会让路由变糊） |
| 越权 | 改/删只能操作自己的；私有技能对别人等同于不存在（返回 404 而非 403，不泄露「它存在」） |

⚠️ **这里不防「技能内容有害」**。技能正文只是提示词，模型读它然后行动 ——
真正的行为边界在工具层（`ToolCallGuard` 的幂等、沙盒隔离、配额）。
给社区技能开放之前，这一点要清楚。

### 2.6 配置项（`nexus.agent.skill.*`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关。关闭后官方与用户技能**都**不生效 |
| `root-dir` | `skills` | **仅官方技能**的根目录；支持 `~` 与相对路径（相对应用工作目录） |
| `refresh-interval` | `60s` | **仅官方技能**的扫描缓存。用户技能实时查库，不需要刷新 |

上传大小：Spring 的 `spring.servlet.multipart.max-file-size: 8MB`
（默认值只有 1MB，技能包常要带模板会被拒），`SkillController` 里再叠一层 8MB 校验。

---

## 三、内置示例：verify-skill

`verify-skill/` 是一个**冒烟测试用**的技能：问它「今天的暗号是什么」，
正确行为是回答里包含 `BANANA-7731`。用来快速验证整条链路
（扫描 → 提示词注入 → activate_skill → 按步骤作答）是否通。

```bash
# 冒烟：对话接口里带 skills 参数（或不带，默认启用全部）
curl -N -X POST http://localhost:8080/api/chat \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"今天的暗号是什么"}],"skills":["verify-skill"]}'
# 期望回答包含 BANANA-7731
```

## 写一个好技能的要点

1. `description` 写「**何时用**」而不是「是什么」——模型靠它做路由决策；
2. 步骤可执行、可判定：明确「必须做什么、禁止做什么」；
3. 给模型读的参考文档/模板放技能目录内（`scripts/` 除外），技能正文里写清相对路径，模型会自己 `read_resource`；
4. 一个技能只干一件事；贪多的技能会让模型执行走样。
