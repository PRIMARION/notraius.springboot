# NOTARIUS — PLAN (fresh rewrite; scope: everything up to and including VISITS)

> Written from zero against `DATABASE.txt` **v4.0 (Consolidée)** — the schema
> authority, read in full, never edited except for the operator-sanctioned
> rename `notarius.frequencies` → `notarius.durations` — and against a full
> read of every `*Guard` class in the codebase. No carry-over from any earlier
> plan. Prose only, no technical detail (no endpoints, no DDL, no config keys):
> this is the thinking layer; implementation details come later, when the
> operator has approved the thinking.

---

## Preamble — what this document is, and the boundary of its scope

This plan covers, in order, and stops at the end of: **types**, **durations**,
the **config module** (rules, policies, amenities), the **asset module**
(property, pricings, property-level policy values), and the **visits module**
(the first place money moves). Everything that exists in `DATABASE.txt` beyond
that line — leases, deposits, breakdowns, conditions, repairs, settlements,
notices — is deliberately *not* planned here. The schema rows for those things
may be mentioned when a visit flow brushes against them (a deposit standard is
*set* in this scope; a deposit is *collected* in the next one), but no
behaviour, no state machine, no edge-case catalogue is committed for them.
When the operator says "go further", the next plan picks up exactly where this
one's last sentence stops.

Three grounding sources were read completely before a word of this was
written:

**`DATABASE.txt` v4.0** describes five schemas. `notarius` holds the reference
and configuration layer: entities, types (hierarchical, self-referencing, bound
to an entity), statuses, validities (a reusable from/to window, permanent when
the end is null), policies (admin standards with min, max, mandatory flag,
status, and a `state_id` for geographic scoping), rules (title, visibility,
description, version, owning user, status), amenities (typed data — boolean,
integer, string — with code, label, status, and an owning user that is null
when admin created it), and durations (the renamed catalogue: a unit code and
a value saying how many). `property` holds properties (owner, status, type,
currency, description, an external location reference), pricings (property,
commercial type, duration reference, amount, version, validity window),
property-level policy values (pricing, policy, value, status, version),
property-rule selections (property, rule reference, validity, version) and
property-amenity holdings (property, amenity, value, status). `visit` holds
visit pricings (property, physical/virtual type, amount, a plain duration in
minutes, version, status) and visits (agent/owner user, visitor, version,
pricing, validity, status). `lease` and its seven tables are out of scope as
stated above. The general rules at the top of the file bind everything: every
table has id, timestamps and creator; **versioning happens through
`notarius.logs` with old and new data**; cross-module links are plain UUIDs
with no foreign key.

**The guard estate** (read in full — eleven classes) is the fence every flow
in this plan has to run inside. It is described in Part 0, because the visits
module in particular makes no sense without knowing why its money is driven
through one specific door.

**The operator's specification** (the informal text this plan restructures)
is the source of every business rule below. Where the spec is ambiguous, the
plan states the ambiguity explicitly and picks a default, marked as a decision
point for the operator to confirm or overturn during review.

---

# Part 0 — The guard estate: the eleven fences and how each project stands inside them

Before any business flow: the codebase already answers *who may touch what,
how often, under which delegation, and whose money moves through which door*.
Every flow in this plan is written to fit those answers rather than reinvent
them.

### OwnershipGuard — "is this caller a party to this record?"

`OwnershipGuard` is abstract, lives in shared, and is the estate's pattern
for record-level access: a service extends it, implements one method that
answers "is this user an owner or involved party of this entity", and calls
the require-form when it needs a throw. The contract of the class is richer
than it first looks. A plain ownership answer is only the first gate: when
the caller is *not* a party, the guard consults live delegation before
denying. Two delegation shapes exist. If the caller cleared a role gate
through an enterprise **seat**, the seat's grantor (the enterprise account)
is substituted for the caller and the domain question is re-asked as "would
the grantor be allowed?" — so an employee editing their company's property
passes because the company owns it. If the caller is an **APP actor**, every
live user-to-app consent for the exact activity and operation already cleared
on this request is enumerated, and each grantor is substituted in turn. Both
fallbacks fail closed: no permit, no live grant, a grantor who would fail the
domain check, or any exception — and the plain ownership answer stands. The
seat branch additionally requires the seat grant to name the very activity and
operation of the current call, so a grant minted for something else never
authorizes here.

How the projects stand: **contract** has six extending services (contract,
contract type, layout, metadata, party, term) and twenty-six require-calls —
the heaviest user; **logistic** four (engagement, fulfilment, agreement,
order); **shared** ten (apps, keys, subscriptions, chat, feedback,
notifications, support tickets); **primarion** eleven (devices, sessions,
profile, reputation, assessments, KYC, KYC attachments, phone, OAuth
authorization, OAuth usage, users); **pay** four (wallet, gateway, ledger,
payments). **notraius has none** — the host is born without a domain; this
plan's flows are what fill that void, and every record-touching service
described below extends it.

### ActivityAuthorityGuard — "may this authority level perform this operation at all?"

The permission table is a fail-closed allowlist: for a given activity and
operation, look up the permission rows; if there are **none, access is
denied** — an operation nobody has permissioned is an operation nobody may
perform. Each row names an authority code and a match type (greater-or-equal,
equal, less-or-equal) against the caller's resolved level, so one family can
carry a staff row and a service row simultaneously. An override form resolves
a *target* user's level for delegation scenarios. Consequence for this plan:
every activity family and operation introduced below (property, pricing, rule,
amenity, policy, visit operations) needs its permission rows to exist before
any endpoint using them can succeed — seeding them is part of building the
module, not an afterthought.

### ConsentGuard — "does a live delegation grant exist?"

A consent is live while its validity row is GRANTED *and* inside its window —
a grant whose end has passed counts as absent even before housekeeping flips
it. Two shapes: a user granting an app the right to perform an activity, and
an account granting a seat that right. Crucially, **no permission rows for an
activity plus operation means not delegatable**: the permission table is the
delegation catalogue, so the set of things apps can be consented for is exactly
the set that is permissioned at all. The guard also exposes the grantor list
behind an actor's live grants — the raw material for OwnershipGuard's
fallback.

### SeatGuard — "which enterprise delegations does this employee carry right now?"

An employee's delegation for an activity and operation exists only when all
three links are alive: a live seat assignment (GRANTED and in window), an
active seat, and a live seat consent on that seat for that permission — and,
as defence in depth, the consent's grantor must be the seat's own creator, so
an orphaned consent row is never a source of authority. The grantor is always
the seat's creator: the enterprise owner (or the platform account for
platform-level seats — the guard does not distinguish). Results are cached
briefly, but **errors are never cached** — a transient database failure must
not poison the cache for the whole TTL; it returns empty (fail closed) and
lets the next request retry. The grantor's authority level is re-verified on
every use by the caller of this guard, not here.

### SubscriptionGuard — "has this user exhausted their plan limit?"

The twin of the rate limiter, and the reason the visits module must declare
itself. Limits bind per permission row through the plan's features, and the
guard answers **only for initiating operations** — initiate, create, submit,
request, pay, accept, send, grant, sign, assign, bulk-create and two special
creates. Follow-ups (confirm, cancel, get, list, export, update) are never
limited: they operate on work a limit already admitted. The counting window
is the subscription's own validity row — renewal resets the counter cleanly,
and the free plan's open-ended window counts from its start to now. Three
escape hatches make it fail open by design: no live subscription means no
limit surface at all; no bound feature or a null limit means unlimited; an
unregistered family means nothing to count against. Usage counting is the
host's job — **every project, notraius included, already provides a
UsageCounter bean that counts rows in the host's own logs table** inside the
window. The caller resolves whose subscription to check (own user, app owner,
or the user a service acts for).

### RateLimitGuard — "how often may this happen?"

Sits at *both* policy entry points — the endpoint's authority expression and
the service-side policy enforcement — and is charged once per request because
both run in the same request cycle. A rule row for an activity, operation and
scope turns enforcement on; **no row means open**, the same contract the
subscription guard applies to limits. Two scopes are consulted because the
activity, operation and scope together *are* the rule identity: the user
scope counts the attributable principal (the user, or the acting app for a
service call), the IP scope counts the caller's address when the trace
carries one; each falls open independently. A null subject charges the shared
anonymous bucket — a missing identity is never a free pass. Breaches surface
as client-visible rate-limit responses, deliberately not as silent denials.

### ActivityPolicyGuard — "one call, all the policy layers"

The single switch host services call once per business operation instead of
hand-wiring per-activity checks. It resolves the actor, looks up the activity
family (an unregistered family is a no-op — authorization already happened at
the endpoint), then runs, in order: consent (APP actors only — an app acts for
its owner only within a live grant; user actors act for themselves; platform
service actors are trusted infrastructure by design), the subscription limit
(passing the original dotted code so the initiating-operation filter can see
the operation — a bare family would never look initiating), the rate limit
(on its own entry point so a quota breach is a client-visible rate response
rather than a silent denial), and finally it records the current permit —
which is what scopes OwnershipGuard's consent-delegation fallback to this
exact activity and operation. A platform-internal call with no attributable
principal passes everything, by design: the platform talking to itself is not
a user to be limited.

