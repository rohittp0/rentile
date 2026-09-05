# Rendering has a priority, and it is the caller's

ADR 0005 said Rentile "does not define work classes or prioritize callers", and that a caller
controls priority by retaining or cancelling its own work. That held for as long as everything a
caller submitted was work it was waiting for. It stopped holding when consumers began reading ahead.

A consumer rendering an animated session submits two kinds of render for the same style: the handful
of tiles it is about to put on screen, and a much larger read-ahead covering where the session is
going. `maxConcurrentMetatileWorkers` is one or two on a phone, and the render gate was a plain FIFO
`Semaphore`, so the two kinds queued together and the larger one won by arriving first. Measured on
the consumer's final wave-2 build against production: a OnePlus MT2111 held playback once per cold
run for 6.8-7.8 s waiting on two to four coarse ancestor tiles, while the two raster workers drew 35
read-ahead tiles — about 10 s of draw — that nothing was waiting for. A Motorola Edge 60 Fusion held
9-10.5 s with 65 read-ahead renders under it.

Cancellation cannot fix this, which is what makes it an API problem rather than a caller's. The
read-ahead is not wrong work: those tiles are wanted, a second or two later. Cancelling them throws
away exactly the bytes and pixels the read-ahead exists to have ready, and pausing the read-ahead
between windows — which the consumer tried — cannot pre-empt what is already queued.

## Decision

**`render`, `renderRaw` and the style-based `render` overload take a `RenderPriority`, defaulting to
`NORMAL`.** `URGENT` means a tile the caller is about to present, or one whose absence pauses
presentation. `NORMAL` means read-ahead. A freed metatile worker goes to an `URGENT` request whenever
one is waiting, and to a `NORMAL` request only when none is.

Two levels, not a number. The distinction a caller can actually make reliably is "someone is waiting
for this" versus "nobody is yet"; anything finer is a weight to tune, and a weight that is wrong is
worse than no weight at all because it looks deliberate.

Ordering *within* a level is unchanged: both queues are FIFO, and a tile already drawing is never
pre-empted. An `URGENT` request therefore waits at most for the tiles in the workers — one unit of
work per worker — rather than for the backlog. Cancelling a half-drawn tile to make room would throw
away pixels already paid for, which is the same trade ADR 0031 makes for an exchange already on the
wire.

The render gate is the existing `PriorityGate`, generalised rather than duplicated. It was already
exactly this shape for the network gate — two FIFO queues, a freed permit offered to the deferred
queue only when the first queue is empty, cancellation-safe release — because warming and acquisition
had the same problem one layer down. It now names its queues `GateLane.FIRST` and
`GateLane.DEFERRED`, and each caller maps its own vocabulary onto them:
`ResourcePriority.ACQUISITION`/`WARM` for exchanges, `RenderPriority.URGENT`/`NORMAL` for draws. One
gate means one set of cancellation and permit-leak proofs, which is the part of it that was hard.

Priority is a scheduling decision and reaches nothing else. It does not enter an output tile's
request key or content key, does not reach the draw path, and two tiles rendered at different
priorities are byte-identical — so a consumer's output cache sees one entry per tile however it was
scheduled.

## Why not bound the read-ahead instead

The obvious alternative is to cap how far ahead a read-ahead may run and leave the queue FIFO. It is
a guess that is wrong at both ends: too small and the workers idle between windows, which is the
whole reason to read ahead; too large and the read-ahead takes every freed worker from the tile the
user is waiting on, which is the failure being fixed. The consumer's own ADR 0017 recorded this from
the other side, where a whole-session read-ahead in plan order spent the contended connection budget
on tiles the run would not reach for minutes.

Strict priority deletes the parameter rather than tuning it. There is no number to get right, and a
read-ahead can safely cover a whole session because it can only ever use what nothing else wants.

## Consequences

- **ADR 0005 is amended, not overturned.** Rentile still defines no work classes of its own and
  still does not rank one caller against another; it accepts one bit from the caller about the work
  it submits. Everything else in ADR 0005 stands: the caller owns batch sizing, chunking,
  cancellation and which tiles are submitted.
- **A caller that marks everything `URGENT` has one FIFO queue again**, which is the previous
  behaviour and not an error. The mechanism is relative, so it is only worth what the caller's
  honesty about its own read-ahead is worth.
- **The default is `NORMAL`, so an existing call site keeps its exact behaviour** and one that
  renders only what it is waiting for loses nothing by never passing a priority.
- **A wrong priority is invisible in the output.** Nothing a consumer can render, hash or diff
  differs, so a call site that dropped or inverted its priority would silently return to FIFO. That
  is why the gate carries a test-only lane recorder and every render entry point is covered by a
  test that asserts the lane it asks for.
- **Adding the parameter is a binary-incompatible signature change** on a prerelease API. Consumers
  recompile; no cached tile, content key or renderer semantic changes.
