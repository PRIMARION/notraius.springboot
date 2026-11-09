# notarius — Access-Control Audit & Upgrade Plan

Consolidated record of the access-control review: the original endpoint audit
(GAP 1–4), the codebase-wide chicken-and-egg findings (C&E 1–4), the decided
access model (D1–D3 + N1), and the implementation surface (I1–I8) that carries
it out.

Status legend: ✅ applied · 🔜 decided, not yet implemented · ⏸ parked · ✅ accepted / no change

---

## 1. Original audit — endpoint → service review (194 endpoints)

Full audit of every endpoint against its backing service method. Architecture
verified sound: authorization is a **5-layer chain** evaluated in
`shared/.../SecurityExpressionService`:

1. **Role gate** — `@authz.authority('X', 'GTE', 'ACTIVITY', 'OP')`
2. **Permission row** — fail-closed; missing row = denied (`PermissionSeeder`
   mirrors every annotation into the permission table at boot)
3. **Consent delegation** — live grant in the actor's own shape
4. **Plan-limit policy** — `ActivityPolicyGuard`; fail-closed on exception
5. **Rate-limit guard** — runs *outside* the fail-closed catch: a quota breach
   is a client-visible 429, never a silent 403

Then the **service-layer party check** (`OwnershipGuard.requireOwnership` →
each service's `canAccess`).

### Key facts established

| Fact | Value / source |
|---|---|
| Authority levels | `UNAUTHENTICATED(-1) < PERSONAL(0) < APP(1) < ENTERPRISE(2) < SERVICE(3) < AGENT(4) < ADMIN(5) < SYSTEM(6)` — `RoleHierarchy.java`, `V2__seeders.sql` |
| Anonymous resolves | level **-1** → fails every `PERSONAL GTE` gate |
| Normal registered session | carries a known code (e.g. `PERSONAL`, level 0) → clears all `PERSONAL GTE` gates |
| GET/LIST plan limits | `GET`/`LIST` are **not** in `SubscriptionGuard.INITIATING_OPERATIONS` → reads are never quota-gated |
| Unknown role code | `matchKnownRole` returns null → level -1 → **every** 4-param endpoint denies (closed-set matcher, `#W0-02`) |
| `PUBLIC_ENDPOINTS` | `application.yaml:263` → `${PUBLIC_ENDPOINTS:}`; `.env.example:140` → **empty** → logged-out caller gets 401/403 on every GET |

### The four original gaps

| ID | Finding | Fix | Status |
|----|---------|-----|--------|
| **GAP 1** | `LEASES.RESOLVE` declared `@PreAuthorize` at `'PERSONAL'` while the service **skips the party check** → any user could resolve any lease | Changed to `'ADMIN'`. `PermissionSeeder` reconciles the row on boot | ✅ applied |
| **GAP 2** | `ReferenceService.getAmenity(UUID)` had no readability gate — any session could read a PENDING/REJECTED amenity submission (proposer PII) | `getAmenity(UUID, UUID callerId)` + `requireReadableAmenity`: **ACTIVE → all**, else proposer (`requireOwnership`) or `currentIsStaff()`, else `NotFoundException(AMENITY_NOT_FOUND)`. Both single and batch controller callers updated (batch needed a lambda capturing `callerId` after a compile break) | ✅ applied |
| **GAP 3** | Pricing/Terms/VisitPricing sub-resource reads threw `ForbiddenException` (403) on a hidden property → existence oracle | Added `requireReadableProperty` wrapping `requireOwnership` in try/catch → `NotFoundException(PROPERTY_NOT_FOUND)`, mirroring `requireUnpublishedInvisible` | ✅ applied |
| **GAP 4** | Assignee can read a visit before accepting it | **Accepted by design — NO CHANGE.** *"As for the fourth one, it's ok for assignee to see before even accepting. He should know on what he's embarking."* | ✅ accepted |

Verification after GAP 1–3: `./gradlew compileJava compileTestJava` clean;
`./gradlew test` = **320 tests, 0 failures, 0 errors** (10 pre-existing skips).

> ⚠️ Note: GAP 1–3 changes are **uncommitted**. `notarius` is its own git repo
> but the whole `modules/` tree is untracked, so `git diff` shows nothing for
> the edited files — verify via grep markers instead.

---

## 2. Chicken-and-egg findings (C&E 1–4)

> *"We have a lot of chicken and egg issues codebase wide … the gets on assets,
> visits and configs are private, meaning no one can view them … that creates
> the chicken-and-egg issues."*

All **34 GETs** across assets / visits / configs were resolved to their backing
service methods, with each service-level gate traced to its `canAccess`
implementation. **Verdicts: 32 keep, 2 fix** (later superseded/extended by D1–D3).

### 🥚 C&E-1 — A tenant cannot read the property they are leasing

The chain:

1. Lease birth flips the property `PUBLISHED → LEASED`
   (`LeaseAgreementService.java:1158` and `:1738`).
2. `PropertyService.requireUnpublishedInvisible` exempts **only** `PUBLISHED` —
   `LEASED` falls into the owner-only branch → `ForbiddenException` → caught →
   **404**.
3. `PropertyService.canAccess` = `callerId.equals(property.getOwnerId())` **only**;
   the tenant (lease `userId`) fails.
4. Yet `LeaseResponse.propertyId` is documented as *"The bridge to the property;
   read its details there."* — **the bridge 404s for the very person it points at.**

Blast radius — every property-anchored read funnels through
`requireReadableProperty` / `requireUnpublishedInvisible`:

| Endpoint | What the tenant loses |
|---|---|
| `GET /asset/properties/{id}` | address, details of their own home |
| `GET /asset/properties/{id}/amenities` | what's included |
| `GET /asset/pricings…` (list/get/batch) | the rent they're paying |
| `GET /asset/terms/pricings/{id}/values` | **the policy values of their tenancy** |
| `GET /asset/terms/properties/{id}/rules` | **the house rules** |
| `GET /visit-pricings…` | visit price history |

Partial mitigation only: `GET /leases/{id}` still works (party check on the
*Lease* entity), so they see rent/dates/deposits — but not the property, its
rules, or its terms.

Also hits an **in-flight visitor**: at lease birth there is **no `windDown`
call** and no visit-status guard in `LeaseAgreementService` (windDown runs only
on *unpublish*/*suspend* — `PropertyService.java:308,333`), so a live visit can
survive the flip; the visitor then loses property read mid-visit.
`VisitResponse.propertyId` has the same broken bridge.

**Original verdict: do NOT remove the guard — add the missing exemption.**
**Superseded by D2** (see §3): instead of party exemptions, `LEASED` becomes
publicly readable — one rung fixes tenant, visitor, and public alike, with no
party bookkeeping.

### 🥚 C&E-2 — Staff cannot inspect any non-published property

`PropertyService` has **no `SecurityAuthorityUtils.currentIsStaff()` bypass**
(grep: zero hits), while siblings do — `RuleService.isVisibleTo` checks staff,
and the GAP 2 amenity gate checks staff. So `ADMIN` gets **404 on a draft or
leased property** — even though the same ADMIN holds `PROPERTY SUSPEND` /
`REINSTATE` (`PropertyController:147,155`) and must evaluate the row to suspend
it. **Read is stricter than write for the same actor.**

**Verdict: add the staff bypass** — mirrors `RuleService.isVisibleTo`, doesn't
widen any user-facing surface. → **I2**

### 🥚 C&E-3 — Visits and leases answer 403 where the rest of the module answers 404

- `VisitService.getVisit` → `requireOwnership` → **403** for a stranger
  (`VisitService.java:1306`); missing id → 404. Probing distinguishes exists-vs-not.
- `LeaseAgreementService:940-942` deliberately re-throws **403**.
- `assessments` → 403 with a dedicated code (`VISIT_ASSESSMENT_FORBIDDEN`).

Contradicts the anti-enumeration doctrine just enforced on properties/amenities.
UUID unguessability keeps severity low.

**Verdict: keep the party gate; map read-path `Forbidden → NotFound`** — same
shape as the GAP 3 fix. Not to be confused with GAP 4 (accepted: the assignee
*is* a party). → **I8, parked, your call.**

### ⚪ C&E-4 — Anonymous access (info)

`PUBLIC_ENDPOINTS=` empty ⇒ a logged-out visitor gets 401/403 on **every** GET,
including the market page. This is config, not a service guard. → resolved by
**D1 / I4**.

### ⚪ Observation — config single reads skip the status filter

`getType` / `getDuration` / `getPolicy` expose rows (incl. DISABLED/RETIRED)
that their lists hide. **Keep** — admin vocabulary / global config, no user
privacy at stake.

---

## 3. Decided access model — D1, D2, D3, N1

### Your questions answered

> *"Before being a visitor he must first be an authenticated user. Right???"*

**Right.** `requestVisit(request, visitorId)` takes
`SecurityContextUtils.getCurrentUserId()` and `POST /visits` declares
`VISITS REQUEST` at `PERSONAL GTE` — anonymous (level -1) is stopped at the
door. Reconciled:

- **Acting** (request a visit, sign a lease, propose an amenity, resolve a
  dispute) → **always requires a session.** Unchanged, forever.
- **Looking** (read-only market data) → what D1 opens to unauthenticated callers.

### Decisions

| ID | Decision |
|----|----------|
| **D1** | Unauthenticated callers may **read** published/leased assets and their sub-resources, plus visit-pricings. **"As for configs things, they shall all be authenticated at least"** — refined by N1: only the *targeted* by-id/batch gets open; lists and rules browse stay authenticated; submissions stay ADMIN; visit **records** stay session+party. |
| **D2** | **`LEASED` is public** — *"Make it so everyone can see this property is currently leased … maybe even see up until when the lease extends. Like a leasehistory or such."* Dates-only public projection; **never** tenant PII, amounts, deposit/lease ids. Supersedes the C&E-1 party-exemption proposal. |
| **D3** | **Drafts stay 404** — the doctrine holds; only the `LEASED` rung changes. |
| **N1** | Asset DTOs are **id-bridges, not owners** → open the **targeted** by-id/batch config gets so anonymous rendering can resolve them (*"Not all but the targeted gets"*). Option B (embed config data in asset DTOs) deferred as a later optimization. |

---

## 4. Table 1 — Access matrix, all 34 GETs: today → after D1–D3/N1

| # | Endpoint | Data | Today | After D1–D3/N1 | Change |
|---|----------|------|-------|----------------|--------|
| 1 | `GET /asset/properties` | market list (PUBLISHED only) | session, `PROPERTY LIST` | **anonymous OK** | 🔜 flip pair → open tier |
| 2 | `GET /asset/properties/mine` | caller's own | session, caller-scoped | **session** (unchanged) | — |
| 3 | `GET /asset/properties/{id}` | one property | PUBLISHED→all; else owner→404 | **PUBLISHED + LEASED → everyone**; DRAFT/PAUSED/SUSPENDED → owner+staff, else 404 | 🔜 gate (D2) + open tier |
| 4 | `GET /asset/properties/{id}/amenities` | inclusions | same gate | same as #3 | 🔜 same |
| 5 | `GET /asset/properties/batch` | many | same gate | same as #3 | 🔜 same |
| 6 | `GET /asset/pricings?propertyId` | rent/price | same gate | same as #3 | 🔜 same |
| 7 | `GET /asset/pricings/{id}` | one pricing | same gate | same as #3 | 🔜 same |
| 8 | `GET /asset/pricings/batch` | many | same gate | same as #3 | 🔜 same |
| 9 | `GET /asset/terms/pricings/{id}/values` | policy values | same gate | same as #3 | 🔜 same |
| 10 | `GET /asset/terms/properties/{id}/rules` | house rules | same gate | same as #3 | 🔜 same |
| 11 | `GET /asset/terms/pricings/values/batch` | many | same gate | same as #3 | 🔜 same |
| 12 | `GET /visit-pricings?propertyId` | visit prices | same gate | same as #3 | 🔜 same |
| 13 | `GET /visit-pricings/batch` | many | same gate | same as #3 | 🔜 same |
| 14 | `GET /visits/{visitId}` | bilateral record | session, party-only → 403 | **session, party-only** (unchanged) | — (optional 403→404 = I8) |
| 15 | `GET /visits/mine` | own visits | session, caller-scoped | **session** | — |
| 16 | `GET /visits/assigned` | counterpart inbox | session, caller-scoped | **session** | — |
| 17 | `GET /visits/{id}/assessments` | assessments | session, party-only | **session, party-only** | — |
| 18 | `GET /visits/batch` | many | session, party-only | **session, party-only** | — |
| 19 | `GET /config/types` (list) | vocabulary | session, `TYPE LIST` | 🔒 **session — authenticated at least** | — |
| 20 | `GET /config/types/{id}` | one type | session, unguarded | ✅ **anonymous** (targeted) | 🔜 open tier |
| 21 | `GET /config/types/batch` | many | session | ✅ **anonymous** (targeted) | 🔜 open tier |
| 22 | `GET /config/durations` (list) | vocabulary | session | 🔒 **session** | — |
| 23 | `GET /config/durations/{id}` | one duration | session | ✅ **anonymous** (targeted) | 🔜 open tier |
| 24 | `GET /config/durations/batch` | many | session | ✅ **anonymous** (targeted) | 🔜 open tier |
| 25 | `GET /config/amenities` (list) | live catalogue | session, ACTIVE-only | 🔒 **session** | — |
| 26 | `GET /config/amenities/submissions` | moderation queue | **ADMIN** | 🔒🔒 **ADMIN — never opens** (proposer PII) | — |
| 27 | `GET /config/amenities/{id}` | one amenity | session; GAP 2 gate: ACTIVE→all, else proposer/staff | ✅ **anonymous** (targeted; gate already safe) | 🔜 open tier |
| 28 | `GET /config/amenities/batch` | many | session, same gate | ✅ **anonymous** (targeted) | 🔜 open tier |
| 29 | `GET /config/rules` (list) | PUBLIC+own+staff browse | session, `RULE LIST` | 🔒 **session** | — |
| 30 | `GET /config/rules/{id}` | one rule | session; `isVisibleTo`: PUBLIC/creator/staff | ✅ **anonymous** (targeted; PRIVATE → 404 for anon) | 🔜 open tier |
| 31 | `GET /config/rules/batch` | many | session, same gate | ✅ **anonymous** (targeted) | 🔜 open tier |
| 32 | `GET /config/policies` (list) | global config, no party check | session | 🔒 **session** | — |
| 33 | `GET /config/policies/{id}` | one policy | session, **unguarded** | ✅ **anonymous** ⚠️ (targeted) | 🔜 open tier (optional hardening, N2) |
| 34 | `GET /config/policies/batch` | many | session | ✅ **anonymous** ⚠️ | 🔜 open tier |

**Net:**
- **Assets + visit-pricings: 13 rows open to anonymous** (#1, #3–#13).
- **Config: 10 opens** — the targeted by-id/batch gets (#20, 21, 23, 24, 27, 28, 30, 31, 33, 34); **6 stay authenticated** (all lists + rules browse: #19, 22, 25, 29, 32, and #26 stays ADMIN).
- **Visit records: 5 rows stay session+party** (#14–#18).
- Total: **23 open · 20 authenticated · 1 ADMIN.**

---

## 5. Table 2 — The property read gate after D2 (the law)

| Property status | Who may read | Answer to everyone else |
|---|---|---|
| `PUBLISHED` | **anyone, incl. anonymous** | — |
| `LEASED` | **anyone, incl. anonymous** — *"someone lives here, until \<window\>"* (D2) | — |
| `DRAFT` | owner + staff | 404 (doctrine, D3) |
| `PAUSED` | owner + staff | 404 (owner withdrew it) |
| `SUSPENDED` | owner + staff | 404 (moderation) |
| `DELETED` | nobody | 404 (already `requireProperty`) |

Every sub-resource (#4–#13) inherits this law unchanged — they all funnel
through `requireReadableProperty`. The tenant's broken bridge and the
in-flight visitor's broken bridge are both fixed by **one rung**, with no party
bookkeeping.

Carried fix: **staff bypass** (`currentIsStaff`) still required for
DRAFT/PAUSED/SUSPENDED (C&E-2) — D2 does not cover it.

---

## 6. Table 3 — The "see until when the lease extends" projection (D2+)

| Field | Public? | Reasoning |
|---|---|---|
| `status: LEASED` | ✅ | explicit finding |
| Current tenancy window `leasedFrom` / `leasedUntil` | ✅ | *"up until when the lease extends"* — **dates only** |
| Past tenancy windows (history) | ⚠️ **your call** | dates-only history lets buyers see prior occupancies; **safe variant = current window only**. Pick one. |
| Tenant id / name / `createdBy` | ❌ **never** | PII; occupied-home safety |
| Rent amount, deposits, matrix, debts, payment ids | ❌ **never** | financial data of two private parties |
| `leaseId` | ❌ no need | would turn a public field into a probe handle for `GET /leases/{id}` |

**Shape options:** embed `leasedUntil` on `PropertyResponse`, **or** a dedicated
`GET /asset/properties/{id}/tenancy` returning `{status, from, to[]}`. Either
way it is a **projection** read from the lease rows for that property — the full
lease stays at `GET /leases/{id}`, party-only (row #14's rule).

---

## 7. N1 — Asset response → config follow-up dependency

**The finding:** *"Even asset policy responses don't own the data of that policy.
Same goes for all property-based config things — meaning we must again call the
config endpoint to fetch them."*

`VisitResponse` states it outright: *"Every relation travels as an id —
`propertyId` and `pricingId` are the bridges a client follows."*
`PolicyValueResponse` carries only a `policyId`; `RuleSelectionResponse` carries
only a `ruleId`; neither carries the name/label. An anonymous caller who clears
the asset gate would get a page of **unresolvable UUIDs** unless the config
by-id endpoints answer too.

### Table A — Asset response → required config follow-up

| Asset response (anonymous-visible) | Carries | Must resolve via | Opens |
|---|---|---|---|
| `PropertyResponse` | `typeId` (and state/type refs) | `GET /config/types/{id}` | targeted |
| `PropertyAmenityResponse` | amenity ref | `GET /config/amenities/{id}` — *mapper reads the `Amenity` entity, so code/label may already embed — **verify at impl, one grep*** | targeted (safe anyway) |
| `PricingResponse` | pricing → type/duration refs | `GET /config/durations/{id}` | targeted |
| `PolicyValueResponse` | **`policyId` only** | `GET /config/policies/{id}` | targeted |
| `RuleSelectionResponse` | **`ruleId` only** | `GET /config/rules/{id}` | targeted |
| `VisitResponse` / `LeaseResponse` | `propertyId`, `pricingId` bridges | `GET /asset/properties/{id}`, `GET /asset/pricings/{id}` | already open under D1 (rows 3, 7) |

### The fork (mutually exclusive)

| Option | What it does | Cost | Decision |
|---|---|---|---|
| **A — open the targeted gets** | Config `{id}` + `/batch` reads join the anonymous set; config **lists** stay authenticated | N+1 follow-up calls stay; by-id config becomes world-readable (existing gates mostly cover it) | ✅ **chosen — now** |
| **B — embed the data** | `PolicyValueResponse` gains policy name, `RuleSelectionResponse` gains rule label, property/pricing gain type label → config stays 100% authenticated | DTO changes across 3 modules + mapper work; bigger diff | ⏸ **deferred** — later optimization |

### Accepted costs (stated plainly)

- Anonymous market page **cannot populate filter dropdowns** (`/config/types`,
  `/config/durations`, `/config/amenities` lists) — filters are a login feature
  unless the ACTIVE-only lists are opened later (one line each).
- The full policy **catalog** and any non-live vocabulary remain login-only —
  by-id resolution is what anonymous needs; browsing the catalog is not.

### N2 / N3 (flagged)

| ID | Finding | Status |
|---|---|---|
| **N2** | `GET /config/policies/{id}` is **unguarded** (any status incl. DISABLED, `createdBy` visible). Today any registered user already sees it; opening widens registered→world. Optional hardening: DISABLED → 404 for anonymous (mirror the amenity gate) | ⚠️ your call |
| **N3** | Anonymous can't use market filter dropdowns (lists stay auth) | ✅ accepted; opening ACTIVE-only lists later = one line each |

---

## 8. Table 4 — Implementation surface I1–I8 (nothing done yet)

| # | Change | Where | Careful note | Status |
|---|---|---|---|---|
| **I1** | `LEASED` → readable by all (D2) | `PropertyService.requireUnpublishedInvisible` | one rung; sub-resources inherit automatically | 🔜 |
| **I2** | Staff bypass for non-published reads (C&E-2) | same method | mirrors `RuleService.isVisibleTo`; fixes ADMIN-cannot-GET-a-draft-it-can-suspend | 🔜 |
| **I3** | Open the permission pairs for the 13 asset/visit-pricing rows + 10 targeted config rows to anonymous: `PROPERTY LIST`, `PROPERTY GET`, `PRICING LIST`, `PRICING GET`, `PROPERTY_POLICIES LIST`(?), `PROPERTY_RULES LIST`(?), `VISIT_PRICINGS LIST`, `TYPE GET`, `DURATION GET`, `AMENITY GET`, `RULE GET`, `POLICY GET` | annotations on rows #1, 3–13, 20, 21, 23, 24, 27, 28, 30, 31, 33, 34 | ⚠️ **one-pair law: every endpoint declaring a pair must flip together or the app refuses to boot.** Grep all declarations of each pair first. The partition lands cleanly on **GET vs LIST**: each `* GET` pair is declared only by its `{id}`+`batch` (flip together ✓); all `* LIST` pairs stay `PERSONAL` | 🔜 |
| **I4** | Mechanism for anonymous | `PUBLIC_GET_ENDPOINTS` env whitelist, **no** `@PreAuthorize` on the handler, IP throttle in the handler | Verified against the code, not assumed: an anonymous door is **three** agreements — (1) path+method in `PUBLIC_GET_ENDPOINTS` (so `PublicKeyValidationFilter` does not demand `X-Service-Public-Key`), (2) `permitAll()` for that method in the host chain, (3) **no `@PreAuthorize` at all**. A `UNAUTHENTICATED` tier cannot work: with no session there is no principal, `matchesAuthority` resolves no permission row for an unknown/no code and **denies**, and `PermissionSeeder` boot-fails on a code with no catalog row (that is why the V12 migration was written and then deleted — the tier itself was the mistake). The rate-limit layer the annotation used to provide is restored in the handler by `PublicReadThrottle` (IP-scoped, no-op when no `rate.limits` row exists). Fixed siblings inside a public `/*` are named in `AUTHENTICATED_GET_ENDPOINTS` so they answer 401, not a method-security 403. `PublicSurfaceTest` fails the build on any drift between the three | ✅ |
| **I5** | Tenancy projection (`leasedUntil` or `/tenancy`) | `PropertyResponse` or new sub-resource + lease read | **dates-only** per Table 3; history-vs-current-only = your call | 🔜 |
| **I6** | Configs | **no change** | D1 refinement — lists stay `PERSONAL GTE` | ✅ |
| **I7** | Visit records | **no change** | party gate stands (GAP 4 accepted) | ✅ |
| **I8** | Optional: read-path 403→404 on visits/leases | `VisitService.getVisit`, `LeaseAgreementService:940` | C&E-3 — parked, your call, not part of D1–D3 | ⏸ |

### Authz facts to preserve at impl time

- `PERSONAL` = level 0; anonymous = level -1. Anonymous is therefore *below*
  every tier, which is why an anonymous door is an **unannotated** handler
  (I4), not a tier of its own.
- GET/LIST are never plan-limited (`INITIATING_OPERATIONS` = INITIATE, CREATE,
  SUBMIT, REQUEST, PAY, ACCEPT, SEND, GRANT, SIGN, ASSIGN, BULK_CREATE,
  CREATE_FROM_TEMPLATE).
- The rate-limit layer does **not** come from `@PreAuthorize`: the authority
  aspect charges nothing. It is restored per handler by `PublicReadThrottle`,
  keyed on the client IP `RequestTraceFilter` resolved. Removing the annotation
  is the mechanism; the throttle keeps the protection.
- `PermissionSeeder` writes a row per annotation at boot and **fails the boot**
  on an authority code it cannot resolve.

---

## 9. Summary of findings

| ID | Finding | Decision | Status |
|----|---------|----------|--------|
| **GAP 1** | `LEASES.RESOLVE` at PERSONAL with no party check | → `ADMIN` | ✅ applied |
| **GAP 2** | `getAmenity` unguarded — submission PII leak | ACTIVE→all, else proposer/staff, else 404 | ✅ applied |
| **GAP 3** | Sub-resource reads answer 403 on hidden property | `requireReadableProperty` → 404 | ✅ applied |
| **GAP 4** | Assignee sees visit before accepting | accepted — *"He should know on what he's embarking"* | ✅ no change |
| **C&E-1** | Tenant/visitor 404 on own LEASED property — lease breaks its own bridge | superseded by **D2** (LEASED public) | 🔜 I1 |
| **C&E-2** | ADMIN can SUSPEND a property but cannot GET it (no staff bypass) | add `currentIsStaff()` | 🔜 I2 |
| **C&E-3** | Visits/leases answer 403 to strangers vs module's 404 doctrine | map read-path Forbidden→NotFound | ⏸ I8 |
| **C&E-4** | `PUBLIC_ENDPOINTS=` empty → anonymous can't browse market | resolved by D1/I4 | 🔜 |
| **D1** | Anonymous reads on assets + visit-pricings; **configs authenticated at least**; visit records session+party | stands; **requires N1 to render** | 🔜 I3, I4 |
| **D2** | Everyone sees a property is LEASED, until when (dates-only; no tenant/financials) | stands | 🔜 I1, I5 |
| **D3** | Drafts stay 404 | stands | ✅ |
| **N1** | Asset DTOs are id-bridges → open **targeted** by-id/batch config gets (10 rows); lists + rules browse stay auth; submissions stay ADMIN | Option A now, Option B later | 🔜 Table B |
| **N2** | `GET /config/policies/{id}` unguarded (any status, `createdBy` visible) | open as targeted; optional DISABLED→404 hardening | ⚠️ your call |
| **N3** | Anonymous can't use market filter dropdowns | accepted; lists may open later | ✅ accepted |

**Bottom line:** 32 of 34 original guards stay as they are — the "private"
reads are the ones *designed* to be private (drafts, other people's visits,
undecided submissions). The upgrade opens **reads** of market data
(assets, visit-pricings, and the *targeted* config lookups the asset render
chain depends on) to unauthenticated callers, makes `LEASED` a public rung so
the lease transaction stops locking out its own tenant, adds the missing staff
bypass — and keeps every submission, moderation queue, and bilateral record
behind a session.

---

## 10. Version and filter audit (V1–V6, F1–F4)

Every versioned DTO publishes `version`, but the version columns are *content
revisions*, not history: one row, one newest truth. The audit below asked two
questions of every write — is the revision counted, and is the old side of the
entry the old truth — and two of every read: can the caller name the version
they need, and does a bad filter fail loudly.

| ID | Finding | Fix | Status |
|---|---|---|---|
| **V1** | Five handlers captured their "before" snapshot **after** mutating the row (`PricingService.updatePricing`, `PropertyService.updateProperty`, `PolicyService.updatePolicy`, `VisitPricingService.updatePricing`, `LeaseAgreementService.end` — deposits zeroed first), so `old_data` already stated the new value | snapshot moved above the first setter; each `!changed` path now returns the snapshot it already has; `RevisionSnapshotArchitectureTest` fails the build on a sixth | ✅ |
| **V2** | Status switches did not bump `version` (`RuleService` disable/enable/retire, `PolicyService` disable/enable, `TermsService.pause/resumeValuesOfPolicy`) although the response publishes it — two different truths shared one revision | bump + before/after entry in the same transaction, matching property/pricing/visit | ✅ |
| **V3** | No endpoint accepted `version`: a client holding v4 could not ask for v3, and the row cannot answer it | `?version=N` on properties, pricings, rules, policies; absent or current = live read, lower = audit chain, missing = `404 NT-0212`, non-positive = `400 NT-0211`; `V40` indexes the chain read | ✅ |
| **V4** | `issueWindow` deletes the previous window and `PricingRepository.findAnsweringAt` has no caller | the window's old dates are now recoverable from the (fixed) chain entry. A whole-page as-of query still needs append-only `pricing_windows` | ⏸ schema decision |
| **V5** | Visits and leases publish `version` but cannot answer a past one | **deliberate**: their responses carry derived `nextActions` that belong to now; a historical answer must not borrow today's actions. Documented limit | ✅ documented |
| **V6** | `visit-pricings` has update/pause/resume/delete but no by-id read, so a versioned row has no version door | left as is: adding it would open a **new public surface** (env + chain + throttle), which is an access decision, not a version fix | ⏸ |
| **F1** | Five list doors pushed a raw status/entity string into SQL: `GET /properties/mine?status=PUBLSIHED` answered `200` + empty page, i.e. "you have no properties" | `QueryFilters` validates against `notraius.statuses`/`notraius.entities`; `400 NT-0210` names the rejected value. No hardcoded lists | ✅ |
| **F2** | `search` was unbounded and un-collapsed | trimmed, inner whitespace collapsed, capped at 200 characters (refused, never truncated) | ✅ |
| **F3** | Lists sorted by a non-total key (`createdAt`, `value`, `code`); a bulk create stamps one identical instant, so page two could repeat one row and drop another | each list appends `id`, the tie-break the visit/lease pages already used | ✅ |
| **F4** | `.env.example` documented `PAGE_MAX_SIZE`, but every notarius list clamps through `PaginatedResponse` which reads `MAX_PAGE_SIZE` from the environment | env file now documents both names and says which one actually bounds a notarius page | ✅ docs |

Proved against a database migrated from `V1` to `V40`, not assumed:
`NOTRAIUS_SQL_URL` pointed at a fresh database and the full suite ran green —
**368 tests, 0 failures, 0 skipped, 45 suites** — including
`revisionReadAnswersOnlyWhatTheChainHolds`, which writes a real chain (birth,
two edits, a non-object payload, a non-numeric version) and asserts the read
returns only the version asked for and is not aborted by the junk rows.

## 11. Chicken-and-egg audit (E1–E6) — every lock has a door

`PLAN.md` promises exits that the code did not always build: "deletion retires
references" (4.1), "the owner removes or edits the values first" (4.3), "a
mandatory standard governs the property's world" (3.2). Each row below is a state
the module could enter and not leave, or a promise with no endpoint behind it.

| ID | Finding | Fix | Status |
|---|---|---|---|
| **E1** | **A deleted property kept its references.** `deleteProperty` flipped the row to `DELETED` and nothing else. The catalogue's in-use gates count rows by entity — `RuleService.referencedByAnyone` (`existsByRuleId`), `propertyService.isAmenityHeld` (`existsByAmenityId`) — and never look at the owning property's status, while every door that would release them answers `404` for a deleted property (`TermsService.requireProperty`, `PropertyService.requireProperty`). A rule held by a deleted property could never be disabled, retired or made private; an amenity never retired; the "or goes away" half of 3.1's lock was unimplemented | `deleteProperty` forgets the property's rule selections (row + window) and amenity holdings before the flip, each removal its own chain entry; selection-before-validity ordering is flushed explicitly | ✅ |
| **E2** | **`DISABLED` was not deletable.** The lease machine itself writes `DISABLED` at settlement birth (`LeaseStateService.disableProperty`), and `deleteProperty` accepted `DRAFT/PUBLISHED/PAUSED` only — so retiring a home required re-publishing it to the market first | `DISABLED` added to the delete set; deletion stays refused from `SUSPENDED` | ✅ |
| **E3** | **A standard could be born without the deposit type it becomes.** `createPolicy` accepted any code; `LeaseAgreementService.derive`/`writeDeposits` resolve every commitment through `LEASE_DEPOSITS` + the policy code and throw `LEASE_DEPOSIT_TYPE_MISSING` otherwise. Only `ADVANCE/SECURITY/ELECTRICITY` are seeded, so an admin naming `PARKING` made every property in that world publishable and unrentable | creation refuses `NT-0213`, naming the type to create first (the catalogue already has the door: `POST /config/reference/types`) | ✅ |
| **E4** | **A value the lease can never use was accepted, and publication did not ask.** `requireValidValue` checked sign and bounds only: zero and `2.5` passed for a `UNITS` standard, while `commitment()` refuses a zero total and `intValueExact` throws on a fraction — after publication. `requireMandatoryValuesComplete` checked presence, not currency, so a `FLAGGED` number published fine and then refused every tenant | values must be `> 0` and whole for counting standards (`NT-0096`); bounds must be `> 0` and whole for `UNITS` (`POLICY_BOUNDS_INVALID`); `PolicyValueSeeder` refuses an unfundable floor so a birth or a mandatory flip rolls back; publication requires an `ACTIVE` mandatory value (`NT-0094`) | ✅ |
| **E5** | **A count-valued pricing could never change duration.** `updatePricing` refuses on `hasCountValuedTerms`, PLAN 4.3 promises the owner "removes or edits the values first", and no endpoint removed a value — the refusal was permanent | `DELETE /asset/pricings/{id}/values/{policyId}` and `/values/bulk` (`PROPERTY_POLICIES.FORGET`) with their own chain entries; the duration refusal now names the door | ✅ |
| **E6** | **Correction to the audit I delivered.** I reported that `PolicyResolver.findCandidates` ignoring a policy's `entity`/`type` made 4.3's per-type standards and 6.0's hotel case unreachable. Re-reading the plan: 4.3 explicitly wants a nightly pricing to carry "a different figure on the nightly", and 6.0's hotel case is a world with **no** standard, not a filtered one. Cross-type applicability is intended; no change | — | ⚪ no defect |

**Deliberately not changed.** A `CONFIRMED` lease that neither party moves on has
no sweep: PLAN 6.2 calls a stalled stage a support-ticket matter and the dispute
door (`POST /leases/{id}/disputes`) is the exit that exists. Adding an automatic
expiry would write a rule the plan does not state.

### Versioning — is the shape the best possible? (audit of the audit)

The law is *one row, one truth*: the row holds only its newest state, and the
past lives in `notraius.logs` as before/after JSON. The alternatives measured:

| Model | Wins | Costs |
|---|---|---|
| **ours (log as storage of record)** | reads are plain JPA — nothing to filter, no way to see two current rows; one audited table for every entity; no per-entity history schema; the same rows compliance and support need anyway | history is JSON, not columns: no relational as-of join, and the record is only as good as the audit write |
| temporal rows / `*_history` tables | SQL-native `WHERE version = ?`, indexable, joinable | every read grows `valid_to IS NULL` (the exact bug class the one-row law forbids), every schema change doubles, storage doubles |
| event sourcing | replay, projections, true state-at-time | an event schema per entity, projections and snapshots — far past this module, and the audit log already is the append-only log |
| dedicated `revisions(entity, ref, version, payload, actor, at)` | typed, indexable, no dependence on the audit writer | a second write path per service, duplicating what the log holds — worth it only if history becomes a queried product |

**Verdict:** the shape fits the plan and is the best of the four for *revision by
id*, which is what the doors expose. It is not yet "the very best possible" on
four points, in severity order:

1. **The record can gap.** Every service audits inside a `try/catch` that logs a
   warning and lets the business write commit; a failed audit write therefore
   loses the revision while the row moves on, and `?version=N` honestly answers
   `404 NT-0212` for a version that existed. Fail-closed for the four versioned
   entities (audit in the same transaction, let the exception propagate) is the
   fix — a one-line change per service — but it trades availability for history,
   so it is an operator decision, not a tidy-up.
2. **The snapshot freezes a derived status.** A pricing's `statusCode` is
   `effectiveStatus(row, now)` at write time, so `?version=<current>` can say
   `LIVE` where the live read says `EXPIRED`. Either re-derive on read or state
   that a snapshot records what the row said when it was written. (Stated here as
   the intended reading: history is what was written, the live door is what the
   clock says now.)
3. **The version is not a column.** `V40` indexes `(entity_id, reference_id,
   created_at, id)` and the revision filter rides inside JSON, so a read scans
   that record's entries. A generated `new_version` column plus
   `(entity_id, reference_id, new_version)` turns it into an exact seek. Cheap
   (`V41`), mechanical, and the only performance claim worth making without a
   measurement is that it is *bounded and small today*.
4. **Discovery is guesswork.** A client holding v4 learns that v3 is gone only by
   asking and reading `NT-0212`; there is no door that lists the revisions a row
   holds. A `GET /{id}/revisions` (authorised, same visibility as the row) would
   close that without touching storage.

Proved against a database migrated from `V1` to `V40`, not assumed:
`NOTRAIUS_SQL_URL` pointed at a fresh database and the full suite ran green —
**377 tests, 0 failures, 0 skipped, 45 suites** — including the new
`forgetValueRemovesTheRow`, `flaggedMandatoryValueStopsPublication`,
`deleteReleasesReferences` and `disabledPropertyCanBeDeleted`.

## 12. Versioning, second pass (V7–V10, Q1)

§11 answered *is the shape right* — it is — and listed the four places where it
was not yet the best it could be. This section closes them, plus one design defect
the reading turned up on the way.

| ID | Finding | Fix | Status |
|---|---|---|---|
| **V7** | **The record could gap.** All four history-bearing services audited through the fail-soft door (`catch (RuntimeException) → log.warn`), and underneath it `LogsService.create` swallowed the insert failure too (savepoint rollback, `return null`). A failed audit write therefore let the row move to version `N+1` with no chain entry, and `?version=N` answered `404 NT-0212` for a version that had existed — the one failure the whole model exists to prevent | `RequiredAuditWriter#writeRequired` (host interface) + `LogsService.createOrFail` (same insert, no swallow); the four versioned services write through it, so row, bump and entry commit together or not at all. The other eight services keep the estate's fail-soft posture — nothing reads their history by version — and both doors are documented side by side in `RequiredAuditWriter` | ✅ |
| **V8** | **The revision was not a column.** `V40` indexed `(entity_id, reference_id, created_at DESC, id DESC)`, but the version filter rode *inside* the JSON, so a revision read walked every entry of that record and evaluated a CASE per row — the index only narrowed the set it walked | `V41` materialises `new_version` / `old_version` as `GENERATED ALWAYS … STORED` (the same `jsonb_typeof` + regex + cast guard the read used to carry, so a malformed payload is NULL instead of a SQL error) with **partial** indexes; `findRevisionSnapshot` is now a UNION ALL of two index seeks. `V40`'s index is dropped in the same migration: it served only the read it was written for, and a third btree on every audit insert with no reader is a cost, not a feature | ✅ |
| **V9** | **Discovery was a guessing protocol.** A client holding version 4 learned whether 3 existed only by asking and reading `NT-0212`; nothing listed a record's revisions | `GET /{id}/revisions` on properties, pricings, rules and policies — version, the instant it became current, and whether it is the row's current one. Same audience as the single revision (owner/creator/staff; public for a standard, which is public configuration), throttled as a read, enumerated in `PUBLIC_GET_ENDPOINTS`. Versions and instants only: publishing actor ids on a public door would open a staff-directory surface no decision asked for. **197 → 201 endpoints** | ✅ |
| **V10** | **A snapshot froze the clock's verdict.** `statusCode` is derived (`LIVE`/`PAUSED` from the act, `EXPIRED` from the window), and the DTO froze it at write time. So the same version read `LIVE` through the history door and `EXPIRED` through the live one; and a revision written while its window was still in the future was `EXPIRED` forever — even once the window answered, because the act it was derived from was no longer in the payload | `PricingResponse.storedStatusCode` publishes the owner's act alongside the derived word (additive field, filled by the mapper), and the history read re-derives `statusCode` at the instant of the read through the same law the live door uses. Snapshots written before the field existed fall back to the frozen word, mapping the one ambiguous case (frozen `EXPIRED`, window answering now) to `LIVE` | ✅ |
| **Q1** | **Three copies of the window law, two of them wrong differently.** `PricingService` asked "begun and not ended", `TermsService` asked only "not ended" (so a selection made for next season read `ACTIVE` today), `LeaseAgreementService` asked its own version inline. The plan states the rule once (4.2: the window moves the status; while no window contains the present instant the row stands `EXPIRED`) | `Windows.contains` / `Windows.statusIn` — half-open `[from, to)`, clock-first, one implementation. Pricing, rule selections and the lease's answering-price gate all read it; `WindowsTest` pins the boundary | ✅ |

**Deliberately not done.** A whole-page *as of* an instant still needs
append-only price windows (`pricing_windows`), and `PricingRepository.findAnsweringAt`
therefore still has no caller — a schema decision, not a read-path one. Child rows
without a by-id door (policy values, rule selections, deposit splits) still expose
their versions only through the parent's chain: adding a door is an access
decision, not a version fix. `visit-pricings` still has no by-id read, unchanged
from §10/V6.

**Quality, same pass.** Batched the reference release on deletion (one flush for
all selections, then their windows, instead of a flush per row — same FK order,
one round trip); removed two stale javadoc blocks that a later edit had left
stacked above the live comment in `RuleController` and `PolicyController`; and
found a hole in the *tests*: four service tests built their `ObjectMapper` without
`findAndRegisterModules()`, so every audit snapshot containing an `Instant` failed
to serialize — and the fail-soft posture logged a warning and carried on, which is
why nobody noticed. Production registers the module (Spring Boot's auto-config),
so this was a test-harness blind spot rather than a defect, but it hid exactly the
class of failure the chain exists to prevent; the four fixtures now build the
mapper the way the application does.

Proved against a database migrated from `V1` to `V41`, not assumed:
`NOTRAIUS_SQL_URL` pointed at a fresh database and the full suite ran green —
**395 tests, 0 failures, 0 skipped, 46 suites**. The chain tests are not mocks:
`revisionReadAnswersOnlyWhatTheChainHolds` writes a real chain (birth, two edits,
a JSON array, a non-numeric version) and asserts both that the revision read
answers only what the chain states and that the listing agrees with it, which is
what proves `V41`'s generated columns and partial indexes behave.

## 13. Versioning, third pass (V11–V13) — one audit path, child doors, the page as of an instant

§12 made the chain trustworthy and the read a seek. It left three debts that were
about *shape*, not correctness: the audit ritual was copied twelve times, child
rows published versions nobody could ask for, and only one record at a time could
be read in the past.

| ID | Finding | Fix | Status |
|---|---|---|---|
| **V11** | **The audit ritual was copied twelve times.** Every service owned its own `try { valueToTree(previous); valueToTree(current); writer.write(...) } catch (RuntimeException) { log.warn }` block, and each carried an `ObjectMapper` and a `RequiredAuditWriter` field only to run it. Twelve copies of one law means twelve chances for one of them to serialise in a different order, swallow at a different boundary, or forget which entity constant it belongs to — and the mapper field was dead everywhere else | `AuditTrail` (`common/audit`) is the single entry point: `record(entityCode, activityCode, referenceId, operation, previous, current)` serialises inside its own `try`, logs and swallows (fail-soft); `recordRequired(...)` propagates and refuses loudly (`IllegalStateException`) if the sink it was handed is not a `RequiredAuditWriter`, so a fail-soft sink cannot silently satisfy a fail-closed resource. All twelve services now delegate: nine one-line helpers, ~15 inline blocks replaced, the twelve `ObjectMapper` collaborators removed. Each service keeps its own `audit(...)` adapter, because that is where the entity constant is bound — the *law* is shared, the *namespace* is not | ✅ |
| **V12** | **Child rows published versions nobody could ask for.** A policy value and a rule selection both bump `version` and both write their chain entry, but only the parent had a by-id door: replaying one required reading the parent's revisions and hoping the child was unchanged in the snapshot you picked. And the selection response froze the clock's word (`statusCode`) the way the pricing response had before V10 | `GET /asset/pricings/{pricingId}/values/{policyId}` and `GET /asset/properties/{propertyId}/rules/{selectionId}`, each with `?version=` and `/revisions`, scoped by the parent (a child id alone is not an existence oracle) and reusing `RevisionService` + `Windows`. `RuleSelectionResponse.storedStatusCode` mirrors V10: the snapshot freezes the window and the act, and the status is re-derived at read time — with the same legacy fallback for snapshots written before the field. **V10's law, applied to the two rows that were still exempt** | ✅ |
| **V13** | **A page could not be read as of an instant.** `?version=N` answers one record; the market page's question is different — *which prices answered on 3 March* — and today's rows cannot answer it, because issuing a new window rewrites the row in place. `PricingRepository.findAnsweringAt` had been sitting there since §10 waiting for a schema change (`pricing_windows`) that the answer never needed | The chain already holds it: each record's state at an instant is the `new_data` of the newest entry written at or before it. `LogsRepository.findStatesAt` reads a whole page in one query (`DISTINCT ON (reference_id)`), `RevisionService.statesAt` maps it back into the live DTO, and `GET /asset/pricings/answering?propertyId=&typeId=&at=` filters with the *same* law as the live door — window contains the instant, owner's act `LIVE` — so a row reads `LIVE` exactly when it answered. A record absent from the map did not exist then (born later, or already removed); a null snapshot is a removal and is omitted rather than replayed from an earlier truth. `V42` restores `idx_logs_entity_reference_created` — V41 dropped it when its reader went away, and this read *is* that reader: the index is the `DISTINCT ON` ordering, one seek per record. **201 → 206 endpoints** | ✅ |

**Deliberately not done, and why.** The as-of page is bounded by the rows that
still exist, so a pricing *hard-deleted* after the instant is not replayed; its
versions remain reachable by id through `?version=N`, and removal is refused while
anything depends on the row, so this is a deliberate act rather than the normal
end of a price. Deposit splits keep their versions in the chain with no by-id
door. `visit-pricings` still has no by-id read, unchanged from §10/V6. Visits and
leases still publish a `version` without accepting `?version=` — their derived
`nextActions` belong to *now*.

**A consequence worth naming.** `TermsService` became fail-closed in this pass: a
resource that publishes a revision door must not be able to gap, so its values
and selections now write through `recordRequired` and its test sink is a
`RequiredAuditWriter`. That is the doctrine §12 established — *any resource that
publishes a revision door is fail-closed* — applied to the rows the new doors
exposed, not a new rule.

Proved against a database migrated from `V1` to `V42`, not assumed:
`NOTRAIUS_SQL_URL` pointed at a fresh database and the full suite ran green —
**407 tests, 0 failures, 0 skipped, 46 suites**. The as-of test is not a mock at
the repository level: `asOfReadReplaysTheNewestStateAtTheInstant` writes a real
chain (a birth, an edit, a row born later, and a removal) and asserts that the
instant picks the right side of each — including that a removal is returned as a
null snapshot rather than skipped, because the reader must know the difference
between "gone" and "never existed".

## 14. Shared-library pass (S1–S3) — take the estate's contracts, keep only the host's part

The versioning work in §11–§13 kept asking "what does the chain hold". This pass
asked the neighbouring question: **what does the shared library already do, and is
notarius honouring those contracts?** Four hundred and thirty-eight shared source
files later, the answer was mostly *yes* — the ports, resolvers, guards, mutexes
and filters are wired — with three places where a contract was satisfied in name
only.

| ID | Finding | Fix | Status |
|---|---|---|---|
| **S1** | **A batch port was answered by a loop.** `ValidityPort#findMany` ships a default that calls `find` once per id, and its javadoc says each host *may* override with a single `IN` query. Notraius did not. The caller is shared's `EventScanService`, which asks once per tick for the window of every due event — batch **100**, interval **1000 ms** — so the default was up to 100 round trips per second against `notraius.validity` for one page of work | `NotraiusValidityAdapter.findMany` overrides it: ids are filtered, de-duplicated, and read with one `findAllById`, and the map is built from the rows that came back. Missing ids stay absent — exactly what the loop answered, since the scan already treats a missing window as "skip this event". `find` and `findMany` share one row→view mapper so the two answers cannot drift | ✅ |
| **S2** | **A shared "do not enable" rule was unenforced here.** Shared's `LogPartitionService` can drop aged audit partitions, and shared's README states plainly that `app.logs.retention-months` must stay `0` in every host until the cold-tier export exists (decision **D1**) — otherwise audit history is destroyed in place. Nothing in this host refused it: the property defaults to `0` by absence, but a production deploy could set it and silently delete the chain that `?version=N`, `/revisions` and `/pricings/answering` read | `ProductionStartupGuard` now reads the property and **fails the boot** when a production profile has any positive value, naming D1 and the version chain. `.env.example` documents the knob and why it is pinned to `0`. The dev profile is deliberately not gated — the guard's job is production safety, not local convenience | ✅ |
| **S3** | **Two implementations of one window law, unpinned.** Shared states the estate rule once as `ValidityView#isLive` (`from <= now < to`, null `to` = open); the host states it again as `Windows.contains`, because its call sites hold instants rather than validity rows and must stay allocation-free. Two implementations are defensible; two *uncompared* implementations are how the three-copy bug in §12/Q1 happened | `WindowsTest.agreesWithSharedValidityView` walks eight windows — before, at and after both boundaries, open end, missing start, both null, and an inverted pair — and asserts the two answers are equal for every one. An edit to either that moves a boundary now fails a test instead of drifting | ✅ |

**Read but not changed, and why.** `JdbcSavepoint` is already used the way shared
documents it (`LogsRepository.insert`: savepoint → insert → release, rollback-to on
failure); the scheduler lock is already `SimpleMutex` with the documented
fail-open posture; `IdempotencyFilter` already applies to every guarded write; the
rate limiter's fail-closed `checkIpRateLimit` is what `PublicReadThrottle` relies
on; `PaginatedResponse.safe`'s native-query trap (NEW-47) cannot bite here because
notraius has no native queries; `MalwareScanHook` is optional in shared and no host
in the estate supplies one, so nothing was invented; and `PiiMasker` has no call
site because no notraius log line carries an email, phone or name.

Proved by the full suite — none of the three needs a database: **417 tests, 0
failures, 13 skipped** (the database-gated ones), **48 suites**; and, with
`NOTRAIUS_SQL_URL` pointed at a fresh `V1`–`V42` database, the same suite green
with **0 skipped**, which is what proves `V42` still applies cleanly and the
as-of read's chain test still passes.
