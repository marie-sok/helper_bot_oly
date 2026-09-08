# Oly — AI Telegram Agent

**A production-oriented Telegram AI agent built with Spring Boot, PostgreSQL and LLM tool orchestration.**

Oly is not a command-only bot. It keeps conversational context, interprets natural-language requests and routes them to server-side tools when an action is required.

## What it demonstrates

- multi-turn AI conversations in Telegram;
- persistent conversation state across application restarts;
- natural-language reminders and task management;
- current-information lookup through web-search tooling;
- media-processing flows for Telegram content;
- persistent knowledge and chat data in PostgreSQL;
- webhook-based Telegram delivery for deployment environments;
- runtime health checks, timeouts and fallback paths;
- environment-driven model/provider configuration.

## Architecture

```text
Telegram
   |
   v
Telegram webhook / updates listener
   |
   v
Agent orchestration layer
   |---- LLM response generation
   |---- web search
   |---- reminder / task tools
   |---- knowledge persistence
   |---- media processing
   |
   v
PostgreSQL + Liquibase migrations
```

The project separates Telegram transport, agent orchestration, persistence and infrastructure concerns instead of putting the whole bot into one handler.

## Engineering highlights

### Stateful agent
Conversation state is stored per Telegram chat, so a dialogue can continue after the Spring Boot process restarts.

### Tool execution
Actions such as reminders are executed on the server side and confirmed only after the underlying operation succeeds.

### Media pipeline
Media handling is isolated from the main update path and processed concurrently so expensive work does not block unrelated Telegram updates.

### Deployment resilience
The codebase contains webhook support, startup probes, runtime watchdogs, HTTP timeout configuration and fallback paths for degraded AI-provider behavior.

### Database evolution
Liquibase changelogs are used for schema evolution instead of relying on ad-hoc database state.

## Stack

`Java` · `Spring Boot` · `Telegram Bot API` · `PostgreSQL` · `Liquibase` · `LLM APIs` · `Docker` · `Maven`

## Local run

Requirements:

- JDK 17+
- PostgreSQL
- Telegram bot token
- AI-provider API key

```bash
git clone https://github.com/marie-sok/helper_bot_oly.git
cd helper_bot_oly
cp .env.example .env
# fill in local credentials
./mvnw spring-boot:run
```

Real credentials belong in environment variables and are not committed to the repository.

## Why this project matters

Oly is a compact example of the kind of systems I like building: a visible user-facing product backed by real persistence, integrations, failure handling and infrastructure rather than a demo prompt wrapped in a chat interface.

---

**Built by Marie Sok.**