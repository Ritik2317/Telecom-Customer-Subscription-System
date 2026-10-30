# TCSMS — Customer & Subscription Management

The default application now implements the supplied **Customer & Subscription
Management** case study: customer/admin login, onboarding, plans, SIMs,
subscriptions, usage, bills, confirmed payments, complaints and reports.

Start with the [current setup and architecture guide](docs/SETUP.md) and
[requirements, gap analysis and verification report](docs/IMPLEMENTATION.md).
Copy `tcsms.properties.example` to ignored `tcsms.properties`, configure **your
own** MySQL account, then run:

```powershell
mvn test
mvn package
java -jar target/tsatms-jar-with-dependencies.jar --init
java -jar target/tsatms-jar-with-dependencies.jar --create-admin
java -jar target/tsatms-jar-with-dependencies.jar
```

No default credentials or production demonstration records are installed.

---

# Legacy TSATMS — Telecom Service Assurance & Trouble Ticket Management System

**The retained documentation below describes the original, separate OSS product.
Its commands now require `--legacy` before any flags**, for example
`java -jar target/tsatms-jar-with-dependencies.jar --legacy --help`.
Do not run legacy database setup on a database containing data you need: its
original schema scripts drop/recreate legacy tables. The new BSS `--init` uses
separate additive tables and never invokes those scripts.

A console-based telecom OSS for incident, fault, SLA and support management,
built to the *Preboarding Batch* case study. Java 25 LTS, MySQL, Maven, no
frontend.

One incident is captured, prioritised against an SLA, assigned to the right
engineer, escalated when it drifts, resolved, closed and rated — with a
complete audit trail and a set of reports over the whole operation.

---

## 1. What you need

| | Version used | Notes |
|---|---|---|
| JDK | 25 LTS | The project compiles with `--release 25`; use JDK 25 or newer. |
| Maven | 3.9.11 | Tested build version; the build works offline once the connector is cached. |
| MySQL | 8.0 | Running on `localhost:3306`. |

Only one external dependency: `mysql-connector-java` 8.0.30. There is no
test framework and no logging library — verification is built in, and
logging goes through `java.util.logging`.

---

## 2. Getting it running

### Step 1 — tell it about your database

Everything configurable lives in `src/main/resources/application.properties`.
The three password values ship blank on purpose — real ones are kept out of
version control. Fill them in before the first run:

| Property | What it is |
|---|---|
| `db.admin.username` / `db.admin.password` | A MySQL account that may `CREATE DATABASE` and `CREATE USER`. Used **only** by `--setup-db`. |
| `db.username` / `db.password` | The account the application itself uses. `--setup-db` creates it. |
| `security.demo.password` | The password given to every seeded account. Must satisfy the policy: 8+ characters with an upper case letter, a lower case letter, a digit and a symbol. |

### Step 2 — build

```bash
mvn -DskipTests package
```

This produces `target/tsatms-jar-with-dependencies.jar`, which runs with a
plain `java -jar` and no classpath wiring.

The build picks up `maven-settings.xml` from the project root automatically,
because `.mvn/maven.config` passes `-s maven-settings.xml`. That file
resolves against Maven Central and uses its own local repository at
`~/.m2/repository-tsatms`. Your machine-wide `~/.m2/settings.xml` is not
read and not modified — on a corporate machine it often mirrors everything
to an internal host, which would fail here, and other work depends on it.
Add `-o` to build offline once the connector is cached.

### Step 3 — create the database

```bash
java -jar target/tsatms-jar-with-dependencies.jar --setup-db
```

Runs the first seven scripts in `src/main/resources/db` in order: the
bootstrap (database and application account), the schema, functions,
triggers, views, procedures and seed data. The eighth,
`07_sample_queries.sql`, is a reference library and is not executed.

### Step 4 — give the accounts a password

```bash
java -jar target/tsatms-jar-with-dependencies.jar --provision-users
```

The seed script writes a sentinel into `password_hash`, because SQL cannot
produce a PBKDF2 hash. This replaces the sentinels with real salted hashes
and activates the accounts. Each account gets its own salt, so the shared
demo password still produces a different stored hash in every row.

### Step 5 — run it

```bash
java -jar target/tsatms-jar-with-dependencies.jar
```

Startup checks, then the sign-in screen.

---

## 3. Every command

`--help` prints this list.

**Setting up**

| Switch | What it does |
|---|---|
| `--setup-db` | Build the schema, logic and seed data from scratch. |
| `--refresh-db-logic` | Reload only the functions, views, procedures and triggers. |
| `--provision-users` | Give the seeded accounts their password. |
| `--check` | Run the startup checks and stop. |

**Using it**

| Switch | What it does |
|---|---|
| *(none)* | Startup checks, then the sign-in screen. |
| `--login` | The sign-in screen without the checks. |
| `--demo` | Walk one incident through the whole system, then roll it back. |
| `--sla` | Print the SLA board. |
| `--reports` | Build all seven reports and export them to CSV and text. |

**Proving it works** — eleven harnesses, 1,470 assertions, all against the
live database.

