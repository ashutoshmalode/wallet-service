# DECISIONS.md — Architecture & Design Decisions

## 1. How did you handle the concurrency race condition?

### The Problem
Ten threads simultaneously read a wallet balance of ₹500 and each tries to debit ₹100.
Without locking, all 10 threads see "balance OK" at the same moment and all 10 updates
go through — resulting in a balance of **–₹500** (a catastrophic negative balance).

### Solution: Two-Layer Locking

#### Layer 1 — `SELECT FOR UPDATE` via PESSIMISTIC_WRITE
In `WalletRepository`, the `findByUserIdWithLock()` method is annotated with:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```
This translates to a **database-level `SELECT ... FOR UPDATE`** statement.

When Thread-1 acquires the lock on a wallet row, Threads 2–10 block at the database level
and wait. Once Thread-1 commits (deducting ₹100 and flushing the new balance of ₹400),
Thread-2 acquires the lock and reads **₹400** — not the stale ₹500. After 5 successful
debits, the balance is ₹0. Threads 6–10 read ₹0, fail the balance check, and throw
`InsufficientFundsException`.

This completely eliminates the TOCTOU (Time-Of-Check-Time-Of-Use) race condition.

#### Layer 2 — `SERIALIZABLE` isolation
The `@Transactional(isolation = Isolation.SERIALIZABLE)` annotation on the service method
ensures no phantom reads can occur — even if two transactions read the same balance
concurrently, the database guarantees serial correctness.

#### Why not Optimistic Locking (`@Version`)?
The `Wallet` entity does have a `@Version` field (optimistic locking) as an extra safety net.
However, in a high-contention scenario with 10 concurrent debits, optimistic locking would
cause **9 `OptimisticLockException`s** and require retry logic. For a payment ledger where
"insufficient funds" is a meaningful business failure (not a retry candidate), pessimistic
locking with a clear reject-on-fail semantic is more appropriate.

---

## 2. How did you handle idempotency?

### Two-Level Guard

**Level 1 — Database unique constraint** (the authoritative source):
- The `transactions` table has a `UNIQUE` constraint on `transaction_id`.
- Before processing, we query `findByTransactionId(txId)`. If a record exists,
  we immediately return the **original response** with HTTP 409 Conflict.
- Even on retries after a commit, the answer is always consistent.

**Level 2 — `IdempotencyCache` (in-process ConcurrentHashMap)**:
- A `ConcurrentHashMap.putIfAbsent()` call atomically "claims" a transactionId before
  the DB transaction starts.
- If a second thread arrives with the same transactionId **before the first thread has
  committed** (i.e., the DB record doesn't exist yet), the second thread is rejected
  immediately without waiting for a DB constraint violation.
- After the DB transaction completes (commit or rollback), the in-flight entry is removed.

This two-level approach handles both sequential duplicates (Level 1) and concurrent
near-simultaneous duplicates within the 50ms window specified in the requirements (Level 2).

---

## 3. Where did the AI assistant give an incorrect or sub-optimal suggestion?

### Sub-optimal suggestion 1: In-memory Map as the sole idempotency mechanism
An AI-generated first draft used only an `ConcurrentHashMap` for idempotency and omitted
the database `UNIQUE` constraint. This would fail in:
- A multi-instance (clustered) deployment where each instance has its own JVM map.
- A JVM restart scenario where the map is cleared.

**Fix**: Added a `UNIQUE` index on `transactions.transaction_id` as the authoritative
guard, with the in-memory map as a performance optimisation for intra-instance concurrency only.

### Sub-optional suggestion 2: Using `Optional.orElse()` inside a locked transaction
The AI initially suggested:
```java
Wallet wallet = walletRepository.findByUserIdWithLock(userId).orElse(new Wallet());
```
This silently creates a zero-balance wallet on miss instead of failing clearly.

**Fix**: Changed to `orElseThrow(() -> new WalletNotFoundException(userId))` to make the
failure explicit and surfaced as a proper HTTP 404 response.

### Sub-optimal suggestion 3: Optimistic locking only
An early AI draft used only `@Version` (optimistic locking) for concurrency control.
Under high contention (10 concurrent requests), this causes 9 `OptimisticLockException`s
and requires a retry loop — inappropriate for a financial ledger where "you don't have
enough funds" is a terminal business decision, not a transient infrastructure error.

**Fix**: Replaced with pessimistic `SELECT FOR UPDATE` locking, which serialises access
at the database row level and turns contention into a clean ordered queue.
