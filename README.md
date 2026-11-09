# springboot.notarius

Notraius — the property-rental domain of the Primarion estate: listings,
pricing, viewing visits, and the leases that follow them. Built on the shared
`springboot.shared` library and Spring Boot 4.1 (Java 25). Listens on port
**8090** (via `PORT`).

Notraius is a *service* microservice — it has **no** `user`/`role`/`session`
tables of its own. Callers arrive as signed bearer tokens issued by the
primarion microservice and verified by primarion introspection; user ids are
stored as opaque UUID references, never as identity rows.

## Domain at a glance

Five modules, one direction of travel: **configure → list → visit → lease.**

| Module | Packages | What it owns |
|---|---|---|
| **config** | `reference`, `rules`, `policies` | The vocabulary and the standards — types, durations, amenities (with a moderation lifecycle), house rules, and the admin policy standards that price deposits |
| **asset** | `property`, `pricing`, `terms` | The marketable thing — properties, their pricings, and the policy values / rule selections bound to each pricing |
| **visit** | `visits`, `pricings` | The first money movement — visit requests, assignment and acceptance, scheduling, mutual assessment, disputes, and the visit-price catalogue |
| **lease** | `agreement`, `finance`, `state` | Custody in months — lease birth and funding, the deposit matrix, the monthly installment cycle, conditions, notices, repairs and settlement |
| **core** | `access`, `app`, `common` | Host wiring: role lookup, activity/status constants, shared guards |

### The three state machines

```
PROPERTY   DRAFT ──publish──▶ PUBLISHED ──lease──▶ LEASED
                              ▲   │
                    unpause   │   └─unpublish─▶ PAUSED ─┐
                               └────────────────────────┴─▶ SUSPENDED / DELETED / DISABLED

VISIT      UNPAID → REQUESTED → ASSIGNED → PLANNING → CONFIRMED → STARTED → COMPLETED
                                     └──────────▶ CANCELLED / EXPIRED

LEASE      PENDING → CONFIRMED → ACTIVE → COMPLETED
                └──────────────────────▶ CANCELLED / EXPIRED / DISPUTED
```

Vocabulary lives in `NotraiusStatuses` — **93 status constants** — and the
activity/operation pairs the permission layer reconciles in
`NotraiusActivities` — **144 constants**.

## API surface

