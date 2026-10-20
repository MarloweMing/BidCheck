# Document-scoped hybrid retrieval

`HybridRetriever` implements Chinese character/bigram and identifier BM25 plus
`text-embedding-v4` dense vectors, exact cosine search, reciprocal-rank fusion
(each route top 30, RRF constant 60), then the real `qwen3-rerank` API. Search
returns at most 16 original `Evidence` objects; ranking scores are diagnostics,
not evidence confidence. The index is local, not Milvus.

Public entry points are `retrieve(Checkpoint, Document)`,
`search(Document, String query, int limit)` (1–16), and `warmup(Document)`.
`warmup(Document, Consumer<Map<String,Object>>)` adds caller-thread progress.
Warm-up embeds the entire document and returns `INDEX_READY` only when complete.
Each successful batch of at most 10 inputs is persisted immediately. Repeat a
warm-up after a timeout/failure to continue from its validated cache, without
re-embedding completed batches. No rerank or query embedding occurs in warm-up.

Production must explicitly set `bidcheck.retrieval.enabled=true`, `key`, and a
writable `cache-dir`. Defaults use the DashScope native embedding endpoint and
qwen3-rerank's flat compatible API, models `text-embedding-v4` / `qwen3-rerank`,
1024 dimensions. `enabled()` reports the configuration switch, **not** successful
retrieval. Only `HYBRID_RERANKED` means both routes and remote reranking completed.
`HYBRID_RRF_DEGRADED`, `BM25_DEGRADED` and `BM25_DISABLED` explicitly describe
reduced capability. Diagnostics contain bounded failure codes and per-route
ranks; they never include API keys, remote error bodies or source text.

The cache namespace binds project ID, document ID/version/source hash, actual
page text/location/parser, chunk input hash, endpoint, embedding model and policy
version/dimensions. Binary entries contain only hashes/vectors/checksums. Cache
reads validate metadata, dimensions, finite/nonzero vectors and checksum; corrupt
entries are re-embedded. At most two complete document indexes are held in memory.
Documents are limited to 2 million characters / 4,000 chunks. Each request is
bounded by 30 seconds; search remains bounded by 90 seconds. Preparation has a
separate 600-second ceiling, including cache reads and lock/network queues.
`text-embedding-v4` permits at most 10 inputs per request, so batches stay at 10.
The bounded scheduler runs four batches concurrently by default, and a shared
semaphore applies the same cap across requests/documents. No unbounded batch
queue is submitted. A failed batch stops new dispatch; other in-flight valid
batches can finish and persist, so a subsequent preparation only embeds missing
inputs. Partial vectors are never published as a complete dense index. Existing
cache identities are preserved because chunking/model/vector contents did not
change. There are no hidden automatic model retries.

Optional settings are `bidcheck.retrieval.embedding-concurrency` (default 4,
1–8), `preparation-timeout-seconds` (600), and `request-timeout-seconds` (30).
Duration settings must be positive and at most one hour. Raising a ceiling does
not prove performance; elapsed time, batch/chunk counts and failure codes show
actual progress. `RETRIEVAL_TIMEOUT` specifically means the operation deadline;
`EMBEDDING_REQUEST_TIMEOUT` / `RERANK_REQUEST_TIMEOUT` mean the individual
request expired. Queue, HTTP, invalid-response and cache failures stay separate.
`retryableFailure` identifies timeout, queue, communication, 408/425/429 and 5xx failures;
the caller controls any retry policy.

Progress reports include total/completed/remaining chunks, validated cache hits,
planned/submitted/completed/failed batches, active requests and elapsed/budget
milliseconds. Reports run on the caller thread after cache reads and completed
batches, and at least once per second while waiting for network/lock work. The
caller should persist task progress/renew its lease here. Callbacks must return
promptly; their runtime failures (including cancellation or lease fencing) are
propagated without being converted into document/model errors. Interruption
cancels all queued/in-flight work and propagates without clearing the interrupt
flag. Metrics never contain source text, authorization headers or response bodies.

Native source quotes remain exact substrings at the original location. Same-page
openings and adjacent Word headers are labelled retrieval context. Current-role
or historical cues are unverified hints, never entity or project assertions.

Protocol references:
- https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api/
- https://help.aliyun.com/zh/model-studio/text-rerank-api