### How the five-layer endpoint gate composes with all of the above

Every endpoint declares an authority expression with four parts: required
role, match type, activity family, operation. Evaluation is layered. First
the role gate — and when a raw role fails, a seat fallback re-verifies that
the seat's grantor still holds the required level, recording the delegation
context so ownership and audit see it. Second the permission gate — fail-closed
allowlist; no rows at all means denied for every actor. Third delegation — an
APP actor needs a live grant in its own shape, which both requires and
suffices; a user who cleared the first layer through a seat already proved the
seat's consents. Fourth the plan-limit policy. Fifth the rate-limit policy,
deliberately outside the fail-closed catch so a quota breach is never a silent
denial. Five layers passed means the permit is recorded, which unlocks the
ownership fallback described above. For notraius this means: no endpoint of
any module in this plan works until its activity family, its operation
permission rows, and (where relevant) its rate-limit and plan bindings exist.

### PaymentOwnershipGuard — whose payment is it, and who may drive it

The pay-side fence that shapes the visits module's money completely. A
payment's platform stamp is an **ownership marker, not bookkeeping**: it
decides who drives the payment's lifecycle. Payments stamped with pay's own
platform are pay's generic surface's business (payment requests, splits,
escrow, admin refunds). Payments stamped with a *calling service's* platform
— which is what a notraius-created visit payment will be — are **service-
owned**: pay's generic routes must never touch them, there is no admin
override, and the owning service drives everything through pay's internal
server-to-server API. A null platform means an unowned row and fails closed:
an unowned payment is nobody's payment, and mutating it through a guessable
ownership is exactly the hole this guard closes. Pay's own service calls this
guard on eight distinct paths (generic refunds, escrow hold/release/dispute,
linked-payment mutations) — the guard is the wall between "pay's money" and
"everyone else's money". **Consequence for visits: notraius can only ever
create, settle and refund its visit payments through the internal API, never
through a user-facing pay route**, and the payment's platform stamp is what
lets notraius prove the entity reference on that payment is its own.

### PaySecretsGuard and ProductionStartupGuard — the boot fences

Pay refuses to start without its required secrets, listing every missing one,
because a missing HMAC key would make receipt verification tokens forgeable.
Every project — contract, logistic, notraius, pay, primarion — carries its
own production startup guard that refuses to boot under the production profile
with development-grade configuration: missing HMAC, encryption, database,
shared-database or redis passwords, and (in notraius and pay) the dev-only
source explorer must be off in production even if the environment ships it
enabled. notraius's guard already exists and already passes; nothing in this
plan changes it, and no flow below may assume a boot that these guards would
have refused.

### ReputationEligibilityGuard — the one domain guard, and why it matters as a pattern

Logistic's is the only business eligibility guard in the estate, and its
semantics were signed off deliberately: a *measured* score below the
configured bar fails; **no reputation data passes** (the bar keeps
poorly-scored drivers out, it does not lock out newcomers who have never been
rated); a transport failure while evaluating fails closed (a gate that cannot
decide is not a gate). It exists as a throwing form for doors and a predicate
for operator-facing questions. This is the pattern the visits module should
follow if any eligibility question ever attaches to visiting (for example, a
reputation bar on property owners): decide explicitly what "no data" means,
and never let a downstream outage silently become an allow or a deny.

### What the guard estate means for this plan, in one paragraph

Notraius is a host that already has the *wiring* (its usage counter, its
production guard, its logs service, its validity and status adapters, its
role lookup, its endpoint registry — and its registry already declares the
pay internal routes, the commission calculator, the system-user lookup, the
KYC-verified lookups and the reputation routes it will need) but none of the
*domain* (its activity class is explicitly empty, waiting for exactly these
modules). Building the modules below therefore always consists of the same
four moves: register the activity family and its permission rows; extend
OwnershipGuard on every record-touching service; call the policy guard once
per business operation; declare the endpoint authority expression so the five
layer gate runs. Everything else in this plan is business behaviour sitting
on top of those four moves.

---

# Part 1 — Types (the first reference catalogue)

## What a type is, and who touches it

A type is a labelled code bound to an entity — "this kind of row belongs to
that kind of thing". The catalogue is hierarchical: a type row can name a
parent type, and a null parent means a root. The entity binding is what makes
one code mean property kinds while another means pricing cadences, visit
formats or deposit kinds: the same table serves all of them, and the entity
id is the namespace. The specification's unspoken rule is stated plainly in
the schema itself — the property's type is *the type of the property only*,
bound to the entity whose code is PROPERTY. A property's type never leaks
into pricing, visit or policy semantics; those have their own types under
their own entities.

