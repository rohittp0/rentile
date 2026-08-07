# Freeze the resource closure before drawing

Every render batch will deterministically plan, acquire, validate, and freeze its complete resource closure before drawing begins. Acquisition may run concurrently under `ExecutionPolicy`, but rasterization performs no network access and all outputs in the batch use the frozen resource digests, making request order and provider changes during drawing unable to alter the batch.
