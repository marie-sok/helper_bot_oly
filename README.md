# helper_bot_oly 🐱

`helper_bot_oly` is a Telegram AI agent with a small deterministic reminder engine.

The project started as a reminder bot and is being revived as **Oly**: a conversational assistant that can answer normal Telegram messages while keeping the existing reminder flow and a few original easter eggs.

## What Oly can do

- talk in a normal Telegram chat without a special `/ai` command;
- keep short conversational context per Telegram chat;
- reset AI context with `/new` or `/reset`;
- create reminders using `dd.MM.yyyy HH:mm Text`;
- deliver scheduled reminders every minute;
- keep `/joke` and the original playful commands.

## Stack

- Java 17
- Spring Boot 3.5
- PostgreSQL + Spring Data JPA
- Liquibase
- Pengrad Telegram Bot API
- OpenAI Java SDK + Responses API

## Required environment variables

Copy `.env.example` as a reference. Do **not** commit real values.

```text
TELEGRAM_BOT_TOKEN=...
OPENAI_API_KEY=...
DATABASE_URL=jdbc:postgresql://localhost:5432/telegram_bot
DATABASE_USERNAME=helper_oly
DATABASE_PASSWORD=...
```

Optional:

```text
OPENAI_MODEL=gpt-5.6-luna
OLY_AI_ENABLED=true
SERVER_PORT=8082
LOG_LEVEL=INFO
```

## Run locally

Make sure PostgreSQL is available and the configured database/user exist, then export the environment variables and run:

```bash
./mvnw spring-boot:run
```

The application uses Liquibase migrations from `src/main/resources/db/changelog`.

## Telegram commands

```text
/start  welcome
/help   short help
/new    start a fresh AI conversation
/reset  same as /new
/joke   original joke command
```

Reminder example:

```text
05.09.2026 19:30 Check the backend
```

## Security

Secrets belong in environment variables only. If a token or password has ever been committed to Git history, removing it from the current file is not enough: rotate that credential at the provider and treat the old value as compromised.