**206 HTTP endpoints across 11 controllers**: 188 carry an explicit
`@PreAuthorize` guard, and the other 18 are the anonymous read doors described
in *Access model* (a logged-out visitor has no principal for an authority gate
to resolve, so the servlet chain's `PUBLIC_GET_ENDPOINTS` entry is their gate).

| Controller | Base path | Endpoints |
|---|---|---:|
| `ReferenceController` | `/api/v1/notraius/config/reference` | 42 |
| `PropertyController` | `/api/v1/notraius/asset/properties` | 27 |
| `VisitController` | `/api/v1/notraius/visits` | 25 |
| `LeaseController` | `/api/v1/notraius/leases` | 19 |
| `LeaseStateController` | `/api/v1/notraius/leases` | 17 |
| `RuleController` | `/api/v1/notraius/config/rules` | 15 |
| `PricingController` | `/api/v1/notraius/asset/pricings` | 15 |
| `PolicyController` | `/api/v1/notraius/config/policies` | 13 |
| `VisitPricingController` | `/api/v1/notraius/visit-pricings` | 11 |
| `TermsController` | `/api/v1/notraius/asset` | 15 |
| `LeaseFinanceController` | `/api/v1/notraius/leases` | 7 |

Bulk reads go through `BulkRunner` (`/batch` routes), which answers per-id with
the same service gate as the single read — a batch never widens access.

## Access model

Authorization is a **five-layer chain**, evaluated in order by
`SecurityExpressionService` in the shared library:

1. **Role gate** — `@authz.authority('X', 'GTE', 'ACTIVITY', 'OP')` on the
   controller method.
2. **Permission row** — fail-closed: no row means denied. `PermissionSeeder`
   mirrors every annotation into `access.permissions` at boot.
3. **Consent delegation** — a live grant in the actor's own shape.
4. **Plan-limit policy** — dynamic; `GET`/`LIST` are never plan-limited.
5. **Rate-limit guard** — deliberately outside the fail-closed catch, so a
   quota breach is a client-visible 429 and never a silent 403.

Only then does the **service-layer party check** run: each service extends
`OwnershipGuard` and answers `canAccess(...)` for its own entity — **10
subclasses in this module**.

Three doctrines the module holds:

- **One pair, one policy.** Two endpoints declaring the same
  (activity, operation) pair under different policies is a **boot failure**, not
  a reconciliation. The same applies to duplicate rows in the table: an
  ambiguous allowlist is the highest-grade failure this module can suffer.
- **404 over 403 for hidden rows.** A stranger reading someone else's draft is
  answered `404`, never `403` — otherwise the response becomes an existence
  oracle. Implemented at 8 call sites (`catch (ForbiddenException)` →
  `NotFoundException`); notarius is the only module in the estate that does this.
- **Drafts are invisible.** Unpublished properties answer 404 to everyone but
  their owner and staff. `LEASED` is the one public rung — the market must be
  able to see that a home is taken.

The full audit of all 206 endpoints, its four findings, and the decided access
model are recorded in [`UPGRADE.md`](UPGRADE.md).

## Structure

```
src/main/java/com/notraius/
├── NotraiusApplication.java        # bootstrap + shared @ImportAutoConfiguration
├── config/                         # JPA / Flyway / properties / web security
│   ├── NotraiusJpaConfig.java
│   ├── NotraiusFlywayConfig.java
│   ├── properties/                 # NotraiusProperties, InterServiceProperties
│   └── web/                        # SecurityConfig, guards
├── database/                       # host reference entities + repositories
│   ├── entities/reference/         # Entities / Status / Validity / CommunicationRole
│   └── repositories/reference/
└── modules/                        # ── the domain ──
    ├── asset/{property,pricing,terms}/   controller · service · dto
    ├── config/{reference,rules,policies}/
    ├── lease/{agreement,finance,state}/
    ├── visit/{visits,pricings}/
    └── core/{access,app,common}/         # constants, role lookup, guards
```

Each feature is a vertical slice — `controller` / `service` / `dto` (plus
`entity` where a table is owned here) — so a change to one feature never
requires touching another's internals.

**Codebase size:** 208 main source files (26,645 LOC), 30 entities, 27
repositories, 66 DTOs, 14 service classes.

## Data layer

- **41 Flyway migrations** in `db/migration` (`V1`–`V42`, `V13` was never
  issued). They bootstrap the shared estate schemas (access, app,
  communication, event, security, subscription, plus the V8 subfeature split
  into chat / support / webhook / storage / rate), the notraius host schema
  (entities / statuses / validity / attachments), audit-log partitioning, rate
  limits, and the permission sweeps.
- **Lookup vocabulary** in `db/lookups/notraius.yaml`.
- **Ownership rule:** the notraius database holds every row owned by this
  service plus the shared-module rows that ride each consuming host (chat,
  support, storage, webhook). Foreign-domain data (identity, geo, media
  catalog) lives in the shared lookup database and is read through
  `app.shared.datasource` — **never copied**.
- **Service keys are per-service** (`#AUTH-CENTRAL`): `APP_PUBLIC_KEY` /
  `APP_PRIVATE_KEY` (a fresh RSA-2048 pair) and `HMAC_KEY` must never match
  another service's.

### What comes from the shared library

Notraius owns no infrastructure that the estate already owns. It imports the
shared modules (`NotraiusApplication`'s `@ImportAutoConfiguration`) and supplies
only what the library leaves to the host:

| The host must supply | Where |
|---|---|
| `OwnershipGuard` subclasses (`canAccess`) | `modules/*/service/*Service` — the consent/seat fallback in `requireOwnership` comes from shared |
| `ValidityPort` over `notraius.validity` | `NotraiusValidityAdapter` — `findMany` is **one** `IN` query, not the port's default per-row loop the event scan would pay 100×/tick |
| `StatusPort`, `AppStatusResolver`, `AttachmentStatusResolver`, `WebhookStatusResolver`, `RoleLookupService`, `UsageCounter`, `EntityByIdResolver` | `modules/core`, `config/communication` |
| `endpoints.yaml` (`EndpointRegistry`) | every inter-service call resolves through it — no endpoint string in Java |
| The public-door list (`PUBLIC_GET_ENDPOINTS`) | `.env.example`; `PublicSurfaceTest` proves it matches the controllers |

What the host takes as-is: the audit sink (`AuditLogWriter` + `JdbcSavepoint`,
so an audit failure never rolls back the row it observes), partition management
(`LogPartitionService`, daily), the distributed scheduler lock (`SimpleMutex`),
idempotency (`Idempotency-Key`, caller-scoped), the DB-driven rate limiter
(fail-closed), the generic lookup API, tracing (`RequestTrace` supplies the IP
the public throttle charges), and the window law (`ValidityView.isLive` — pinned
to agree with the host's `Windows` by `WindowsTest`).

One shared rule is enforced here rather than assumed: shared's cold-tier audit
export does not exist yet (decision **D1**), so `APP_LOGS_RETENTION_MONTHS` must
stay `0`, and `ProductionStartupGuard` refuses to boot a production profile with
any other value — pruning audit partitions would delete the chain behind
`?version=N`, `/revisions` and `/pricings/answering`.

## Testing

```bash
./gradlew test
```

| Measure | Value |
|---|---|
| Test classes | 46 files (48 suites, incl. nested groups) |
| Test cases | **417** (13 of them run only where `NOTRAIUS_SQL_URL` points at a migrated database) |
| Test source LOC | 12,913 — **48.5% of main source** |
| Services reached by a test | **12 / 12** |
| Failures | 0 |

Notarius carries the densest test suite in the estate. Its tests are split by
use case rather than by class — `LeaseCreationServiceTest`,
`LeaseCancellationServiceTest`, `LeaseSettlementServiceTest` all exercise
`LeaseAgreementService` — and almost every case carries a `@DisplayName`, so
the suite reads as a specification of the domain.

Guard tests that run with the suite:

- **`SqlLocationArchitectureTest`** — raw SQL and `JdbcTemplate` may live only
  in `repositories` packages or `*Repository.java` files; services and jobs
  call repositories and never hold SQL. Source-scan rather than bytecode
  (ArchUnit cannot parse Java 25 class files and would pass vacuously).
- **`LeaseConditionTermArchitectureTest`** — the terms a tenancy owes (condition
  flag, billing cadence) may be read from the lease only; the live pricing must
  not answer them, and the scan fails if the old calls return.
- **`OptimisticLockingContractTest`** — the three aggregates that carry money
  and legal truth (`Lease`, `Visit`, `Property`) must map Hibernate's
  `@Version` on `lock_version`, and must keep the client-visible content
  revision a plain column. The two numbers are not interchangeable, and this
  test is what stops a future edit from deleting the one that breaks ties.
- **`RevisionSnapshotArchitectureTest`** — every service must capture its audit
  "before" snapshot *before* it mutates the row. Captured afterwards, the entry
  states the new value on its old side and the past becomes unrecoverable while
  every write still succeeds — the quietest way to break `?version=`.
- `VisitSqlBootTest` — a full SQL boot (`ddl-auto=validate`) against a real
  PostgreSQL, enabled only when `NOTRAIUS_SQL_URL` points at one. It also
  proves the token at work: a write that lost the race raises
  `OptimisticLockException` instead of overwriting the winner.

### Concurrency model

There are two numbers per aggregate row and they mean different things.
`version` is the **content revision** clients read (birth is 1, every accepted
edit increments it). `lock_version` is **Hibernate's token** (V37): every
UPDATE is fenced with `AND lock_version = <loaded>`, so the loser of a race
writes nothing and gets 409 CONFLICT with a retry hint instead of silently
discarding the winner's edit. Money rows that move (deposits, breakdowns)
also take explicit row locks, which is why they do not need the token twice.

### What is frozen, and why

An offer is editable in place: an owner may change a price, its `checkable`
flag or the duration it points at, and that is correct — it changes what is
*offered from now on*. A tenancy is not editable: it is a contract, and what
it owes must survive the offer moving. Every read that answers "what does this
tenant owe" therefore reads the lease's own frozen columns, never the live
pricing:

| Term | Frozen by | Read from the lease by |
|---|---|---|
| Rent | `leases.rent_amount` | the calendar, the settlement, top-up pricing, `LeaseResponse.rentAmount` |
| Condition stage owed | `leases.checkable` (V38) | activation, the repair report, the move-out gate, `bypassesConditions`, `LeaseResponse.checkable` |
| Billing cadence | `leases.duration_id` (V39) | the installment calendar, the deposit grace window, `LeaseResponse.periodUnit/periodValue` |
| Deposit standard | `lease_deposits.standard_balance` | the notice engine |
| Funded matrix | `lease_deposit_splits` | `LeaseResponse.matrixTotal` |

Two deliberate refusals support the table: `rentOf` and `perUnitRent` no longer
fall back to `pricing.getAmount()`. A lease with no frozen rent is a row that
predates the freeze, and the only thing the pricing could answer is today's
price for a past contract — so both refuse with a 409 rather than reprice it.
`LeaseConditionTermArchitectureTest` scans the sources and fails the build if
`getPricing().isCheckable()` or `getPricing().getDuration()` ever comes back.

**The offer's history is queryable both ways** (see *Revisions* below). One
row at a time by version (`GET /asset/pricings/{id}?version=N`), and the whole
page **as of an instant** — `GET /asset/pricings/answering?propertyId=&at=`
answers "which prices answered on 3 March" without append-only price windows,
by reading the audit chain: each record's state at the instant is the `new_data`
of the newest entry written at or before it (`V42` indexes exactly that walk).
The chain decides *answering* the same way the live door does — the window must
contain the instant and the owner's act must be `LIVE` — so an expired window is
replayed as it stood then instead of being read through its replacement.

One limit, stated rather than hidden: the page is bounded by the rows that still
exist, so a pricing hard-deleted after the instant is not replayed (its versions
stay reachable by id through `?version=N`). Removal is refused while anything
depends on the row, which makes that a deliberate act rather than the normal end
of a price.

### Revisions — `?version=N`

A versioned row keeps **one truth**: editing a property, a price, a rule or a
standard moves the same row forward and bumps `version`. That is the storage
law, and it means the table cannot answer "what did this say at version 3" —
so the read doors take the version as a parameter and reconstruct the answer
from the audit chain, in the same DTO the live read returns:

| Door | `?version=` | revisions list | Who may read the past |
|---|---|---|---|
| `GET /asset/properties/{id}` | yes | `/revisions` | owner, staff |
| `GET /asset/pricings/{id}` | yes | `/revisions` | owner, staff |
| `GET /asset/pricings/answering?propertyId=&at=` | instant, whole page | — | same audience as the live list |
| `GET /asset/pricings/{id}/values/{policyId}` | yes | `/revisions` | anyone who may read the pricing |
| `GET /config/rules/{id}` | yes | `/revisions` | creator, staff |
| `GET /asset/properties/{id}/rules/{selectionId}` | yes | `/revisions` | anyone who may read the property |
| `GET /config/policies/{id}` | yes | `/revisions` | anyone who may read the row |

Semantics are deliberately narrow: **absent** or **equal to the row's current
version** is the live read (no chain lookup); **lower** is answered from the
chain; a version the chain does not hold is refused `404 NT-0212` rather than
answered with today's row; a non-positive one is `400 NT-0211`. Nothing is ever
fabricated.

Four things make the answer trustworthy rather than merely plausible:

- **The write cannot gap.** These four resources audit through
  `RequiredAuditWriter#writeRequired`, not the estate's fail-soft door: the row's
  version bump and its chain entry commit together or not at all, so
  `?version=N` can never name a revision the chain does not hold
  (`LogsService.createOrFail` is the same insert without the swallow). The rest of
  the module keeps the fail-soft posture — nothing reads its history by version,
  so an audit gap there costs a log line, not an answer.
- **A revision's status is computed at read time.** A snapshot freezes the window
  and the owner's act (`storedStatusCode`), never the clock's verdict: asking for
  version 3 gives the answer the live door would give for version 3 *now*, so the
  same version cannot read `LIVE` through one door and `EXPIRED` through the other.
- **The revision is a column.** `new_version` / `old_version` are generated by the
  database from the payload (`V41`), with partial indexes, so the past is an exact
  seek on `(entity_id, reference_id, version)` rather than a scan of the record's
  history evaluating a CASE over JSON.
- **Discovery is a door, not a guessing protocol.** `GET /{id}/revisions` lists
  the revisions the chain holds — number, when each became current, and which one
  the row states now — so a client holding version 4 learns whether 3 exists
  without provoking `NT-0212`. Versions and instants only, never authors: a
  public door must not become a staff-directory.

Three doctrines make the rest trustworthy:

- **Every write is a revision.** Status switches bump the version too (rule
disable/enable/retire, policy disable/enable, and the policy-value pause/resume
sweeps), because a status the row states is part of its truth. Each one writes
its before/after entry in the same transaction as the bump.
- **The old side is the old truth.** The snapshot is captured before the first
  setter runs, and `RevisionSnapshotArchitectureTest` fails the build otherwise.
- **One window law.** `Windows` (`[from, to)`, clock-first) is the only
  implementation of "does this validity answer now": pricing, rule selections and
  the lease's answering-price gate all read it, so they cannot disagree — as two
  of the three did before, one of them reporting a next-season selection as
  already applying.

**Known limits, stated plainly.** Visits and leases publish a `version` but do
not accept `?version=`: their responses carry derived `nextActions` that belong
to *now*, and a historical answer must not borrow today's actions. Deposit
splits publish versions and are replayable from the chain, but have no by-id
door. An as-of page is bounded by the rows that still exist, so a pricing
removed after the instant is not replayed. All three are additive work, not
corrections.

### Every lock has a door (the chicken-and-egg audit, UPGRADE §11)

A promise in `PLAN.md` is only real if some endpoint can keep it. The rules that
follow exist because each one closed a state the module could enter but never
leave:

- **A deleting property releases what it holds.** `deleteProperty` forgets the
  property's rule selections and amenity holdings (one chain entry each) before
  it flips the row to `DELETED`. The catalogue's in-use gates count rows by
  entity, never by the owning property's status, and every door that would
  release them answers `404` for a deleted property — so without this the
  catalogue would keep a lock no one alive could lift.
- **`DISABLED` is deletable.** A settlement birth leaves the property there by
  design; requiring `PUBLISHED` first made deleting a retired home a public act.
- **A number can be taken back.** `DELETE /asset/pricings/{id}/values/{policyId}`
  (and `/values/bulk`) forgets the owner's value for one standard. This is the
  plan's own escape from `PRICING_DURATION_CHANGE_BLOCKED`: refuse-by-name now
  names the door.
- **A value funds something.** Values must be greater than zero and whole
  numbers when the standard counts units (`NT-0096`), and a counting standard's
  bounds are whole numbers above zero (`POLICY_BOUNDS_INVALID`) — the lease
  matrix multiplies by a count of installments and refuses a zero commitment, so
  a number it cannot use is refused where the owner can still fix it.
- **Publication asks for a current number.** A present-but-flagged (or paused)
  mandatory value no longer passes the publication gate (`NT-0094`): publishing
  is the listing's claim that it can be leased, and it is now checked against the
  same law the lease matrix applies.
- **A standard is born with its deposit type.** `POST /config/policies` refuses a
  code with no `LEASE_DEPOSITS` type (`NT-0213`) instead of letting every
  property in its world publish and then refuse every tenant.

### Filters — a typo is a refusal, not an empty page

Catalogue and list doors validate their filters against the rows that own the
values (`notraius.statuses`, `notraius.entities`) through `QueryFilters`: a
status that belongs to another entity's world, an entity namespace that does not
register, or a search longer than 200 characters is `400 NT-0210` with the
rejected value named. The alternative — pushing the string into SQL and
answering `200` with an empty page — reads as *"you have no properties"* to an
owner whose status simply had a typo, which is why the visit and lease pages
already refused by name (`NT-0208`/`NT-0209`) and the rest now match.

Every list also orders by a **total** key: the documented column plus `id`. A
bulk create stamps many rows with one instant, and a sort on the instant alone
can repeat a row on page two while dropping another.

## Running locally

```bash
# postgres + redis must be up (see the estate compose file)
./gradlew bootRun          # loads .env, migrates via Flyway, serves :8090
./gradlew test             # 417 tests
curl localhost:8090/actuator/health
```

`.env.example` documents every variable; `.env` carries the dev values
(localhost DB/Redis, dev secrets, port 8090). Note that `PUBLIC_ENDPOINTS` is
empty by default, so every route requires a session.

## Documentation

| File | What it is |
|---|---|
| [`PLAN.md`](PLAN.md) | The thinking layer — 3,166 lines covering the guard estate, then types → durations → config → asset → visits → lease → cross-cutting laws |
| [`DATABASE.txt`](DATABASE.txt) | **The schema authority** (v4.0, Consolidée). Read in full; edited only with operator approval |
| [`UPGRADE.md`](UPGRADE.md) | The access-control audit: 201-endpoint review, four findings, chicken-and-egg analysis, and the decided D1–D3 / N1 model with its implementation surface |
| [`CLARIFICATION.txt`](CLARIFICATION.txt) | Operator rulings applied to the plan |
| [`../UPGRADE.txt`](../UPGRADE.txt) | Estate-wide upgrade record |

## Naming

Package `com.notraius` · database `notraius` · schema `notraius` ·
application `springboot.notraius` · activity code `NOTRAIUS`. The seeder
vocabulary mirrors the shared estate post-V24 with the logistic-domain entries
removed.
