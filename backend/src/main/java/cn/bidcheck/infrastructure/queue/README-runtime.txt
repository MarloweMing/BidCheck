Task persistence and RabbitMQ contract

Default profile: H2 file database, no broker connection needed, two local task executions.
vm profile: MySQL 8 + RabbitMQ notifications. This is a single-VM deployment, not high availability.
The API's transactionally committed qc_task row is the only task-state truth source.

The DB scanner publishes only a task UUID (UTF-8 text), with persistent delivery, a durable queue,
mandatory routing, correlated publisher confirms and returned-message checks. Notification overflow,
broker failure or missing consumption cannot discard business state. Notifications carry no documents,
credentials, extracted text, tenant payloads or unique business results.

The consumer first obtains a DB CAS claim with a new per-claim UUID fencing token and expiry, then submits
to a two-thread executor with SynchronousQueue (zero waiting backlog), and immediately acknowledges.
It never holds the Rabbit acknowledgement for OCR, parsing or model completion. Busy/duplicate signals
are acknowledged; their durable DB state still drives future work. Database errors discard only the
notification to avoid hot redelivery; the pending/expired DB task remains recoverable.

Each task lease is renewed by an independent timer. A claim is fenced by owner token + RUNNING state +
unexpired lease on task-state writes. Expired leases alone return to PENDING; startup does not reset live
RUNNING tasks. Result/task/run transactions in BidService must lock/fence the task before result writes.
Shutdown interrupts unfinished local work; persisted leases/rows recover after restart. Model/parse
operations should retain bounded timeouts and interruption handling.

Claims have a lifetime limit of three attempts, including crash/lease-expiry recovery. Exhausted pending
tasks become FAILED through an atomic DB transition; the associated check run is failed in the same
transaction. A rejected executor submission returns its claim and refunds the unused attempt. Manual
retry is an atomic FAILED-to-PENDING transition below the limit, so duplicate retry requests cannot
reset a RUNNING task. A replacement claim in the same JVM interrupts the old execution immediately.

Publisher success gives the Rabbit listener a short delivery window. If the DB task is still pending
after that window, the DB scanner directly executes it. Broker failure uses immediate DB fallback.
All duplicate deliveries are safe at task claim. This is at-least-once scheduling with idempotent stable
result IDs, not an exactly-once external model/API side-effect guarantee.

Environment configuration (never commit values):
SPRING_PROFILES_ACTIVE=vm
BIDCHECK_MYSQL_URL / BIDCHECK_MYSQL_USER / BIDCHECK_MYSQL_PASSWORD
BIDCHECK_RABBIT_HOST / BIDCHECK_RABBIT_PORT / BIDCHECK_RABBIT_USER / BIDCHECK_RABBIT_PASSWORD
BIDCHECK_RABBIT_VHOST (default /bidcheck)
BIDCHECK_RABBIT_ENABLED (default true in vm; false permits a deliberate DB-only fallback deployment)

The MySQL schema creates only missing tables, with no foreign keys and no DROP/TRUNCATE operations.
H2 upgrades add only lease_owner/lease_until columns using ADD COLUMN IF NOT EXISTS.
Existing installations with qc_task created by an older MySQL schema require these same two lease
columns before using this worker version; do not replace or delete existing task/file records.