| Switch | Covers | Checks |
|---|---|---|
| `--verify-db` | All 7 views, all 5 functions, 3 procedures, 2 triggers | prints results |
| `--verify-dao` | The data access layer | 82 |
| `--verify-security` | CAPTCHA, hashing, OTP, lockout, role-based access | 154 |
| `--verify-sla` | The SLA engine and its clocks | 156 |
| `--verify-ticket` | The ticket lifecycle, and `sp_resolve_ticket` | 186 |
| `--verify-events` | Notifications and the audit trail | 130 |
| `--verify-assign` | Engineer recommendation and assignment | 133 |
| `--verify-escalation` | The escalation ladder | 164 |
| `--verify-threads` | The background workers | 154 |
| `--verify-reports` | Stream analytics and the reports | 190 |
| `--verify-console` | The dashboards, driven from scripted input | 121 |

Every harness that writes does so inside a transaction rolled back to a
savepoint, so they can be run repeatedly and in any order. `SEVERE` lines in
the events, threads and console output are deliberate failure injections —
the harness proving a failure is contained.

---

## 4. Start with `--demo`

The fastest way to see the whole system is:

```bash
java -jar target/tsatms-jar-with-dependencies.jar --demo
```

It follows one incident end to end in eighteen scenes, each headed with the
case-study section it comes from — `4. Who should take it   [section 7]` —
so the output doubles as a guided tour of the document:

- The service desk signs in, and what that role may do *(section 2)*
- A `LINK_DOWN` alarm arrives and opens a ticket with nobody involved *(11)*
- The ticket, laid out as the card in section 5 *(4, 5, 6)*
- The best engineers for it, ranked by the five criteria *(7, 16)*
- Assignment, and every row the single commit wrote *(19)*
- What the SLA says, and how long is left *(8)*
- Escalation up the ladder *(9)*
- The engineer signs in, diagnoses and resolves *(2, 10)*
- The service desk closes it *(14)*
- The customer signs in, sees their ticket and rates it *(2, 13)*
- The complete status trail and the audit rows *(10)*
- The manager signs in for the notifications, the operations summary, the
  analytics and a report *(2, 12, 15, 16, 18)*

Four real logins, each through the real CAPTCHA, password and OTP — no
shortcuts and no test doubles. The whole thing runs inside one transaction
that is rolled back at the end, so it can be run as often as you like and
always tells the same story.

---

## 5. How it is put together

```
com.amdocs.telecom
├── controller    the four dashboards, the menu framework, the sign in screen
├── service       business operations, one interface per area
│   ├── impl          the implementations
│   ├── analytics     the Stream API analyses of section 16
│   ├── assignment    engineer matching
│   ├── escalation    the ladder and its policy
│   ├── event         network alarms and ticket events
│   └── sla           SLA evaluation and clocks
├── dao           one interface per table, plus the transaction template
│   └── impl          JDBC implementations
├── model         the entities of section 20, and their enums
├── dto           read models for screens and reports
├── exception     one hierarchy under TSATMSException
├── validation    field rules and the lifecycle state machine
├── security      CAPTCHA, hashing, OTP, sessions, permissions
├── scheduler     the background workers of section 17
├── report        the report model, writers and exporter
├── util          configuration, logging, console, bootstrapping
└── main          the entry point and the demonstration
```

Every operation that changes something goes through a service, and every
service method that changes something checks the caller's permission first —
because who may do a thing is part of the operation, not something a caller
can be trusted to remember. A DAO never knows a `UserSession` exists.

Read-only view projections are the one deliberate exception: a dashboard
listing `vw_open_tickets` calls `ReportDAO` directly rather than through a
service that would only delegate. Those menu options are permission-guarded
at the menu, so the check still happens; what is skipped is a layer with no
rule in it.

### Design patterns

The case study asks for at least three. Six are used, each because it
solved a problem rather than to fill the list:

| Pattern | Where | Why |
|---|---|---|
| **Singleton** | `ConfigLoader`, `DBConnection`, `DAOFactory` | One configuration, one pool, one set of DAOs. |
| **DAO** | `dao` and `dao.impl` | Every SQL statement is behind an interface, so the service layer holds no JDBC. |
| **Factory** | `DAOFactory`, `ReportFormat.newWriter()` | The caller asks for the kind, not the class. |
| **Strategy** | `SlaClock`, `ReportWriter` | Business-hours vs continuous SLA clocks, and CSV vs text output, differ only in the algorithm. |
| **Observer** | `TicketEventPublisher` and its listeners | Notifications and the audit trail both react to the same events without the ticket service knowing either exists. |
| **Template Method** | `Dashboard.open()`, `BackgroundWorker` | The sequence is fixed once; subclasses supply only what differs. |

### The transaction rule

Every mutation that touches more than one table runs through
`TransactionTemplate`. It holds the connection in a `ThreadLocal`, so a
nested call joins the transaction already running rather than opening a
second one, and only the outermost block commits.