Management splits strictly by authority tier. The **admin manages**: creates
types, edits labels and descriptions, arranges the hierarchy, retires types.
**Personal and enterprise users view**: they read the catalogue to populate
their dropdowns and filters, and nothing else — a personal user cannot mint a
type, cannot rename one, cannot reparent one, cannot retire one. This split is
expressed through the generic lookup surface: the type table is registered in
notraius's lookup definitions with its methods declared per authority tier —
read methods for the personal and enterprise tiers, the writing methods
(matching the repository's save and delete capabilities) for the admin tier
only. The lookup service enforces this by asking the caller's authority
resolver whether the tier holds for the current caller; nothing is hardcoded
in the table itself. The host's lookup file is currently empty — registering
types (and durations, below) is the first concrete act of building this
module.

## Edge cases and strange cases — types

**Codes versus labels.** The code is the stable, machine-meaningful part; the
label is what humans read. Renaming a label is safe at any time and changes
every dropdown instantly. Changing a *code* is not a rename, it is a
behavioural edit: code is how other layers recognise a kind, so codes are
treated as immutable once a type has been used, and retired-and-recreated is
the path instead.

**A type in use cannot vanish.** A type referenced by a live property,
pricing or policy value is retired rather than deleted: the row stops
appearing in selection lists, existing references keep resolving, history
keeps meaning what it meant. The lookup surface's delete method exists for
admin use, but the service behind it must answer the "is anything pointing at
this" question first — a catalogue delete that orphans a property's type
reference is a corrupt property, and the plan refuses it.

**Hierarchy edits.** Reparenting a type moves a subtree; creating a cycle
(type A under B under A) would make the catalogue unresolvable, so any
reparent that would place a node beneath its own descendant is refused.
Retiring a parent does not cascade-retire its children — children keep their
own status, and a retired parent simply disappears from root-level selection
while its subtree stays reachable through its children's own entries.

**Concurrency.** Two admins editing the same type concurrently: last write
wins at the field level, but because every write is versioned through the
logs table with before and after data, neither edit is lost to history — the
operator can always reconstitute what each admin saw and when.

**Empty and whitespace codes, duplicate labels.** A blank code is never valid.
Two types under the same entity may share a label (a "Standard" room kind and
a "Standard" pricing cadence are different rows under different entities);
two types under the *same* entity with the same code are the same type and
are refused as duplicates.

**View tiers seeing retired types.** Read methods return the full catalogue
including retired rows when asked by id — history and existing selections
must resolve — but list methods used for selection UIs filter to active rows,
so a personal user's dropdown never offers a retired kind.

**The type of a type.** The hierarchy itself is not typed: parent links
always mean "kind of", never anything else, and the entity binding of a child
must equal the entity binding of its parent — a property kind cannot hang
beneath a visit format, because the entity is the namespace and the hierarchy
stays inside it.

---

# Part 2 — Durations (the renamed catalogue, and why the name now fits)

## What a duration is

A duration row is a unit code and a value saying how many: HOUR with value
ten, MONTH with value one, MINUTE with value thirty. The catalogue is seeded
by the admin through the lookup surface — registered like types, with read
methods open to personal and enterprise tiers and writing methods admin-only —
and the canonical units named by the specification are SECOND, MINUTE, HOUR,
DAY and MONTH. The rename from the old word matters conceptually: this table
never described *how often* anything repeats, it describes **how long one
unit of something lasts**, and every use in this plan is exactly that.

## Where it shines: pricings

A pricing carries two things that together say what it means: the commercial
type (MONTHLY, NIGHTLY, WEEKLY — the label the market reads) and the duration
that gives the commercial type its physical body. NIGHTLY, the specification
says, gets a duration of HOUR with value ten: a night is understood as ten
hours, fully and unambiguously, and any calculation that needs to know how
long a night *is* reads the duration row rather than guessing. MONTHLY gets
MONTH with value one. The type is the name; the duration is the physics.

## Edge cases and strange cases — durations

**The value must be meaningful.** The column is nullable in the schema, but a
duration with no value cannot answer "how long", so any duration referenced by
a pricing must carry a value of at least one; the catalogue may contain
placeholder rows for admin drafting, but a pricing never binds to one.
Zero and negative values are refused for the same reason — a night of zero
hours prices nothing.

**Months are not fixed lengths.** MONTH with value one means *one calendar
month*, from the fifteenth to the fifteenth, twenty-eight to thirty-one days
depending on where it lands — never "thirty days" and never "720 hours". Any
effective-price maths must walk the calendar, and a February monthly pricing
is legitimately cheaper in absolute hours than an August one while both are
correct. Similarly a DAY row across a daylight-saving transition is a
twenty-three or twenty-five hour day; the duration says "one day" and the
calendar decides what that costs. These are not bugs to fix later; they are
the semantics the catalogue exists to pin down.

**A duration cannot be edited out from under a pricing.** Changing a duration
row's value changes the physical meaning of every pricing bound to it — a
night that was ten hours becomes twelve, silently repricing nothing and
everything at once. Therefore: a duration in use by any live pricing is
retired rather than edited; new pricings bind to new rows. The admin who
wants "nights are now twelve hours" creates a new duration and the owner
migrates pricings deliberately, each migration visible as a pricing version
in the logs.

**Deleting a duration in use** is refused for the same reason as a type in
use: the reference must keep resolving.

**Visit pricings do not use this catalogue, and that is deliberate.** The
visit pricing carries its own plain duration in minutes — a visit is thirty
minutes or sixty, a simple scalar with no commercial label attached, and the
specification puts the durations catalogue to work in property pricings only.
The two remain consistent in *meaning* (both answer "how long") even though
one is a catalogue row and one is a number; no plan step converts one into the
other.

**Currency never touches durations.** A duration is time; the pricing's amount
is money in the property's currency. A duration row has no currency, no
amount, and no state — geography and money attach to prices and policies, not
to how long a night lasts.

---

# Part 3 — The config module: rules, policies, amenities

The config module is the reference layer the asset module consumes: rules
describe what living in a place is allowed to be like, policies set the
standards money-related behaviour must respect, amenities describe what a
place has. They have deliberately different governance shapes — user-owned
with visibility, admin-owned with standards, community-proposed with a
moderation lifecycle — and the specification is precise about each.

## 3.1 Rules management

### Creation, visibility, and the two worlds

Any user creates a rule: a title, a description, and a **visibility the
creator chooses at birth**. The two values are PUBLIC and PRIVATE, and the
difference is absolute. PUBLIC means **every authenticated user can view the
rule** — and, when selecting rules for a property (Part 4), any user may pick
it. PRIVATE means **only the creator can view it and only the creator can use
it** — it never appears in anyone else's lists, searches, or selections, and
its contents are not readable through any read path that does not belong to
its creator. There is no middle state, no "unlisted", no sharing-with-named-
users: the specification's world has exactly two lights, and the plan does not
invent a third.

Admin visibility over PRIVATE rules follows the estate's staff convention:
staff roles see everything for moderation purposes (the same way support
tickets expose themselves to staff), but a private rule is never *usable* by
anyone but its creator — admin moderation is read authority, not adoption.

### Versioning: every edit is a new truth, reconstructible from logs

When a creator edits a rule, the rule's version number changes and the logs
table receives a before-and-after entry for that edit. The specification's
requirement is exact: **through the logs, with before and after, we can
reconstitute things and fetch the right rule details based on version**. That
means the logs are not a courtesy trail here, they are the storage of record
for history: the current row holds the current version, and every prior
version is reconstructible by replaying its log entries — title, description,
visibility, status, and which attachments belonged to it at that moment. A
request for "the rule as of version three" is answered from the log chain, not
from the row, because the row only remembers the newest truth.

Two rules make this safe. First, the log entry is written **in the same
transaction as the version bump** — a version that exists without its
history, or a history without its version, is impossible, because both
commit or neither does. Second, creation is itself the first entry: version
one exists in the log from the moment the rule exists, so replay always has a
base to start from.

### The in-use lock: once someone relies on it, it cannot be switched off

The specification's sharpest rule: **when a rule has been created and at
least one person is already using it, its status can no longer be disabled.**
A rule is "in use" when at least one live property selection references it —
and the plan reads "at least one person" literally and conservatively: *any*
live reference locks the status, including the creator's own property's, because
the safer failure is a lock that is slightly too eager than a rule sliding to
disabled under a living property's feet. The lock blocks the status transition
to disabled; it does not block editing (edits bump the version as above, and
the in-use properties see the new version because they reference the rule, not
a snapshot of it — with their own selections' windows bounding when it
applies). When the last referencing property removes its selection or goes
away, the lock lifts and the creator may disable again. Disabling is therefore
always reversible; the lock is a live count, not a one-way ratchet.

Delete follows the same philosophy, harder: a rule in use is never destroyed.
The disable lock already protects users; deletion would break reconstitution
(history must remain replayable), so the path is disable, and hard deletion of
rules is not part of this plan at all.

### Visibility flips, and the orphan question they create

PRIVATE→PUBLIC is always allowed: sharing more is the creator's
sovereignty, and it only adds readers. PUBLIC→PRIVATE while *other* people's
properties reference the rule is **refused**: flipping it would orphan their
selections — their property would be silently holding a rule they can no
longer see or manage, which is worse than either visibility. When only the
creator's own properties reference it, the flip is allowed, because the
creator is the only person affected and the plan does not override the
creator's wish to withdraw a rule from the world. The same test — "does
anyone *else* reference it" — governs, distinct from the disable lock's "does
anyone reference it".

### Attachments: the part the specification warns us about twice

Rules can hold attachments, and the specification says *be careful*. The
cares are these. Attachments belong to the **rule, not to a version**: the
current set of files hangs off the current rule row, while each log entry
records which attachment identifiers belonged to the rule at that edit, so
reconstitution of an old version also reconstitutes its file list. A
PRIVATE rule's attachments are as private as its text — no read path exposes
them to anyone but creator (and staff moderation); a PUBLIC rule's
attachments are readable by every authenticated user alongside the rule.
Deleting an attachment the rule still references is refused; removing a file
first detaches it from the rule, and the detach is itself a versioned,
logged edit, because the rule's truth changed. An attachment never outlives
its rule: when a rule is eventually removed from all use and retired, its
attachments are detached with it rather than left orphaned in storage. And
because attachments are opaque references, nothing in the rule logic ever
opens, parses or trusts their content — they are files a user uploaded, and
the storage layer's own rules govern them.

### Filtering and finding rules

The specification mentions filtering in the selection context (Part 4), so
rule reads must support: creator's own rules (all of them, both
visibilities), the PUBLIC set (everything created by anyone), text search
over title and description, and status filtering. The invariant that ties it
together: **no read path ever returns a PRIVATE rule to anyone but its
creator**, so filtering happens *before* results are shaped, not as a
post-filter — a private rule must not leak through a count, a search
suggestion, or an autocomplete even if the row itself would later be denied.

## 3.2 Policies management (admin standards)

### Who owns what: the strictest governance in the module

Policies are **admin-managed end to end**: the admin account defines them,
sets the standards and the defaults and everything else — title, code,
description, the minimum and the maximum, whether they are **mandatory**, and
their status (enabled or disabled). The admin may also attach documents —
the second place the specification says *be careful* — because a policy is
where a regulatory text or a fee schedule PDF might legitimately live.
**Users can view the policies and that is all**: the personal and enterprise
tiers read the catalogue to understand the standards and to pick values
against them in the asset module, and they cannot create a policy, change a
bound, flip a mandatory flag, enable or disable one, or attach anything to
one. Writes are admin-tier only, exactly like types and durations; unlike
those, the read side here is the *entire* user-facing surface of the module.

### What a policy row means

