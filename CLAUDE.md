**# CLAUDE.md**

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**nexus-agent** is an AI Agent platform built on Spring Boot 3.5 and LangChain4j. It provides:
- Multi-model LLM chat with SSE streaming (OpenAI-compatible APIs)
- RAG knowledge base with pgvector embeddings
- MCP (Model Context Protocol) tool integration
- Sandboxed code execution (Python/FastAPI service)
- Multimodal file uploads (images, documents) via Aliyun OSS
- User authentication with JWT and per-user API key encryption
- Long-term user memory with pgvector similarity search
- WebSocket notifications for real-time client updates

## Tech Stack

- **Java 21**, Spring Boot 3.5.13 with virtual threads
- **LangChain4j** 1.12.1 (OpenAI, pgvector, MCP)
- **MyBatis-Plus** 3.5.6 (ORM) with XML mappers
- **PostgreSQL** (relational + pgvector extension)
- **Redis** (caching, distributed locks, Stream for async tasks)
- **Aliyun OSS** (file/image storage)
- **JWT** (jjwt 0.12.5)
- **Hutool** 5.8.27, **Lombok**
- **Python/FastAPI** sandbox service (`nexus_agent_box/`, uv-managed)

## Maven Multi-Module Structure

```
nexus-agent (parent pom)
├── nexus-agent-common    -- JWT utils, enums, exceptions, ThreadLocal contexts, constants
├── nexus-agent-domain    -- Entities, DTOs, VOs, Result envelope, validation records
├── nexus-agent-mapper   -- MyBatis-Plus mapper interfaces + XML (12 mappers)
├── nexus-agent-service   -- LangChain4j integration, chat engine, tools, configs, factories
└── nexus-agent-web       -- REST controllers, main application, tests
```

**Dependency chain:** `web` → `service` → `mapper` → `domain` → `common`

## Build / Test / Run

```bash
# Build all modules
mvn clean package

# Build skipping tests
mvn clean package -DskipTests

# Run all tests
mvn test

# Run application (port 8080)
mvn spring-boot:run -pl nexus-agent-web

# Run as JAR
java -jar nexus-agent-web/target/nexus-agent-web-0.0.1-SNAPSHOT.jar
```

**Default profile:** `prod` (from `application.yml`). `application-dev.yml` is gitignored for local overrides. Override with `--spring.profiles.active=dev`.

**Tests are in** `nexus-agent-web/src/test/java/`: `BoxToolTest`, `ModelTest`, `SecretTest`, `SortTest`, `WebClientTest`.

**Sandbox service** (separate FastAPI app):
```bash
cd nexus_agent_box
uv run main.py   # or: docker-compose up
```
Sandbox runs on `localhost:8000` by default.

## Environment Variables

From `application-prod.yml` and `EncryptorFactory`:

| Variable | Source | Purpose |
|---|---|---|
| `MOONSHOT` | `application-prod.yml` | Moonshot API key (default chat model) |
| `DEEPSEEK` | `application-prod.yml` | DeepSeek API key (streaming chat model) |
| `AI_KEY` | `application-prod.yml` | Alibaba DashScope key (embedding model) |
| `DOCKER_IP` | `application-prod.yml` | PostgreSQL/Redis host |
| `MAIL_USERNAME` | `application-prod.yml` | QQ SMTP username for email codes |
| `MAIL_PASSWORD` | `application-prod.yml` | QQ SMTP password |
| `JWT_SECRET` | `JwtUtil` | Base64-encoded HMAC-SHA256 key |
| `API_KEY_SECRET` | `EncryptorFactory` | Spring Security Crypto key for user API key encryption |
| `BASE_URL` | `WebClientConfig` | Sandbox service URL (default `http://localhost:8000`) |

## Key Architecture

### Chat Flow

```
POST /chat/stream (ChatDTO)
  → ChatServiceImpl.chat()
    → UserContextHolder.getUserId() (from JWT interceptor)
    → ChatContextFactory.create()
      → Resolve StreamingChatModel (user's config or default)
      → Build AiServices<ChatAssistant> with tools, memory, MCP
    → ChatMessageConverter.toContents() (text/file/image → List<Content>)
    → ChatAssistant.chat(contents, sessionId) → TokenStream
    → SseResponseConverter handles streaming:
       - onPartialThinking → SSE "message" type=THINK
       - onPartialResponse → SSE "message" type=CONTENT
       - onPartialToolCall → SSE "message" type=TOOL_EXECUTION
       - onToolExecuted → SSE "message" type=TOOL_EXECUTION_RESULT
       - onCompleteResponse → SSE "finish" event
       - New sessions get "session_id" event + async title generation
```

**SSE timeout:** 120 seconds (configured in `SseEmitter` constructor).

### Model Selection

1. User's API config stored in `user_config.llm_api_token` (JSONB, encrypted)
2. `ChatContextFactory.createModel()` matches by `modelDTO.id()` or falls back to default
3. Supports per-model `thinking` toggle (maps to `enable_thinking` custom param)
4. `EncryptorFactory.text(salt).decrypt()` decrypts user API keys
5. Default models configured via `langchain4j.open-ai.*` in `application-prod.yml`

### Default Models

| Model | Provider | Config Key |
|---|---|---|
| `deepseek-v4-flash` (streaming chat) | DeepSeek | `langchain4j.open-ai.streaming-chat-model` |
| `moonshot-v1-8k` (sync chat) | Moonshot | `langchain4j.open-ai.chat-model` |
| `text-embedding-v4` (embeddings) | Alibaba DashScope | `langchain4j.open-ai.embedding-model` |

