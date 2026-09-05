# Oly — AI Telegram Agent

`helper_bot_oly` is a Spring Boot Telegram agent with OpenAI Responses API integration, persistent conversation context, web search and reminder tools.

## What Oly can do

- chat naturally in Telegram and keep conversation context;
- answer current-information questions using OpenAI web search;
- create reminders from natural language, e.g. `Напомни завтра в 18:00 позвонить маме`;
- list future reminders;
- delete reminders;
- keep the legacy reminder format `dd.MM.yyyy HH:mm text`;
- reset AI context with `/reset`;
- report AI status with `/ai`.

The default model is `gpt-5.6-terra`. Override it with `OPENAI_MODEL` if needed.

## Requirements

- JDK 17+
- PostgreSQL
- Telegram bot token from BotFather
- OpenAI API key

## 1. Clone

```bash
git clone https://github.com/marie-sok/helper_bot_oly.git
cd helper_bot_oly
```

If you already have the repository locally:

```bash
git switch main
git pull origin main
```

## 2. Create the PostgreSQL database

Run once as a PostgreSQL admin user:

```bash
psql postgres \
  -v helper_oly_password='CHANGE_ME_DB_PASSWORD' \
  -f src/main/resources/setup_database.sql
```

Use the same password later in `DATABASE_PASSWORD`.

## 3. Configure secrets

```bash
cp .env.example .env
nano .env
```

Set at minimum:

```dotenv
TELEGRAM_BOT_TOKEN=your_telegram_bot_token
OPENAI_API_KEY=your_openai_api_key
DATABASE_PASSWORD=your_database_password
```

`.env` is ignored by Git. Never commit real Telegram, OpenAI or database credentials.

Load the environment into the current shell:

```bash
set -a
source .env
set +a
```

## 4. Run Oly

```bash
./mvnw spring-boot:run
```

A healthy startup should include a log similar to:

```text
Oly Telegram listener started. AI enabled=true, model=gpt-5.6-terra
```

Keep that process running while you use the bot in Telegram.

## Telegram commands

```text
/start  — intro and capabilities
/help   — help
/ai     — AI status and model
/reset  — reset the current AI conversation context
/joke   — AI-generated joke
```

You do not need a command for normal AI chat. Just send Oly a message.

Examples:

```text
Объясни мне dependency injection простыми словами.
Напомни завтра в 18:00 позвонить маме.
Какие у меня напоминания?
Удали напоминание про звонок.
Что сегодня нового в OpenAI?
```

## Agent architecture

```text
Telegram
   ↓
TelegramBotUpdatesListener
   ↓
OlyAiService
   ├── OpenAI Responses API
   ├── web_search
   ├── create_reminder
   ├── list_reminders
   └── delete_reminder
           ↓
       PostgreSQL
```

`oly_conversation` stores the latest OpenAI response id per Telegram chat so the conversation can continue after an application restart. `/reset` deletes that local conversation pointer and starts a fresh context.

## Main environment variables

| Variable | Default | Purpose |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | none | Telegram Bot API token |
| `OPENAI_API_KEY` | none | OpenAI API key |
| `OPENAI_MODEL` | `gpt-5.6-terra` | model used by Oly |
| `OLY_AI_ENABLED` | `true` | enable/disable AI |
| `OLY_WEB_SEARCH_ENABLED` | `true` | allow current web search |
| `OLY_TIMEZONE` | `Europe/Amsterdam` | reminder interpretation and scheduler timezone |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/telegram_bot` | PostgreSQL JDBC URL |
| `DATABASE_USERNAME` | `helper_oly` | PostgreSQL username |
| `DATABASE_PASSWORD` | none | PostgreSQL password |
| `SERVER_PORT` | `8082` | Spring Boot HTTP port |

## Notes

- Oly never receives real secrets in its prompt; secrets are used only by the application for API authentication.
- Reminder actions are executed by server-side tools and only confirmed after the database operation succeeds.
- The OpenAI Responses API is used with stored response context so multi-turn memory works. OpenAI platform data-retention settings apply to those responses.
