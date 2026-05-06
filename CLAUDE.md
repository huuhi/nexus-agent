# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**nexus-agent** is an AI Agent platform built on Spring Boot 3.5 and LangChain4j. It provides:
- Multi-model LLM chat with SSE streaming (OpenAI-compatible APIs)
- RAG knowledge base with pgvector embeddings
- MCP (Model Context Protocol) tool integration
- Sandboxed code execution (Python/FastAPI service)
- Multimodal file uploads (images, documents) via Aliyun OSS
- User authentication with JWT and per-user API key encryption

## Tech Stack

- **Java 21**, Spring Boot 3.5.13
- **LangChain4j** 1.12.1-beta21 (OpenAI, pgvector, MCP, Skills)
- **MyBatis-Plus** 3.5.6 (ORM)
- **PostgreSQL** (relational + pgvector for vector embeddings)
- **Redis** (caching/sessions)
- **Aliyun OSS** (file storage)
- **JWT** (jjwt 0.12.5)
- **Hutool** 5.8.27, **Lombok**
- **Python/FastAPI** sandbox service (`nexus_agent_box/`)

## Maven Multi-Module Structure

```
nexus-agent (parent pom)
├── nexus-agent-common    -- Utilities, enums, exceptions, JWT, ThreadLocal contexts
├── nexus-agent-domain    -- Entities, DTOs, VOs, validation
├── nexus-agent-mapper   -- MyBatis-Plus mapper interfaces + XML
├── nexus-agent-service   -- Business logic, LangChain4j integration, tools, configs
└── nexus-agent-web       -- REST controllers, main application entry point
```

**Dependency chain:** `web` → `service` → `mapper` → `domain` → `common`

## Build / Test / Run

```bash
# Build all modules
mvn clean package

# Build skipping tests
mvn clean package -DskipTests

# Run tests
mvn test

# Run application
mvn spring-boot:run -pl nexus-agent-web
# or
java -jar nexus-agent-web/target/nexus-agent-web-0.0.1-SNAPSHOT.jar
```

**Active profile:** `dev` (default). `application-dev.yml` is gitignored — contains DB credentials, OSS keys, JWT secret, etc.

**Required environment variables** (from `application-prod.yml`):
- `JWT_SECRET` — JWT signing key
- `OSS_ENDPOINT`, `OSS_ACCESS_KEY_ID`, `OSS_ACCESS_KEY_SECRET`, `OSS_BUCKET_NAME` — Aliyun OSS
- `POSTGRESQL_HOST`, `POSTGRESQL_PORT`, `POSTGRESQL_DATABASE`, `POSTGRESQL_USERNAME`, `POSTGRESQL_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`
- `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`
- `ENCRYPTOR_SALT` — Spring Security Crypto salt for user API key encryption

## Key Architecture

### Chat Flow
1. `ChatController` receives `POST /chat/stream` with `ChatDTO`
2. `ChatContextFactory` builds a `ChatContext` with model, memory (32K TokenWindowChatMemory), tools, and MCP providers
3. `ChatAssistant` (LangChain4j AiService interface) streams responses via `TokenStream`
4. `SseResponseConverter` writes SSE events: content, thinking, tool calls, tool results
5. `PgChatMemoryStore` persists chat memory to PostgreSQL (delta-only writes)

### Dynamic Model Selection
- Users configure LLM API keys via `UserConfig` (encrypted with Spring Security Crypto)
- `ChatContextFactory` selects user-configured model or falls back to system default
- Model metadata: `ModelDTO` + `ModelSystemContent` for system prompts per model

### Tools
- `RagTool` — `@Tool("rag_search")` for vector similarity search against knowledge base
- `BoxTool` — `@Tool` collection for sandbox operations (create/delete sandbox, upload/download files, execute code/commands)
- `LogTool` — `@Tool` for system logging
- MCP tool providers from `McpInformation` table

### Sandbox Service
- Separate Python/FastAPI app in `nexus_agent_box/`, runs on `localhost:8000`
- Endpoints: create/delete sandboxes, file upload/download, code execution (Python/JS/TS), Linux commands
- Java side calls via `WebClient` configured in `WebClientConfig`

### Key Patterns
- **API Response:** `Result` envelope with `code` (0=success), `msg`, `data`, `total`
- **Constructor injection** throughout (no field injection)
- **ThreadLocal contexts:** `UserContextHolder` (user ID), `MessageMetadataContext` (file metadata)
- **Custom exceptions:** `NotSupportException`, `ValidationException`, `UnauthorizedException`, `NotFoundException`, `ParserException` — handled by `GlobalExceptionHandler`
- **Code comments are in Chinese**

## Database Tables (PostgreSQL)

| Table | Purpose |
|---|---|
| `users` | User accounts (email, username, bcrypt password, avatar, API quota) |
| `user_config` | Per-user LLM API tokens (JSONB, encrypted), MCP tokens, defaults |
| `chat_memory` | LangChain4j chat memory persistence |
| `chat_history_list` | Chat session list/metadata |
| `knowledge_base` | Knowledge base definitions |
| `knowledge_base_file` | Files linked to knowledge bases |
| `sys_file` | Uploaded files (OSS URLs, status, biz type) |
| `mcp_information` | MCP server configs (name, URL, headers as JSONB) |
| `skill_mcp_information` | Skill-MCP association |
| `system_log` | AI/system log entries |
| `knowledge_embedding` | pgvector embedding table (auto-created by PgVectorEmbeddingStore) |

**Note:** No Flyway/Liquibase migrations — tables managed manually.

## API Endpoints

| Path Prefix | Controller | Purpose |
|---|---|---|
| `/chat` | `ChatController` | SSE streaming chat, model list |
| `/user` | `UserController` | Login, register, password, API config |
| `/history` | `ChatHistoryController` | Chat history by session |
| `/knowledge` | `KnowledgeController` | Insert files into knowledge base |
| `/file` | `FileController` | Image/file upload to OSS |
| `/common` | `CommonController` | Email verification code |

## Existing Rules & Conventions

- No existing `.cursorrules`, `.cursor/`, `.github/`, or prior `CLAUDE.md`
- Inherits user's global rules from `~/.claude/rules/` (common + java + web)
- Java hooks: auto-format with google-java-format, run `mvn compile` after edits
- Code review standards apply: review after modifications, security checks before commits
- All code comments are in Chinese