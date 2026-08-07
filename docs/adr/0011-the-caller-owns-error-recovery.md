# The caller owns error recovery

Rentile will detect failures and throw typed custom exceptions with structured, redacted diagnostics, but it will not automatically retry, refetch, substitute missing content, fall back to another renderer, or decide whether related work should continue. The caller catches the failure and may repeat the operation, change its transport or cache policy, split the batch, or cancel other jobs; coroutine cancellation propagates as `CancellationException` and is never wrapped as a Rentile failure.

A failed operation never rolls back unrelated raw-resource cache entries that were already validated and committed atomically. The failing resource produces no partial or negative cache entry, while successful entries acquired for other tiles remain reusable by later calls.

The `render()` return boundary is all-or-error for the caller-defined batch. Any tile failure throws a typed batch exception and returns no partial PNG collection; callers that need isolated outcomes choose smaller batches, while previously committed raw resources remain available to those later calls.

Rentile fails fast on the first terminal failure and cancels work owned only by that call without continuing solely to discover more errors. The thrown batch exception contains the primary failure and any concurrent failures already observed, while single-flight work with other waiters continues under last-waiter cancellation.
