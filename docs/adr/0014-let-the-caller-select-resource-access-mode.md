# Let the caller select resource access mode

Each preparation or render operation may select a non-content `ResourceAccessMode`: `NORMAL` uses fresh raw entries and revalidates stale entries, `CACHE_ONLY` uses any integrity-valid cached bytes without transport access, and `RELOAD` fetches unconditionally while replacing an existing entry only after successful validation. The mode never enters output identity; the frozen resource digests do, allowing callers to implement offline use and explicit refetch without Rentile owning recovery policy.
