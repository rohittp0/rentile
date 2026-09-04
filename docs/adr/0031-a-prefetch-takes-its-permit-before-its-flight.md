# A prefetch takes its permit before its flight, and never joins one it did not create

Warming and on-demand acquisition of one raw resource share a single flight, so the two never issue
two requests for one URL. A cursor-ordered prefetch is designed to reach the tiles the renderer is
about to ask for, so the collision is the normal case rather than a race, and the duplicate was
charged to the connection budget the prefetch exists to spend well.

Sharing a flight makes the order in which a prefetch takes its exchange permit part of the priority
contract. **A prefetch acquires its `ResourcePriority.WARM` permit before creating the flight, not
inside it.** A flight another coroutine can discover is therefore always one whose exchange is
already on the wire, so an acquisition that joins a prefetch waits for one in-flight request — the
bound ADR 0005's gate already promises — instead of inheriting a WARM queue position behind every
queued acquisition, which is unbounded while acquisition load continues.

The same ordering forbids the mirror move: **a prefetch does not join a flight it did not create.**
It holds a permit while it runs, so waiting there for an acquisition's fetch — which needs a permit
of its own — could park every permit in the gate on work that cannot start. It has nothing to wait
for anyway; the bytes it wanted cached are already being fetched. It returns, and
`RawWarmSummary.alreadyCached` counts the resource, with the `WARM_ALREADY_IN_FLIGHT` metric
separating "someone else is fetching it" from "it was on disk".

**One attempt is one permit is one exchange.** A prefetch's retry lives outside the permit: a
retryable failure leaves the gate, waits out `Retry-After`, and comes back for a fresh permit and a
fresh flight. Holding the permit across that wait would let a warm burst park every permit asleep
for up to `MAX_TILE_RETRY_DELAY_MILLIS` while acquisitions queued behind them, which is precisely
the starvation the gate's two queues exist to prevent. The consequence is accepted deliberately: an
acquisition that joined the first attempt receives its failure rather than waiting for the
prefetch's second try. For the vector, raster and DEM classes prefetching covers, those statuses are
substitution-eligible, and recovery remains the caller's under ADR 0011.

Cancelling a prefetch while a joiner is still attached returns the permit and leaves the flight
running. This is accepted slack rather than a leak: the exchange is already on the wire, so nothing
new starts, and the gate is momentarily one request over its count rather than one request short of
its work.