Section 19's assignment is the worked example: validate the ticket, validate
the engineer, check availability, assign, update the ticket, write the
status history, write the notification, write the audit record, commit. Any
one of those failing rolls back all of them, so a ticket can never show an
engineer who was never told about it.

---

## 6. The demonstration data

`06_seed_data.sql` loads:

- **4 SLA bands** exactly as tabulated in section 8
- **17 login accounts** — 2 service desk, 1 manager, 6 engineers, 8 customers
- **6 engineers**, the first four being section 7's worked example
- **8 customers** across all three types, in five regions, with **14 services**
- **15 tickets** covering all eight statuses and all four priorities, nine of
  the ten incident categories, both met and breached SLAs, and one raised
  automatically from an alarm. `TT-2026-004521` is section 5's sample ticket,
  field for field.
- **Network events** including section 11's `NE-884521` on `MUM-RAN-045`
- Status history, escalation history and feedback for the tickets that have
  them

Ticket timestamps are relative to `NOW()`, so the SLA states stay realistic
whenever the seed is loaded. `sla_deadline` is deliberately left out of the
insert so the `BEFORE INSERT` trigger derives it — which also proves the
trigger works.

Notifications, audit rows and login history are not seeded. They are
consequences of things happening, and the system writes them itself — a
seeded audit trail would be a fiction, and would hide whether the real one
works.

Two gaps, stated rather than hidden: no seeded ticket uses the `OTHER`
category, and four of the eight resolution codes (`SOFTWARE_FAILURE`,
`FIBER_CUT`, `POWER_FAILURE`, `UNKNOWN`) do not appear in seeded rows. All
of them are implemented, selectable in the console and exercised by the
harnesses; they simply are not pre-loaded. `--demo` resolves its ticket with
`FIBER_CUT`.

---

## 7. The database

Thirteen tables — `users`, `customers`, `telecom_services`,
`network_engineers`, `sla_configuration`, `trouble_tickets`,
`ticket_status_history`, `escalation_history`, `network_events`,
`notifications`, `feedback`, `audit_log`, `login_history` — normalised, with
primary keys, foreign keys, unique constraints, `NOT NULL` where a value is
required, check constraints, status columns, created/updated timestamps and
indexes on the columns actually filtered on.

Beyond the tables:

| | |
|---|---|
| **7 views** | `vw_ticket_details`, `vw_open_tickets`, `vw_engineer_workload`, `vw_sla_compliance`, `vw_category_incidents`, `vw_customer_repeat_incidents`, `vw_manager_dashboard` |
| **5 functions** | `fn_sla_deadline`, `fn_sla_response_deadline`, `fn_sla_status`, `fn_resolution_hours`, `fn_minutes_remaining` |
| **6 procedures** | `sp_assign_engineer`, `sp_escalate_ticket`, `sp_resolve_ticket`, `sp_recommend_engineers`, `sp_ticket_volume_report` — all five called from Java through `ReportDAO` — and `sp_manager_dashboard`, exercised by `--verify-db` |
| **4 triggers** | `trg_tickets_before_insert` derives the SLA deadlines; `trg_tickets_before_update` stamps resolution and closure times; `trg_engineers_after_update` keeps the workload count honest; `trg_notifications_before_update` stamps the send time |

`07_sample_queries.sql` is a library of the SQL the common requirement asks
to be demonstrated: joins, left joins, `GROUP BY`/`HAVING`, subqueries,
`CASE`, aggregates and date functions, each with a comment explaining the
question it answers.

Every statement is a `PreparedStatement` and every **value** is bound as a
parameter. SQL text is assembled by concatenation in exactly two places —
the fixed `('RESOLVED', 'CLOSED', 'CANCELLED')` status list, and the table
and column names each DAO returns from `tableName()` and `idColumn()` — and
both are compile-time constants. Nothing a user typed ever reaches SQL text.

---

## 8. Where things are logged and written

| | Path |
|---|---|
| Log files | `logs/tsatms.log.N` — `INFO` and above to file, `WARNING` and above to console |
| Exported reports | `reports/` |
| Configuration | `src/main/resources/application.properties` |

---

## 9. Requirement traceability

[`TRACEABILITY.md`](TRACEABILITY.md) maps every numbered section of the case
study, and every item in the four common requirement lists, to the code that
implements it and the check that proves it.

---

## 10. Notes and known limits

- **There is no SMS gateway.** The one-time password is printed on screen,
  as the case study's console scope implies. The same is true of the
  self-service password reset, which therefore reveals whether an account
  exists — noted in the javadoc rather than papered over.
- **CAPTCHA is text, not an image.** A terminal cannot show a distorted
  bitmap. The characters are spaced and framed, which keeps the step honest
  in the flow without pretending to be image recognition.
- **A locked account cannot self-serve a password reset.** The lockout is
  the defence against guessing, and a route around it would have to be at
  least as hard to pass as the guessing it prevents.
- **Passwords are PBKDF2-WithHmacSHA256** with a per-account salt. No
  password value is ever logged or printed anywhere.
"# telcom-project" 
