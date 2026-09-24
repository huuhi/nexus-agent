# skills/ — 技能目录（本地目录扫描方案）

> 对应决策 **D3**（`重构计划.md` §七）与任务 **P2-1 / P2-2**。
> 实现见 `nexus-agent-service/.../skills/SkillLoader.java`，基于 `langchain4j-skills` 的
> `FileSystemSkillLoader`。

## 目录约定（与 Claude Code 一致）

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
2. 每次对话，可用技能清单注入系统提示词的 `{{availableSkills}}` 占位符；
3. 模型按需调用 `activate_skill(skillName)` 取得技能正文，严格按步骤执行；
4. 技能引用的资源文件用 `read_resource(skillName, relativePath)` 读取。

**请求侧行为**（`ChatDTO.skills`）：

| 请求 | 效果 |
|---|---|
| 不传 / 空数组 | 启用**全部**技能（客户端不必先知道有哪些） |
| `["my-skill"]` | 只启用指定的；名字不存在时**只告警不报错**（技能可能刚被删除，不该让对话失败） |

## 配置项（`nexus.agent.skill.*`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关闭后忽略请求里的 skills 参数，等价于无技能 |
| `root-dir` | `skills` | 根目录；支持 `~` 与相对路径（相对应用工作目录） |
| `refresh-interval` | `60s` | 扫描缓存时长；新增技能最多延迟这段时间生效，**无需重启**。设 `0s` 则每次请求重扫 |

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
  **不要**把用户可写的上传目录配成 `root-dir`。
- 将来支持用户自定义技能时（`<root>/users/<userId>`），上传侧需要额外的
  内容校验；`SkillLoader` 本身无需改动（见其 `scan()` 注释）。

## 内置示例：verify-skill

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