It is a standard, expressed against an entity and a type: "for this kind of
thing, here is the allowed range and whether you must comply at all". The
minimum and maximum are numeric bounds; the mandatory flag says whether
compliance is required or optional; the status says whether the standard is
currently in force. The code gives it a stable identity (ADVANCE is the
specification's worked example), and the state identifier scopes it
geographically — the mechanism the specification builds an entire scenario
around, treated fully in Part 4's policy-values section: **a policy for Benin
can differ from a policy in Nigeria**, and the state on the policy is how the
system knows which world a standard belongs to.

### Enable, disable, mandatory — and what each does to the world

**Disable** is the admin's pause button: the standard stops being offered
for new value-setting and stops being enforced for new configurations. It is
not retroactive destruction — values already set against the policy by
properties in the asset module keep existing (they are that property's
history, versioned in the logs like everything else); what they lose is the
ability to be *changed to a new value* while the standard is paused, and the
publishing gate stops checking them. Re-enabling restores enforcement, and
at that moment existing values are re-evaluated against the bounds: a value
that still fits passes silently; a value the admin has since tightened out of
range is flagged on the property as "needs review" but is **not**
auto-corrected — the plan never rewrites a user's stored number behind their
back; it surfaces the conflict and the user (or the admin) resolves it by
editing, which is a versioned, logged act.

**Mandatory** is the compliance switch: a mandatory policy must have a value
set on every configuration it governs before that configuration can be
published — an unset mandatory policy is a publishing refusal, not a warning.
Making a policy mandatory *after* properties already exist does not
retroactively unpublish them (grandfathering: live configurations stay live)
but blocks any *new* publication missing the value, and flags existing ones
as incomplete for their next edit. An optional policy with no value simply
carries no standard; absence is legal.

**Bounds are advisory-strict**: a value must be within min and max when it is
set. If the admin later tightens bounds, existing out-of-range values are
grandfathered with the review flag described above — never silently clamped,
because a clamped deposit that quietly changed meaning is worse than a
visible conflict.

### Attachments on policies: the second *be careful*

The same discipline as rule attachments, with one asymmetry: policy
attachments are admin-authored documents, so they are readable by the users
who can view the policy (the whole user base — policies are public standards)
but writable by admins only. They are versioned with the policy the same way:
each logged edit records the attachment set, so "what did the standard say,
and what document backed it, at version four" remains answerable. A policy
never carries a user's file; if a personal user needs to attach something,
that belongs to their property, not to the standard.

### Viewing

Users read: the full enabled catalogue, filtered by entity, type and state
relevant to their context; the bounds; the mandatory flags; the descriptive
text and its attachments. Users do not read drafts (policies are created
enabled or not at all — there is no user-visible draft state), do not read
state scopes that do not apply to their properties' states (a Benin property
is shown the Benin standard, not a menu of every country's), and never see a
disabled policy offered as a choice (though a value already set against one
remains visible on the property, marked as paused).

## 3.3 Amenities management (the moderation lifecycle)

### The four-entry dance

The specification gives amenities the richest governance of the three, and it
is a lifecycle, not a permission set:

**Any user may propose an amenity.** Not create — *propose*. A personal user
fills the fields (the amenity's kind determines its data type: boolean for
yes/no amenities, integer for counts, string for free descriptions; a code, a
label, a description) and submits. Their proposal lands in **PENDING**. It
belongs to them: they are the creator of record, and while it is pending they
may **cancel** it — withdraw before any decision, which ends the proposal
without admin involvement. Cancellation is only possible *before* a decision:
once the admin has acted, the proposer's withdraw button is gone.

**When the admin account is the creator, the amenity becomes ACTIVE
automatically.** No pending state, no confirmation round trip: staff birth is
staff approval. The admin-created amenity is immediately part of the live
catalogue that properties can select.

**When a proposal is pending, the admin may REJECT or CONFIRM.** Reject ends
it: the amenity never becomes selectable, and the proposal's terminal state
is recorded (the proposer may propose again — a rejected amenity is not a
blacklist on the *idea*, it is a decision on *this row*). Confirm flips it to
**ACTIVE**.

**From then on, only the admin manages it.** This includes the original
proposer: the moment their proposal becomes ACTIVE, their edit rights
evaporate — ownership of an active amenity belongs to the platform, not to
whichever user happened to suggest it first. Only the admin edits its fields,
changes its status, disables or retires it. This is the specification's
deliberate asymmetry: proposing is a community act; the live catalogue is a
platform act.

### The cascade: a global amenity going down takes holdings with it

The specification calls it an unspoken rule, and it must be planned loudly
anyway: **when a global amenity goes down (disabled by the admin), all
properties holding that amenity go down as well.** Disabling an ACTIVE
amenity is therefore never a local act — it walks every property-amenity
holding that references it and takes those holdings down in the same
transaction. The property itself keeps its own status (a property is not
suspended because a pool was switched off), but its claim to that amenity is
withdrawn everywhere at once: it disappears from amenity-filtered searches,
its detail page stops offering it, and any completeness computation that
counted it now counts it as absent. One admin switch, estate-wide effect, no
property left behind claiming something the catalogue no longer grants.

**Re-enabling is the mirror question, and the plan answers it carefully.**
Holdings taken down by the cascade must be distinguishable from holdings the
*property owner* had individually disabled before the cascade — otherwise
re-enabling the global amenity would resurrect amenities owners had
deliberately switched off, overruling them with the admin's unrelated action.
The plan therefore distinguishes the two causes: a holding carries its own
enabled/disabled state for the owner's choice, and the cascade marks its
absence as *caused by the catalogue*. Re-enabling restores exactly the
cascade-suppressed holdings and leaves owner-disabled ones disabled. The
owner's will survives the platform's switch in both directions.

### Owner-side management of holdings (the other half of the amenity story)

Properties do not merely receive amenities; their owners **select from the
active global catalogue and set the details** — the value written against
holding depends on the amenity's data type (a boolean amenity gets true or
false, an integer amenity a count, a string amenity a phrase). The owner may
edit the value, and may disable or enable *their own holding* of any active
global amenity: a property can have a pool and still choose not to advertise
it this season. That owner-level disable is the personal decision the cascade
above must never overwrite in either direction. Selecting an amenity that is
not active — pending, rejected, cancelled, disabled, retired — is refused:
the catalogue a property picks from is the live one, filtered for selection
the same way rule lists are filtered.

### Strange cases — amenities

**Duplicate proposals.** Two users propose "Pool" independently: both rows
live in PENDING, both proposers may cancel their own. When the admin confirms
one, the second becomes a duplicate of an active amenity — confirming it is
refused with a pointer to the existing active row, and the admin rejects the
duplicate (the rejected proposer is not at fault; the rejection reason is the
duplication). Uniqueness is enforced among *active* rows by code, not among
proposals — proposals are allowed to collide, the catalogue is not.

**The proposer cancels while the admin is deciding.** Cancellation and
admin decision race: the plan takes first-writer-wins on the pending row —
cancel and confirm/reject both check the row is still pending inside the same
transaction; whichever lands first owns the outcome, the other finds a
non-pending row and fails cleanly. A cancelled proposal can never later be
confirmed; a confirmed proposal can never later be cancelled by its proposer
(only the admin can now move it).

**Data type is immutable after birth.** Changing a boolean amenity to a
string amenity would reinterpret every holding's stored value ("true" as a
string is not a boolean) — the type is fixed at creation, and a different
type means a different amenity.

**A disabled amenity and its holdings' values.** When the cascade takes
holdings down, their stored values are kept, not erased — re-enabling
restores the amenity *with* the value the owner had set, because the value
was never wrong, only unavailable.

**Retiring versus disabling.** Disable is reversible and triggers the
cascade; retirement (removing from the catalogue permanently) is refused
while any holding still references the amenity — retire first requires the
holdings gone, which in practice means disabled long enough for owners to
have migrated, or an explicit admin cascade-and-clear. The plan prefers
disable and treats retire as the rare, checked operation.

**Searching by a down amenity.** A search filter for an amenity that is
globally disabled returns nothing rather than erroring — the filter is
answered against active holdings only, so the cascade's effect on search is
automatic and needs no separate code path.

---

# Part 4 — The asset module: property, pricings, policy values

## 4.1 Property

### Who may create one: the KYC door

**Any authenticated, KYC-verified user may create a property.** Two
conditions, both checked: the caller is signed in at the required authority
level, and their identity has cleared verification. The verification answer
comes from the identity host — the endpoint registry already declares both
the current-user verification check and the check-by-identifier form, so the
gate reads the platform's own KYC truth rather than keeping a private copy of
it. The gate is checked at creation *and again at publication* — a user may
begin a draft while verified and finish it later, and publication is the
moment that matters most: nothing goes live under an identity that has since
lost verification. If verification lapses mid-flight, the draft survives
(save-first, below) but publication waits; existing live properties are not
retroactively hidden by a lapsed check — that is a moderation question, and
the staff path handles it.

### Save-first: the DRAFT that costs nothing

Creation is deliberately partial. A user may save a property with as little
as a title and have it exist in **DRAFT** status — incomplete, invisible to
the market, editable at leisure. Every subsequent act (describing, attaching
media, choosing a type and currency, setting pricings, selecting amenities,
picking rules, defining policy values) is an edit against the draft, each
versioned and logged. The draft state answers the specification's "they can
save first" exactly: nothing needs to be complete until the owner decides to
publish, and the completeness checks (a type, a currency, at least one
pricing, mandatory policy values where required) gate **publication**, not
saving. A half-built property is a legal object; a half-built property in
the market is not.

### Attachments

Properties hold attachments — the media that shows the place. They attach to
the property through the storage layer keyed by the property's entity and
reference, they follow the same detach-before-delete discipline as rule
attachments, and they belong to the property's current truth while each
logged edit records the attachment set for reconstitution. Deleting a
property detaches its media rather than orphaning it. Attachment ordering and
cover selection are properties of the storage layer's sequence, and whatever
order the owner sets is the order every viewer sees.

### The type, and the entity rule

