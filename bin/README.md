# Idempotent Payment / Wallet Event Processor

A Spring Boot 3 + Java 21 microservice that safely processes payment webhook events
with full idempotency guarantees and concurrency-safe balance management.

## Tech Stack

| Layer        | Technology                    |
|--------------|-------------------------------|
| Language     | Java 21                       |
| Framework    | Spring Boot 3.2.5             |
| Database     | H2 In-Memory (zero-config)    |
| ORM          | Spring Data JPA / Hibernate   |
| Tests        | JUnit 5, Spring Boot Test     |
| Build        | Maven                         |

## API

### Process a Transaction
```
POST /api/v1/transactions/process
Content-Type: application/json

{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "userId":        "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "amount":        250.00,
  "type":          "DEBIT"
}
```

**Responses:**
- `201 Created` — transaction succeeded
- `409 Conflict` — duplicate transactionId (returns original response)
- `422 Unprocessable Entity` — insufficient funds
- `404 Not Found` — wallet not found
- `400 Bad Request` — validation error

## Running Tests

```bash
# From project root (one command — no Docker, no external DB needed)
mvn test
```

Or run directly from IntelliJ IDEA — see below.

## Architecture Highlights

- **Idempotency**: Two-level guard — DB `UNIQUE` constraint + in-process `ConcurrentHashMap`
- **Concurrency**: `SELECT FOR UPDATE` (pessimistic locking) prevents negative balances
- **Isolation**: `SERIALIZABLE` transaction isolation
- See `DECISIONS.md` for full design rationale