### Tools

Registered in `ChatContextFactory`: always-present + conditional.

**Always:**
- `BoxTool` — sandbox operations: `create_box`, `delete_box`, `upload_file`, `download_file`, `list_dir`, `check_file_exist`, `create_write_file`, `execute_code`, `execute_cmd`
- `LogTool` — `record_log` for AI to log system feedback

**Conditional (via `chatDTO.enableRag()`):**
- `MemoryTool.searchUserMemory()` — vector similarity search on user memory table
- `MemoryTool.saveLongMemory()` — save user preference/memory to pgvector
- `MemoryTool.ragSearch()` — search knowledge base embeddings

**Conditional (via `chatDTO.MCPs()`):**
- MCP tool providers from `McpInformation` table — `StreamableHttpMcpTransport`-based clients

**Safe execution:** All tool methods wrap via `SafeExecuteToolHandler` to catch errors without breaking the stream.

### Memory System

**Chat memory:** `PgChatMemoryStore` implements `ChatMemoryStore`:
- `getMessages()` — deserializes from `chat_memory` table
- `updateMessages()` — delta-only inserts (compares count), attaches file metadata via `MessageMetadataContext`
- Uses Redis Stream (`memory.stream`) for async long-term memory processing (now disabled)

**Long-term memory:** `UserMemoryService`:
- Async `saveMemory()` — embeds content and saves to `user_memory` table
- `searchMemory()` — pgvector cosine similarity (`<=>` operator) with min score filter
- Memory consolidation via `handleLongMemory` thread — polls Redis Stream, calls LLM to extract/update/delete memories with structured JSON output
- Uses `moonshot-v1-128k` with JSON response format for memory extraction

### MCP Integration

- `McpInformationService` manages MCP server configs (URL, headers as JSONB)
- `StreamableHttpMcpTransport` for MCP communication
- MCP clients discovered from a remote service endpoint, saved to local DB
- Health check on connection — marks unavailable servers on failure

### WebSocket

- Endpoint: `/ws/{userId}` (Jakarta WebSocket, `ServerEndpointExporter`)
- ConcurrentHashMap-based session store
- Used for real-time push to clients (e.g., notifications)

### Sandbox Service

- `nexus_agent_box/` — Python/FastAPI app managed with `uv`
- Docker + docker-compose support
- Endpoints: create/delete sandboxes (containers), file upload/download/list, code execution (Python/JS/TS), Linux commands
- Java side: `WebClient` (reactive) with connection pooling (`ReactorClientHttpConnector`, max 100 connections)

## API Endpoints

| Path Prefix | Controller | Purpose |
|---|---|---|
| `POST /chat/stream` | `ChatController` | SSE streaming chat (SseEmitter, 120s timeout) |
| `GET /chat/model` | `ChatController` | List models from user's API provider |
| `/user` | `UserController` | Login, register, password, API config |
| `/history` | `ChatHistoryController` | Chat history by session |
| `/knowledge` | `KnowledgeController` | Insert files into knowledge base |
| `/file` | `FileController` | Image/file upload to OSS |
| `/common` | `CommonController` | Email verification code |
| `/mcp` | `McpController` | MCP server CRUD, sync from service |
| `/ws/{userId}` | `WebSocketService` | WebSocket connection per user |

## Domain Entities

| Entity | Table | Notes |
|---|---|---|
| `User` | `users` | Auth, quota, avatar |
| `UserConfig` | `user_config` | JSONB LLM tokens (encrypted), MCP tokens |
| `ChatHistory` | `chat_memory` | LangChain4j message JSON per session |
| `ChatHistoryList` | `chat_history_list` | Session list with title |
| `UserMemory` | `user_memory` | Long-term memory with pgvector embedding |
| `Memories` | — | Transient: add/update/delete lists for memory consolidation |
| `SysFile` | `sys_file` | Uploaded file metadata, OSS URL |
| `McpInformation` | `mcp_information` | MCP server URL, headers (JSONB), availability |
| `KnowledgeBase` | `knowledge_base` | Knowledge base definitions |
| `KnowledgeBaseFile` | `knowledge_base_file` | Knowledge base ↔ file link |
| `SystemLog` | `system_log` | AI/system log entries |
| `Model` | — | Transient: model config within API config |

**Note:** No Flyway/Liquibase — tables managed manually. `knowledge_embedding` table created automatically by LangChain4j's `PgVectorEmbeddingStore`.

## Key Patterns

- **API Response:** `Result` envelope — `code: 0`=success, `msg`, `data`, `total`
- **Constructor injection** (Lombok `@RequiredArgsConstructor`) — no field injection except `PgChatMemoryStore` uses `@Resource`
- **ThreadLocal contexts:** `UserContextHolder` (user ID from JWT interceptor), `MessageMetadataContext` (file attachments metadata)
- **Custom exceptions:** `NotSupportException`, `ValidationException`, `UnauthorizedException`, `NotFoundException`, `ParserFileException`, `PermissionDeniedException` — handled by `GlobalExceptionHandler`
- **Lombok:** `@Data`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor`
- **Java records** used for DTOs (`ChatDTO`, `ModelDTO`, `SearchMemoryRequest`, etc.)
- **All code comments are in Chinese**

## Existing Rules & Conventions

- Inherits user's global rules from `~/.claude/rules/` (common + java + web) including: google-java-format after edits, `mvn compile` verification, code review after modifications, security checks before commits
- `application-dev.yml` is gitignored — never commit secrets
- Tests: JUnit 5, no mocking framework dependency visible (direct integration-style tests in `nexus-agent-web/src/test/`)