The property's type is bound to the PROPERTY entity — the unspoken rule made
explicit. It answers "what kind of place is this" and nothing else; pricing
types, visit types and deposit kinds are separate namespaces entirely. The
type is chosen from the active catalogue (Part 1), may be a child of a root
kind (an apartment is a residential, a villa is a residential), and changing
it later is a normal versioned edit — with the caveat that a type change may
change which policy standards apply (policies are typed too), so a type edit
on a published property re-runs the completeness check before it lands.

### Currency and location: the two anchors

**Currency follows the property.** The property carries one currency, and
every amount anywhere beneath it — pricings, policy values, later deposits —
is expressed in that currency. A pricing never has its own currency; a policy
value never has its own currency; they inherit, always. This is also why
policy standards are state-scoped (below): different states mean different
countries mean different currencies, and the property's location resolves
which world it lives in.

**Location is an external reference** — a plain identifier pointing into the
logistics platform's location records, carried opaquely (notraius does not
parse or copy the address; it holds the pointer). What notraius *does* derive
is the state: the location resolves to a state identifier, and that state is
what matches the property against state-scoped policies. The chain is
property → location → state → policy, and it is the foundation of the Benin
versus Nigeria scenario.

### Amenities and rules on the property: selection, values, filters

The owner selects **active global amenities** and sets each holding's value
per the amenity's data type; edits, self-disables and self-enables of those
holdings are the owner's own domain (Part 3.3), and the global cascade
overrides them only in the catalogue's direction, never in the owner's.

The owner also selects rules for the property, and the pool is precisely
defined by the specification: **the rules he created himself, or public
rules** — his own PRIVATE and PUBLIC rules (he is their creator, so both are
his), plus every PUBLIC rule anyone else created. Private rules of other
users are unreachable — not filtered out after retrieval, but never queried
at all, so counts and search suggestions agree with details. The
specification's *he can filter* means the selection UI searches that pool by
text, visibility, creator and status; whatever filter is applied, the pool's
definition is invariant. Each selection is a row of its own — the property,
the rule, its own validity window (does this rule apply from now to then, or
permanently), its own version — so two properties holding the same rule hold
two independent selections, and one property may hold the same rule for
different windows without contradiction.

### Ownership and the parties

The property's owner is its user identifier, full stop. OwnershipGuard's
domain check for every property mutation is "is the caller this owner", with
the delegation fallbacks doing their estate-standard work: an enterprise
employee passes because the enterprise owns it; an app acting for its owner
passes within a live grant. Staff moderation reads everything. The visit
module's owner-settlement rule (Part 5) depends on this field being the
single truth of ownership — the money goes to whoever this field says, not to
whoever happened to be interacting with the visit.

### Lifecycle: DRAFT, published, paused

DRAFT is the save-first state. Publication — the completeness gate above —
moves it live: discoverable, viewable, bookable for visits. The owner may
unpublish (their own pause) and republish; staff may suspend for moderation
(a status the owner cannot lift alone). Unpublishing does not delete
anything: pricings, amenities, rules, values and attachments all persist,
and visits already requested keep their own lifecycle (Part 5 handles what
happens to a visit whose property went dark). Deletion is refused while a
live visit references the property — the same in-use discipline as rules —
and is otherwise a soft removal that detaches media and retires references
rather than breaking them.

### Strange cases — property

**Two owners' worth of edge.** A property created by a user who then loses
KYC stays published until moderation acts (never auto-deleted, never
silently hidden — visible decisions only). A property whose *type* is retired
after publication keeps its type reference and keeps publishing; the retire
only removes it from new selections.

**Currency change after pricing exists.** Changing a published property's
currency would reinterpret every stored amount (a number meaning francs now
meaning naira) — refused once any pricing or policy value exists. The path
is: unpublish, clear or migrate amounts deliberately, republish. The plan
never silently multiplies money.

**Location change after publication.** Allowed (the owner moved the listing
to a new address), but it re-resolves the state, which re-resolves which
policy standards apply — so a location edit re-runs the completeness check
and may surface a now-missing mandatory value before it lands. The old state's
values remain stored, marked as not-applicable-to-current-state rather than
deleted: if the owner moves back, they re-apply without retyping.

**Duplicate titles, empty descriptions, zero-attachment publications** — all
legal. The market's quality bar is the owner's business; the system's bar is
completeness of the gates above, not literary merit.

**Concurrent edits by owner and admin** (owner edits a title while staff
suspends): both are versioned log entries; last write wins on the row, and
the log keeps both truths in order. A suspension is never overwritten by a
title edit — status and content are separate concerns, and an edit cannot
lift a suspension it did not set.

## 4.2 Pricings

### What the owner decides

For their asset, the owner defines pricings: which **commercial types** the
property supports, each expressed as its own pricing row — the property's
supported pricing systems are *derived* from which types it has rows for: a
property with a nightly row supports nightly, a property with a monthly row
supports monthly, a property with both supports both, and **removing the last
row of a type withdraws that system** — support is never a separate flag that
can drift out of sync with reality; the rows are the reality. Each row
carries: the type, the **duration** that gives the type its physical meaning
(Part 2), the amount, its own version, and its own validity window — the
reusable from/to that says when this price applies, permanently when the end
is null.

### Money follows the property; time follows the duration

The amount is in the property's currency — inherited, never per-pricing,
never per-anything. The duration says how long one unit of the type lasts:
ten hours for a night, one calendar month for a month. The commercial type
says what the market calls it. Together: "NIGHTLY, ten-hour nights, 50,000
per night, from here to there" — and every downstream calculation
effectiveness, comparison, later deposit maths — reads those four facts and
never guesses.

### Validity windows and overlap

Two pricings of the **same type** with overlapping windows on one property
would make the price at a given instant ambiguous, so overlap within a type
is refused: at any moment, at most one live pricing per type answers the
question "what does a night cost now". Different types may overlap freely —
a monthly price and a nightly price coexisting is not a conflict, it is a
property that rents both ways. A window may be permanent (null end), may
start in the future (a scheduled price change), may end (a seasonal price
that has passed — it simply stops answering). Editing a window or an amount
is a version bump and a log entry, like everything; the previous version
remains reconstructible, so "what did a night cost last August" stays
answerable.

### Lifecycle and in-use discipline

