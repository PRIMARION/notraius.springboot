# springboot.notraius

Notraius microservice for the Primarion estate. Built on the shared
`springboot.shared` library and Spring Boot 4.1 (Java 25). Runs on port **8090**.

Notraius is a *service* microservice — it has no `user`/`role`/`session` tables
of its own. Authenticated callers are resolved from signed bearer tokens issued
by the primarion microservice (verified by primarion introspection).

## What it is now

Notraius is born **empty by design** — a fully provisioned, doctrine-complete
host that copies the entire shared estate and is ready for its modules:

- The **complete shared module surface** (see `NotraiusApplication`
  `@ImportAutoConfiguration`): common config, Redis, storage, crypto, security
  auth + rate limiting, utils, users, services, database, identity apps,
  access, preferences, tokens, chat, lookups, flowmap, communication, the
  event action engine, subscription, webhooks, anomaly, idempotency, tracing,
  export encoders, log partitioning and ops.
- The **full DB bootstrap chain** (`db/migration` V1..V24 mirroring the
  logistic estate minus the logistic/courier domain): the shared schemas
  (access, app, communication, event, security, subscription, + the V8
  subfeature alignment into chat / support / webhook / storage / rate), the
  notraius host schema (entities / statuses / validity / attachments
  registries), audit-log partitioning, rate limits and the permission sweeps.
- **Service-identity keys are per-service** (#AUTH-CENTRAL doctrine):
  `APP_PUBLIC_KEY`/`APP_PRIVATE_KEY` (fresh RSA-2048 pair) and `HMAC_KEY` in
  `.env` are notraius-unique; they must never match another service's.

## Ownership rule

The notraius database holds every row owned by the notraius microservice
plus the shared-module rows that ride each consuming host (chat, support,
storage, webhook rows). Foreign-domain data (identity, geo, media catalog)
lives in the shared lookup database — accessed via the shared lookup
datasource (`app.shared.datasource`), never copied.

## Running locally

```bash
# postgres + redis must be up (see the estate compose file)
./gradlew bootRun          # loads .env, migrates via Flyway, serves :8090
./gradlew test             # test fixture
curl localhost:8090/actuator/health
```

`.env.example` documents every variable. `.env` carries the dev values
(localhost DB/Redis, dev secrets, port 8090).

## Structure

```
src/main/java/com/notraius/
├── NotraiusApplication.java      # bootstrap + shared @ImportAutoConfiguration
├── config/                       # JPA / Flyway / properties
│   ├── NotraiusJpaConfig.java
│   ├── NotraiusFlywayConfig.java
│   ├── properties/               # NotraiusProperties, InterServiceProperties
│   └── web/                      # SecurityConfig, guards
├── database/                     # host reference entities + repositories
│   ├── entities/reference/       # Entities/Status/Validity/CommunicationRole
│   └── repositories/reference/
└── modules/                      # the notraius domain (grows here)
```

## Naming

Package `com.notraius`, DB `notraius`, schema `notraius`, app
`springboot.notraius`, code `NOTRAIUS`, seeder vocabulary mirrors the shared
estate post-V24 with the logistic-domain entries removed.