A pricing may be created, edited, paused (its window closed or its row
disabled — the owner's choice) and removed. Removal is refused while a live
reference depends on it — within this scope, a policy value row points at a
pricing, so removing a pricing that has policy values attached either
refuses or takes the values with it; the plan refuses, because the values
are the property's deposit story and destroying them to delete a price is the
wrong trade. The out-of-scope lease module will add a second, stronger
in-use reference later; the discipline is already the same.

### Strange cases — pricings

**A pricing bound to a retired duration** keeps working exactly as written —
retirement blocks new bindings, never breaks existing ones.

**Zero-amount pricing** (a free promotional month) is legal; negative is not.

**A pricing whose window has expired** is history: it stops being offered,
keeps its row, keeps its version chain, and reappears if its window is
extended.

**The same type twice with touching windows** (one ends exactly where the
next begins) is *not* an overlap — windows are half-open in intent: the end
instant belongs to the next pricing, so a price change at midnight is
unambiguous.

**A property with no pricing at all** is a legal draft and an unpublishable
one — the completeness gate needs at least one row of at least one type, or
the property has no answer to "what does it cost".

## 4.3 Property-level policy values (where deposits get their standards)

### The two-tier structure the specification describes

There are policies (admin standards, Part 3.2) and there are **values**: the
owner picks an admin policy and sets *their* number against it, on a
specific pricing. The schema mirrors it exactly: a value row names a pricing,
names a policy, carries a number, its own status and its own version. The
specification's worked example is the anchor of the whole design: **the admin
sets ADVANCE with a minimum of 3 and a maximum of 6** (months); **the user,
on a pricing whose type is MONTHLY, sets the policy ADVANCE to 4** — meaning
this pricing asks for four months of advance. The standard is the admin's;
the number inside the standard's bounds is the owner's; the pricing is the
context that gives the number its unit (four *months* because the pricing is
monthly).

### Deposits are linked to the policy, and this scope sets the standard, not the money

The specification: deposits will be linked to the policy identifier, because
the policy is what defines the standards for deposits. Within this plan's
boundary, that link is *prepared*, not *exercised*: the value rows created
here are the standards-as-configured-per-pricing that the later deposit
module will read when it creates an actual deposit — it will resolve its
policy through this chain and take its bounds from the standard rather than
re-inventing them. The example deposit kinds are deliberately broad — not
just ADVANCE and SECURITY but **ELECTRICITY and such**: any money-kind
standard the admin can name becomes a policy, any of them can be given
bounds, and any of them can be valued per pricing. "What do we do for an
electricity deposit? We still set it" — the mechanism does not care which
kind it is; the admin's policy defines the standard, the owner's value
defines the instance.

### Units: how a number knows what it means

A value of 4 means four months because its policy is an ADVANCE standard and
its pricing is monthly; a value of 150,000 means an amount because its
policy is a SECURITY standard. The unit interpretation descends from the
policy's own type and code — the admin, who defines the standard, is the one
who has implicitly defined what counting means for it — and the pricing's
type supplies the multiplier for count-kind standards. The plan does not add
a unit column or a conversion table: the semantics live where the
specification put them, in the admin's definition, read consistently by the
deposit module later.

### State scoping: Benin versus Nigeria, and the bulk copy

This is why `state_id` exists on the policy and why properties chain through
their location to a state. **A policy for Benin can change and not be the
same as in Nigeria.** The resolution rule: a property's state is resolved
from its location; when the owner sets a value, the applicable standard is
the policy matching that state — falling back to a stateless (all-states)
policy when no state-specific one exists, so the admin can publish a global
default and override it per state where the world differs (currency,
regulation, market norms). A state-specific row *exists but is disabled*
treats as absent and falls through to the fallback — a paused local standard
must not silently become a stricter-than-nothing wall; if no standard
resolves at all, an optional policy simply has no bound and a mandatory one
blocks publication with a clear "no applicable standard" answer rather than
an unbounded number.

The **apply-to-all-states** operation is the specification's bulk gesture:
the admin takes one state's policy and *copies it to all other states,
overriding what is there*. It is deliberately destructive per the spec's own
word — copy-paste single-state policies to all others **overriding** — so
the plan treats it as a confirmed bulk act: the source state's set of policy
rows is duplicated per target state (same codes, same bounds, same mandatory
flags, target state stamped), existing rows with the same code in those
states are replaced, every replacement is logged per state, and after the
operation the states are identical copies that diverge again the moment the
admin edits any one of them. It is the right tool for "we have one policy,
roll it out everywhere" and a dangerous one for "roll out yesterday's Benin
policy everywhere" — which is why it confirms, previews what it would
overwrite, and logs every state it touched.

### The value-setting experience on a pricing

The owner, editing a pricing, sees the policies applicable to their
property's state and entity/type context: each with its bounds, its mandatory
flag, its description and documents, and — if already set on this pricing —
their current value. Setting or changing a value inside the bounds is a
versioned, logged act; setting one outside is refused with the bounds quoted;
leaving a mandatory one unset blocks publication; changing it later re-runs
the same checks. Values are per-pricing, so a property with a monthly and a
nightly pricing may carry four months' advance on the monthly and a different
figure on the nightly — the standards are shared, the instances are
contextual.

### Strange cases — policy values

**A policy disabled after values exist**: values persist, marked paused,
cannot be edited while paused, resume under re-enabling (with the
grandfather/review rules of Part 3.2).

**A policy made mandatory after values exist**: live values grandfather;
missing ones block *new* publication, flagged for completion on next edit.

**Changing a value's pricing's type** (monthly becomes weekly): the unit
context of count-kind values changes meaning under it — the plan refuses a
pricing type change while count-kind policy values are attached, because
"four months" silently becoming "four weeks" is exactly the kind of quiet
re-interpretation this design exists to prevent. The owner removes or edits
the values first, visibly.

**Two states, one property**: impossible by construction — one location
resolves to one state — but a property whose location fails to resolve (bad
external reference) has no state: it sees only stateless policies, and
state-specific standards simply do not apply to it until the location
resolves. The plan never guesses a state.

**The same policy valued twice on one pricing** is one row, not two: the
pair (pricing, policy) is the identity of a value, and re-setting it edits
the existing row (a new version, a new log entry) rather than accumulating
duplicates.

---

# Part 5 — The visits module: where money starts flowing

## 5.0 The frame: non-subscription, service-owned money, two parties and an owner

Visits are declared a **non-subscription module**. In guard terms this has a
precise meaning, not a vibes-based one: the subscription guard only ever
counts *initiating* operations, and visit requests and visit payments
categorically are initiating operations — so if a plan limit were ever bound
to the visit family, the guard would begin counting them and the "non-
subscription" promise would break silently. The promise is therefore kept by
construction: **no plan feature is ever bound to the visit activity family**,
so the subscription guard's third escape hatch (no bound feature means
unlimited) keeps visits free of plan quotas forever, regardless of which plan
a visitor is on. Rate limits, by contrast, remain available: the rate-limit
guard falls open only while no rule row exists, and adding a row for visit
operations is the admin's ordinary throttling tool if abuse appears. The
permission rows and authority expressions still apply — non-subscription does
not mean unguarded; it means the *quota* dimension is deliberately empty.

The money's ownership follows the PaymentOwnershipGuard doctrine: a visit
payment is created through pay's internal server-to-server API, stamped with
notraius's platform, and from that moment **notraius is its only driver** —
it settles it, refunds it, splits it, through internal routes; pay's generic
user-facing routes refuse it by design, with no admin override. The stamp is
also the proof-of-ownership link the specification points at: the payment
carries a platform identifier, and reading it back tells notraius that the
entity reference on this payment — **the identifier of the VISITS entity
row in the registry** — belongs to notraius's own namespace. The addressing
is therefore: entity id = the VISITS entity (proving *whose* namespace this
is), reference id = *this particular visit* (proving *which* record it pays
for), and the platform stamp is what lets notraius trust the first half
without asking anyone.

The parties are three, and the specification is adamant about which one money
ultimately belongs to: the **visitor** (the prospect who requests and pays),
the **agent or owner user** attached to the visit (the human counterpart who
accepts and plans it), and the **property owner** (the user id on the
property row — *not* the visit's user id, *not* whoever clicked confirm).
Settlement pays the property owner's account because they own the thing that
was visited; the visit's own user id is the operational counterpart, not the
beneficiary. Keeping those two identifiers distinct in every flow below is
the single most important data rule of this module.

## 5.1 Request and automatic payment

The visitor browses (a published property, its pricings, its visit pricing —
physical or virtual, with its amount and its plain minute-duration), decides,
and **requests a visit: paying automatically**. There is no "request then
invoice then chase" loop: the request *is* the payment event. One transaction
of business intent creates the visit row in its initial requested state and
the payment for it — the payment's amount is the visit pricing's amount in
the property's currency, its reference is the visit, its entity is VISITS,
its platform is notraius — and the money lands **held**, not released: the
escrow split that names the destination exists from birth, but nothing moves
to anyone until the visit completes or cancels.

The payment's deduplication key is (reference, currency) on pay's side and
the absence of an idempotency key on the internal route means **the
reference is the idempotency**: a request retried after a network hiccup
finds the existing payment for this visit and currency instead of charging
the visitor twice. The status-aware shape of that deduplication handles the
real strange case — a visit whose payment was refunded (cancellation,
below) and whose visitor *requests again*: the old, dead payment no longer
shadows a fresh charge, and the new request funds itself properly.

If the payment attempt fails, the visit does not half-exist: the request
rolls back (or is created in a clearly unpaid state that the sweep expires) —
a visit nobody paid for is never left waiting on an owner's desk as if it
were real. The owner sees nothing until the money is held.

### Strange cases — request

**Currency edge:** the visit pricing has no currency of its own; it inherits
the property's; a property whose currency changed is impossible (Part 4.1),
so the visit's currency is always well-defined.

**The property was unpublished between browsing and requesting**: the request
re-checks publication and refuses; a property that goes dark *after* the
request keeps its visit in the flow (the owner's side deals with it through
rescheduling or cancellation below — money is already held, and every exit
from here pays it out by some rule).

**A visit pricing deleted after being booked**: the visit keeps its
reference; deletion is in-use-refused anyway while the visit is live.

**Double-click by the visitor**: one visit row, one payment, both attempts
resolve to the same pair — the (reference, currency) dedupe is the answer.

**Owner never acts on a paid request**: a requested visit cannot hang
forever — the plan expires unaccepted requests after a window, refunding the
visitor in full (nobody performed, nobody keeps), and the expiry is a logged
state change like any other. The exact window is configuration, settled at
implementation; the *rule* is fixed here: silence never keeps money.

## 5.2 Acceptance: the owner stamps an identity and the visit becomes PLANNING

The owner (or the agent user on the visit) **confirms or cancels by adding
their user id** — the act of acceptance is literally the stamping of an
identity onto the visit — and on confirmation the status becomes
**PLANNING**. This is the specification's mechanic, and it carries a real
meaning: before the stamp, the visit is an unassigned paid request; the
stamp says *this human is now responsible for it*, and responsibility is what
enters the planning stage. Cancellation at this point — before acceptance —
is the owner declining the request, and since the owner never engaged, the
visitor's money returns **in full**: the 20% retention only exists (below)
once the visit has been confirmed and committed to. The refund is driven
through notraius's internal door, the payment being notraius's own to move.

Once stamped, PLANNING is where the two counterparties **edit the time of
the visit until they are both ready** — and the specification gives the turn
rule with full precision, because it is a logs-driven rule:

**When A edited it last — and we know through the logs — B can confirm or
edit it again. Vice versa.** The logs are the referee: each time proposal is
edited, the entry records who changed what, so at any moment the system
knows whose edit is *current* and whose *move* it is. The holder of the move
(the counterpart of the last editor) has two legal actions: **confirm the
current time** (accepting the other's proposal) or **edit it again**
(countering with their own). The last editor has no move — they proposed,
they wait; they cannot confirm their own proposal, because confirmation by
the proposer is not agreement, it is self-echo. This creates the alternating
rhythm the specification describes: propose, counter, counter, confirm —
each edit flips the move, each confirmation ends the stage. When the
counterpart confirms, the status becomes **CONFIRMED**.

Why logs and not a "pending edit" field: the specification wants the history
itself to be the source — who edited, when, what the time was before and
after — so the turn state is *derived* from the last edit entry rather than
stored as a flag that could disagree with the history. The derivation is
simple and exact: the last logged edit names the last editor; the move
belongs to everyone else (the counterpart). If both edit "simultaneously",
the log order — transaction order — decides who was last, and the loser of
the race simply holds the move; no edit is lost, both are in the history,
and the turn rule continues from whichever committed second.

### Strange cases — PLANNING

**The counterpart never moves**: the other party holds the move and vanishes.
Planning cannot stall forever — the same expiry philosophy as unaccepted
requests applies with a longer window, and expiry of a PLANNING visit
returns the visitor's money in full (the visit never got confirmed; nothing
was committed to). Silence never keeps money is the module's second law.

**Edits are always available to the move-holder only**: an edit attempt by
the last editor is refused with the turn state in the error — not
mysteriously rejected, but explained ("you edited last; they must confirm or
counter"), because a turn-based flow whose users do not understand the turn
degenerates into support tickets.

**Rescheduling reuses this stage** (below): a confirmed visit that needs a
new time re-enters PLANNING with the same turn rule, the same logs, the same
referee — one mechanic, two entries.

**The owner stamps a different user id on re-acceptance** (the agent hands
the visit to a colleague): allowed before planning edits begin; the stamp is
the responsibility record, and responsibility can transfer while the visit is
still unconfirmed. After CONFIRMED, the counterpart is fixed — changing who
plans a confirmed visit is a reschedule-shaped act, not a silent edit.

## 5.3 CONFIRMED, the visit itself, and mutual assessment

CONFIRMED means the time is agreed and the visit will happen (physically or
virtually). After the visit, the specification's sequence is strict and
backwards-and-forwards by nature: **each party assesses the other**, and
**both must have assessed before either can confirm the visit** — "to
confirm visits, they must absolutely assess each other first, then they click
on the confirm-visit button, and things get settled."

The assessments are mutual and cross-wired: the visitor assesses the
property/owner side, the owner side assesses the visitor. Each assessment is
its own record, each is submitted once (re-submission before the other side
acts is an edit of one's own assessment, versioned and logged; after the
visit is confirmed it is frozen — a confirmed visit's assessments are
history). The **confirm-visit button is gated on both**: one assessment
without the other means the button does not exist for either party — the
gate is symmetric, so neither side can rush the other by confirming early.

When both assessments exist and someone presses confirm, the visit completes
and **the money moves from escrow to the owner of the property — not the
user id on the visit, the owner's account on the property**. One settle, one
destination, the full held amount: the split that was declared at birth is
released to the property owner's user. The visit's status becomes completed,
the payment becomes settled, and — because pay never allows a refund after
settle — this is a one-way door: everything below (cancellation) is
therefore only thinkable *before* this moment, which the state machine
enforces by making cancel impossible once settled.

The assessment-then-confirm ordering is what makes the escrow honest: the
money cannot be released while either party still owes their judgment, so
neither side can skip their half and take the money — and neither side can
be *forced* to judge before the other has, because the button waits for both.

### Strange cases — assessment and settlement

**One party never assesses**: the visit sits CONFIRMED with money held and
an incomplete pair — the specification's answer is the support ticket (below)
and the admin's decision; the plan adds no silent auto-confirm, because
auto-settling without a missing assessment would break the very guarantee the
gating exists to provide. The window-and-force question (does an assessment
ever expire into admin-forced completion?) is explicitly a decision point
for the operator — the plan's default is: no automatic force; disputes are
human decisions, matching "admin decides".

**The property changed owner between request and settlement**: settlement
pays the owner *as of the settle moment* — the property's current owner id —
because the specification's rule is about the property's ownership, not a
snapshot frozen at request time. If the operator prefers the request-time
owner (the person who actually showed the place), that is a one-line
decision-point note; the plan defaults to current-owner for the same reason
the rule exists at all: the property is what was paid for.

**The visit's counterpart (user id) is not the owner**: settlement ignores
them entirely — no share, no fee, no routing. Their compensation, if any, is
arranged outside this module, exactly as the specification insists ("not the
user id but the owner of the property account").

**Virtual versus physical** changes nothing in money or assessment — the
pricing type says how the visit happens; the lifecycle is identical.

**Assessments feed the platform's reputation host** — the endpoint registry
already declares the assessment routes — so a visit's mutual judging is also
the raw material of each party's standing; a contested assessment is
therefore also a reputation question, which is another reason disputes go to
the admin rather than being auto-resolved.

## 5.4 Reschedule and cancel: the 24-hour rule and the 20% retention

The specification's final scenario — things going awry — has two named
instruments and one money rule.

**Reschedule management** exists inside CONFIRMED: while the visit is still
far enough away, either party may request a new time, and the visit
re-enters the PLANNING stage with the identical turn rule (last editor's
counterpart confirms or counters) — confirmed visits do not get a second,
simpler editing mechanic; they get the same referee. A reschedule that
completes returns the visit to CONFIRMED with the new time, logged as
another version of the agreement.

**Reschedule is the clean path, and the plan guards that cleanliness
explicitly.** A reschedule moves no money (the payment stays held, untouched,
no split, no refund, no fee), files no assessment (neither party gains nor
loses reputation for merely changing the time), and carries no penalty of any
kind — it is the penalty-free alternative the specification insists on
alongside the penalties below ("still they can reschedule"). Everything
punitive in this section attaches to *cancellation* only; the moment a flow
charges money or reputation for a mere time change, the module has broken its
own contract. The turn rule itself is the reschedule's only gate: both
parties must agree to the new time through the confirm cycle, and that
mutual agreement is all the discipline a reschedule needs.

**Cancellation inside CONFIRMED, before the visit is 24 hours away.** The
specification: when already in CONFIRMED status and 24 hours before the
visit, they can cancel — and upon cancelling, they get their money back
**but 20% of the amount goes to the property owner**. The reading the plan
takes (and flags for confirmation, since the phrasing admits both directions):
cancellation is available **while the visit is still at least 24 hours in
the future** — the standard "free cancellation until one day before" shape —
and once inside the final 24 hours the self-serve door closes: too close to
the visit for a replacement booking to be plausible, so the visit proceeds
or goes to dispute (the support ticket, below). The exact boundary instant
compares the visit's planned start against now, in absolute time, so "24
hours before" is a moment on the calendar, not a business-day approximation;
a visit exactly 24 hours away is still cancellable (at-least, not
inside-than).

**The money split on cancellation**: the held payment is settled with two
destinations — **80% back to the visitor, 20% to the property owner** — the
visitor "gets their money back" as the overwhelming majority, and the owner
keeps a fifth as compensation for a slot they can no longer fill at the last
responsible moment. Both legs are declared in one settle (one credit per
beneficiary, exact sum, held money moving — all of pay's split facts
honoured), and because this settle *is* the terminal act, the payment
becomes settled and the no-refund-after-settle rule is never approached.
The 20% is a **fixed share of the visit fee**, not a sliding scale, not a
tiered penalty — the specification gives one number and the plan keeps it.

**The reputation penalty travels with the money penalty.** The operator's
rule is explicit and two-directional: **a cancellation of a CONFIRMED visit
files an assessment of 1 on every axis against the cancelling party**, in
addition to whatever the money does. When the **visitor** cancels after
CONFIRMED, they lose 20% of the fee *and* take an all-axes-1 assessment —
money and standing, both. When the **user-id side** (the owner's
counterpart on the visit) cancels, they take the all-axes-1 assessment —
the bad review is the price of breaking a confirmed commitment from their
side — while the money rules of this section (the 80/20 split, or the
full-refund owner-fault cases below) continue to apply unchanged. The
assessment is **filed automatically inside the cancellation transaction**,
on behalf of the non-cancelling party, against the cancelling party: the
visit never reaches the mutual-assessment stage of §5.3, so there is no
button for the surviving party to press and no way for the penalty to be
forgotten — a cancel *is* the judgment. It is a normal assessment record
against the reputation host (the registry already declares the assessment
routes), score 1 across every axis, attributed to the counterparty, and it
counts toward the cancelling party's standing exactly like any other
assessment. Nothing gates on it: it is a consequence, not a step — the
cancel completes whether or not anyone later disputes the fairness of the
review, and disputes about *that are* what the support ticket (§5.5) is
for.

**Reschedule, once more, because the contrast is the point**: none of the
above — no 20%, no all-axes-1, no money movement — attaches to a reschedule.
The two paths out of a confirmed-but-unhappy situation are deliberately
priced worlds apart: change the time (free, mutual, clean) or break the
commitment (paid, one-sided, judged). The penalty exists to push the parties
toward the first door, which is exactly why it must never leak into it.

**Who may cancel**: the specification's "they" covers the visitor (the payer
— the natural reading of "they get their money back"). The plan extends it
symmetrically — **either party may cancel before the 24-hour line, with the
same 80/20 split** — because a symmetric door is simpler to reason about and
the penalty's purpose (compensating the owner for a lost slot) holds whether
the slot was lost by the visitor's plans or the owner's. On the *reputation*
side the symmetry is no longer the plan's guess: the operator has ruled that
**both directions carry the all-axes-1 assessment** — visitor cancels,
visitor is judged; user-id cancels, user-id is judged. What remains open for
review is only the *money* symmetry: the operator may prefer visitor-only
cancellation with an owner-initiated cancel refunding the visitor in full
(no money penalty for the owner's own breach — though the assessment still
lands, since the operator attached it to the act of cancelling, not to who
pays). The plan's default is the symmetric 80/20; the flag is here so the
review can settle it in one sentence.

**Cancellation before CONFIRMED** (requested, or in planning) is a different
door with different money: no commitment has been made, no slot has been
held in earnest — the visitor is refunded **in full**, and the owner keeps
nothing. The 20% exists to price *confirmed* commitments; pricing a
cancelled negotiation would tax people for merely talking.

**Owner-initiated full-refund cases**: the owner unpublishes, suspends, or
simply cannot host — the visitor's money returns in full regardless of the
24-hour line when *the owner* is the reason the visit cannot happen: the
money penalty exists to compensate owners for visitor-side cancellations,
never to reward an owner for cancelling their own booking. A property that
goes dark with visits inside the final 24 hours therefore settles to the
visitor 100%. The reputation penalty, however, still follows a *voluntary*
owner-side cancellation of a confirmed visit — the owner's counterpart eats
the all-axes-1 review for breaking the commitment, exactly as the operator
specified for the user-id direction. The one exception the plan draws: a
cancellation forced by **staff action** (admin suspension of the property)
is not the owner's cancel — nobody is judged for a platform decision — so
no assessment is filed against anyone when staff shutdown is the trigger;
the money still returns to the visitor in full, because the visit cannot
happen and silence never keeps money.

### Strange cases — cancel and reschedule

**Exactly-24-hours**: at-least semantics as stated — cancellable at exactly
24 hours out, not one second inside.

**Reschedule then cancel**: the cancellation clock measures the *current*
confirmed time; rescheduling closer to now tightens it (a reschedule inside
24 hours, if the operator allows it at all, requires both parties' agreement
precisely because it moves the visit past the cancellation line for both —
the turn rule's confirmation *is* that mutual agreement, so a reschedule
inside the line is legal only through the full confirm cycle, never as a
unilateral edit).

**A cancelled visit's assessments**: none exist yet (assessment follows the
visit); if a visit was cancelled after assessments began — impossible in the
normal order, but defensively: cancellation is refused once both assessments
exist and the confirm button is live, because at that point the visit is one
click from completion and the money's destination is already decided.

**Cancel racing confirm**: first transaction wins; the state check inside
the transaction decides — a settle that has begun makes the cancel find a
settled payment and refuse; a cancel that has begun makes confirm find a
cancelled visit and refuse. No split-brain, no half-settled money.

**The 20% leg's arithmetic**: 20% of the amount, rounded to the currency's
precision with the visitor's leg absorbing the rounding remainder so the two
legs always sum to exactly the held amount — a penny's difference must never
make a settle fail.

**Who receives the 20% when the property changed hands**: the current owner,
same rule as final settlement — the property's owner id is the constant the
module pays toward.

## 5.5 Disputes: support tickets and the admin's decision

"Things going awry — they just create a support ticket. Admin decides." The
module does not invent a dispute engine; it uses the estate's existing
support-ticket machinery, whose ownership semantics are already correct for
this use: a ticket belongs to its creator, staff see everything, and the
ticket's entity binding ties it to the visit record so the admin reading the
dispute sees exactly which visit, which payment, which assessments, which
turn-history it concerns. Either party may open a ticket on a visit;
the admin's decision is exercised through the ordinary doors available at
the time — a not-yet-settled visit's money can be settled to one side or
refunded by admin action through the internal payment routes (notraius's own
door, since the payment is notraius's own); a settled visit's money is
already terminal (no refund after settle), so a post-settlement dispute is
resolved as a platform-level decision — compensation outside this payment,
recorded against the ticket — rather than by reversing pay's ledger, which
does not allow it.

Tickets inherit the whole estate's discipline around them: the creator and
assignee are parties (OwnershipGuard's domain check), staff are above it,
rate limits apply to ticket creation like any initiating operation, and the
ticket's own lifecycle (open, answered, resolved) is the shared module's
concern, not this plan's.

### Strange cases — disputes

**Both parties open tickets on the same visit**: two tickets, one visit —
fine; the admin sees both, and nothing about the visit's state machine
depends on ticket count.

**A ticket opened mid-cancellation**: the cancellation transaction proceeds
independently (tickets never freeze state machines); if the admin later rules
against the cancellation's outcome, the remedy is a new money movement, not
an undo — money history is append-only.

**Anonymous or third-party tickets about a visit**: refused — a visit ticket
requires being a party to the visit (visitor, counterpart, property owner)
or staff, enforced by the same ownership check the ticket system already
implements.

---

# Part 6 — Cross-cutting laws (the sentences every module obeys)

**Versioning is always the same act.** A number on the row, a before-and-after
entry in the logs, written in one transaction, replayable forever. Rules,
policies, amenity records, property selections, pricings, policy values,
visit times, assessments — none of them remember history themselves; all of
them are reconstructible from the log chain, by version, exactly as the
specification demands for rules and as the schema's general rules demand for
everything.

**Ownership is one field.** The owner id on a property decides money
destination, edit rights and delegation chains; the visit's counterpart id
decides planning responsibility and nothing about money; the visitor id
decides who pays and who is refunded. Three ids, three meanings, never
substituted for one another.

**Guards are not re-implemented.** Authority comes from the permission rows
and the endpoint's five-layer expression; delegation from consents and seats;
quotas from the deliberately-empty visit binding; throttling from rate-rule
rows; record access from OwnershipGuard subclasses; money doors from the
platform stamp. Each module above adds business behaviour on top of those and
assumes none of them away.

**Fail closed, decide loudly.** Every ambiguous gate in this plan resolves to
a refusal with a stated reason, never to a silent allow — the estate's
guard doctrine (no rows means denied, no grant means denied, undecidable
means closed) extended into business rules: no applicable standard blocks
publication rather than bounding nothing; a missing assessment blocks settle
rather than defaulting; an unowned payment is nobody's payment.

**Money is append-only and terminal.** Held until an explicit act; moved by
splits that sum exactly, one credit per destination; refundable only until
settled; every movement preceded by the state change that authorized it in
the same breath. The visit module's four money outcomes — full refund
(pre-commitment or owner fault), 80/20 split (confirmed cancellation), full
owner payout (completed visit), admin-directed payout (dispute) — are the
only ones that exist, and each names its trigger explicitly.

**In-use means untouchable.** A rule in use cannot be disabled; an amenity
in use cannot be retired; a type or duration in use cannot be deleted; a
pricing with values cannot be removed; a property with a live visit cannot
be deleted; a settled payment cannot be refunded. The same discipline, six
times, always resolving to *retire rather than destroy, flag rather than
rewrite*.

**Ambiguities flagged for the operator, defaults chosen and visible.**
(1) The 24-hour cancellation window's direction — default: cancellable while
at least 24 hours remain. (2) Symmetry of cancellation — default: either
party may cancel pre-24h with the same 80/20 split; the alternative (owner
cancel refunds visitor in full) is one sentence away. (3) Assessment deadlock
— default: no auto-force, disputes are admin decisions. (4) Owner at
settlement — default: the property's current owner at settle time. (5) The
amenity cascade's literal reading — default: holdings go down with the
global amenity (and come back on re-enable unless the owner had disabled
them personally); the property's own status is untouched by the cascade
unless the operator means properties themselves to go down, which is a
stronger rule and needs one confirmation. Each of these is written where it
bites, restated here so review can find them in one place.

---

*End of plan. Scope: types → durations → rules → policies → amenities →
property → pricings → property policy values → visits. Everything after
visits — leases, deposits, breakdowns, conditions, repairs, settlements,
notices — is the next plan's subject, and picks up from the deposit-standard
chain this one deliberately left at "standards set, money not yet moved".*
