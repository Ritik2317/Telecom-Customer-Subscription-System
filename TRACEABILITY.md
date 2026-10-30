# Requirement Traceability

**Current PDF coverage:** see [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md).
The retained mapping below documents the original trouble-ticket/SLA OSS case
study, which differs from the supplied Customer & Subscription Management PDF.
Legacy commands require `--legacy` before the original switches. The original
Java upgrade edits in this file are retained.

Every numbered section of the case study, and every item in the four common
requirement lists, mapped to the code that implements it and the check that
proves it.

"Proved by" names a harness switch, and where `--demo` also shows the thing
running end to end as part of one incident's story, it says so too. Every
harness runs against the live database.

`--demo` prints the case-study section beside each scene, like
`4. Who should take it   [section 7]`, so searching its output for
`[section 7]` finds the scene for any row below.

---

## Section 1 — Business scenario and roles

| Requirement | Implemented in | Proved by |
|---|---|---|
| Customer | `Role.CUSTOMER`, `CustomerDashboard` | `--verify-console`, `--demo` |
| Service Desk Administrator | `Role.SERVICE_DESK`, `ServiceDeskDashboard` | `--verify-console`, `--demo` |
| Network Engineer | `Role.NETWORK_ENGINEER`, `NetworkEngineerDashboard` | `--verify-console`, `--demo` |
| Network Manager | `Role.NETWORK_MANAGER`, `NetworkManagerDashboard` | `--verify-console`, `--demo` |
| Each role sees only its own capability | `Permission`, `AccessControl`, `Menu.guarded(...)` | `--verify-security`, `--verify-console` |

---

## Section 2 — Login module

| Requirement | Implemented in | Proved by |
|---|---|---|
| Six menu options, in the document's order | `MainMenu` | `--verify-console` |
| Customer / Service Desk / Engineer / Manager login | `MainMenu.signIn(Role)` | `--verify-console` |
| Forgot Password | `AuthenticationService.beginPasswordReset`, `completePasswordReset` | `--verify-console` §12 |
| Exit | `MainMenu` | `--verify-console` |
| CAPTCHA before credentials | `CaptchaGenerator`, `CaptchaChallenge` | `--verify-security` |
| Password authentication | `PasswordHasher` (PBKDF2WithHmacSHA256, per-account salt) | `--verify-security` |
| OTP as a second factor | `OtpService`, `OneTimePassword` | `--verify-security`, `--demo` |
| Account locks after repeated failures | `AuthenticationServiceImpl`, `UserAccount.isCurrentlyLocked()` | `--verify-security` |
| Login history recorded | `LoginHistory`, `LoginHistoryDAO`, `LoginStatus` | `--verify-security`, `--verify-dao` |
| Role-based access control after login | `UserSession`, `AccessControl`, `Permission` | `--verify-security` |

The sign-in sequence is CAPTCHA, then password, then OTP — the order the
document gives. A wrong CAPTCHA never reaches the password check, so a
guessing script pays the CAPTCHA cost on every attempt.

---

## Section 3 — Customer and service information

| Requirement | Implemented in | Proved by |
|---|---|---|
| Customer record | `Customer`, `customers` table, `CustomerDAO` | `--verify-dao` |
| Customer types | `CustomerType` (CONSUMER, SME, ENTERPRISE) | `--verify-dao` |
| Subscribed services | `TelecomService`, `telecom_services`, `TelecomServiceDAO` | `--verify-dao` |
| Service types | `ServiceType` | `--verify-dao` |
| Service status | `ServiceStatus` | `--verify-dao` |
| A customer may hold several services | FK `telecom_services.customer_id` | `--verify-dao`, seed data |

---

## Section 4 — Trouble ticket

| Requirement | Implemented in | Proved by |
|---|---|---|
| Ticket fields | `TroubleTicket`, `trouble_tickets` | `--verify-ticket`, `--verify-dao` |
| Auto-generated ticket number `TT-YYYY-NNNNNN` | `TicketServiceImpl`, `AppConstants.TICKET_NUMBER_PREFIX` | `--verify-ticket` |
| Four priorities | `Priority` (CRITICAL, HIGH, MEDIUM, LOW) | `--verify-ticket`, `--verify-sla` |
| Eight statuses | `TicketStatus` | `--verify-ticket` |
| Only legal status moves are allowed | `TicketLifecycle` | `--verify-ticket` |
| Field validation | `TicketValidator`, `Validators`, `ValidationResult` | `--verify-ticket` |

`TicketLifecycle` is an explicit state machine rather than a scatter of
`if` statements, so an illegal transition is refused in one place and the
legal set can be read at a glance.

---

## Section 5 — Sample ticket

| Requirement | Implemented in | Proved by |
|---|---|---|
| `TT-2026-004521` exactly as tabulated | `06_seed_data.sql` | `--verify-dao`; `TicketView` renders the same card |
| The ticket card layout | `TicketView`, `Displayable.toDetailBlock()` | `--verify-console`, `--demo` |

---

## Section 6 — Incident categories

| Requirement | Implemented in | Proved by |
|---|---|---|
| All ten categories | `IncidentCategory` | `--verify-ticket`, `--verify-reports` |
| Category drives the specialization sought | `IncidentCategory` → `Specialization` mapping | `--verify-assign` |

Nine of the ten appear in seeded tickets; `OTHER` does not. It is
implemented and selectable — see the README's note on seed coverage.

---

## Section 7 — Engineer management and recommendation

| Requirement | Implemented in | Proved by |
|---|---|---|
| Engineer record | `NetworkEngineer`, `network_engineers`, `NetworkEngineerDAO` | `--verify-dao` |
| Specialization | `Specialization` | `--verify-assign` |
| Availability | `EngineerAvailability` | `--verify-assign` |
| Workload against capacity | `active_ticket_count` / `max_ticket_capacity`, `trg_engineers_after_update` | `--verify-assign` |
| **Criterion 1** — specialization matches the category | `EngineerRecommender`, `MatchTier` | `--verify-assign` |
| **Criterion 2** — region matches | `EngineerRecommender` | `--verify-assign` |
| **Criterion 3** — availability | `EngineerRecommender` | `--verify-assign` |
| **Criterion 4** — current workload | `EngineerRecommender` | `--verify-assign` |
| **Criterion 5** — experience | `EngineerRecommender` | `--verify-assign` |
| Stream + Lambda + Comparator + Optional | `EngineerRecommender` | `--verify-assign`, `--demo` |

The five criteria are not equal, so they are not summed into one score. A
specialization and region match is a different *tier* from a specialization
match alone (`MatchTier`), and experience only breaks ties within a tier.
Ranking within a tier is a chained `Comparator`; the best candidate comes
back as an `Optional`.

---

## Section 8 — SLA management

| Requirement | Implemented in | Proved by |
|---|---|---|
| CRITICAL — 15 min response, 2 h resolution | `sla_configuration` seed | `--verify-sla` |
| HIGH — 30 min, 4 h | `sla_configuration` seed | `--verify-sla` |
| MEDIUM — 2 h, 12 h | `sla_configuration` seed | `--verify-sla` |
| LOW — 8 h, 48 h | `sla_configuration` seed | `--verify-sla` |
| Deadlines derived on creation | `trg_tickets_before_insert`, `fn_sla_deadline`, `fn_sla_response_deadline` | `--verify-db`, `--verify-sla` |
| Three SLA statuses | `SLAStatus` (WITHIN_SLA, AT_RISK, BREACHED) | `--verify-sla` |
| Live SLA evaluation | `SlaService`, `SlaServiceImpl`, `SlaEvaluation` | `--verify-sla`, `--demo` |
| Business-hours and continuous clocks | `SlaClock`, `BusinessHoursSlaClock`, `ContinuousSlaClock`, `SlaClockRegistry` | `--verify-sla` |
| SLA board | `--sla`, `vw_sla_compliance` | `--verify-db` |

Java and SQL must agree on SLA state or the dashboard and the report will
disagree. `fn_sla_status` and `SlaEvaluation` are checked against each other
in `--verify-sla`, including the rounding: `AVG(fn_resolution_hours(...))`
averages already-rounded hours, so the Java average does the same.

---

## Section 9 — Ticket escalation

| Requirement | Implemented in | Proved by |
|---|---|---|
| Ladder: Engineer → Team Lead → Network Manager → Operations Manager | `EscalationLevel` | `--verify-escalation` |
| Escalation triggers | `EscalationTrigger`, `EscalationPolicy` | `--verify-escalation` |
| Escalation recorded | `EscalationHistory`, `escalation_history`, `EscalationHistoryDAO` | `--verify-escalation`, `--verify-dao` |
| Escalation service | `EscalationService`, `EscalationServiceImpl` | `--verify-escalation`, `--demo` |
| The same thing as a stored procedure | `sp_escalate_ticket`, via `ReportDAO.escalateViaProcedure` | `--verify-escalation` |
| **PriorityQueue** orders what to escalate first | `EscalationServiceImpl`, `EscalationCandidate` | `--verify-escalation` |

`EscalationCandidate` has a total ordering — priority first, then how far
past its deadline — so the `PriorityQueue` always yields the most urgent
ticket, and a sweep that is interrupted has still done the work that
mattered most.

---

## Section 10 — Incident resolution

| Requirement | Implemented in | Proved by |
|---|---|---|
| Eight resolution codes | `ResolutionCode` | `--verify-ticket` |
| Resolution notes and time | `TroubleTicket`, `trg_tickets_before_update` | `--verify-ticket` |
| `TicketStatusHistory` for every change | `TicketStatusHistory`, `TicketStatusHistoryDAO`, `trg_tickets_before_update` | `--verify-ticket`, `--demo` |
| Resolve and close | `TicketServiceImpl` | `--verify-ticket`, `--demo` |
| The same thing as a stored procedure | `sp_resolve_ticket`, via `ReportDAO.resolveViaProcedure` | `--verify-ticket` §9 |
| Customer feedback and rating | `Feedback`, `FeedbackDAO` | `--verify-dao`, `--demo` |

---

## Section 11 — Network event processing

| Requirement | Implemented in | Proved by |
|---|---|---|
| Event record `NE-884521` / `MUM-RAN-045` / `LINK_DOWN` / `CRITICAL` | `NetworkEvent`, `06_seed_data.sql` | `--verify-events` |
| Event types | `NetworkEventType` | `--verify-events` |
| Severity | `Severity` | `--verify-events` |
| Event processing state | `EventStatus` | `--verify-events` |
| An event raises a ticket automatically | `NetworkEventService`, `NetworkEventServiceImpl` | `--verify-events`, `--demo` |
| Event simulation | `NetworkEventSimulator` | `--verify-events` |
| **Multithreaded** processing | `NetworkEventProcessor` | `--verify-threads` |
| **Queue-based** producer/consumer | `NetworkEventFeeder` (producer) → `BlockingQueue` → `NetworkEventProcessor` (consumer) | `--verify-threads` |

An event that has already raised a ticket must not raise a second one when
two threads pick it up together. The claim is made in the database, not in
Java — `EventStatus` moves under a conditional update, so only one thread's
update matches and the loser skips it.

---

## Section 12 — Notification system

| Trigger (the document's six) | Implemented in | Proved by |
|---|---|---|
| Ticket creation | `NotificationType`, `TicketEventPublisher` | `--verify-events` |
| Engineer assignment | " | `--verify-events`, `--demo` |
| SLA warning | " | `--verify-events`, `--verify-threads` |
| SLA breach | " | `--verify-events`, `--verify-threads` |
| Ticket resolution | " | `--verify-events`, `--demo` |
| Ticket closure | " | `--verify-events`, `--demo` |
| The `Notification` record's fields | `Notification`, `notifications` table | `--verify-dao` |
| Delivery | `NotificationProcessor` (background worker) | `--verify-threads` |

Escalation raises a notification too, which the document does not list. It
is the same mechanism, added because telling nobody that a ticket has been
escalated would make section 9 pointless.

One event reaches several recipients — an assignment tells both the customer
and the engineer — so two notification rows from one action is correct, not
duplication. `--demo` prints the recipient beside each row to make that
visible.

---

## Section 13 — Customer dashboard

All eight options, in the document's order, in `CustomerDashboard`. Proved
by `--verify-console` and `--demo`.

| # | Option |
|---|---|
| 1 | View Active Services |
| 2 | Raise Trouble Ticket |
| 3 | View My Tickets |
| 4 | Track Ticket |
| 5 | View Ticket History |
| 6 | View Notifications |
| 7 | Submit Feedback |
| 8 | Logout |

The document also lists what a customer may see on a ticket — ticket
number, service, priority, status, engineer, SLA status and resolution.
All seven are on `TicketDetailDTO` and rendered by `TicketView`.

---

## Section 14 — Service desk dashboard

All eight options, in the document's order, in `ServiceDeskDashboard`.
Proved by `--verify-console` and `--demo`.

| # | Option |
|---|---|
| 1 | View Open Tickets |
| 2 | Assign Engineer |
| 3 | Reassign Ticket |
| 4 | Escalate Ticket |
| 5 | Update Priority |
| 6 | Monitor SLA |
| 7 | Close Ticket |
| 8 | Generate Reports |

---

## Section 15 — Network manager dashboard

All six figures, in `NetworkManagerDashboard` and `vw_manager_dashboard`.
Proved by `--verify-console`, `--verify-db` and `--demo`.

| # | Figure | Column |
|---|---|---|
| 1 | Total Open Tickets | `total_open_tickets` |
| 2 | Critical Incidents | `critical_incidents` |
| 3 | SLA At Risk | `sla_at_risk` |
| 4 | SLA Breached | `sla_breached` |
| 5 | Resolved Today | `resolved_today` |
| 6 | Average Resolution Time | `avg_resolution_hours` |

All six come from one row of `vw_manager_dashboard`, so the six numbers on
the screen are consistent with each other rather than six queries taken at
six slightly different moments.

Not one option on this dashboard changes a ticket. A manager reads the
operation; the people running it change it.

---

## Section 16 — Java API requirements

All nine analyses are in `TicketAnalytics`. Proved by `--verify-reports`
and `--demo`.

| # | Analysis (the document's wording) | Method |
|---|---|---|
| 1 | Tickets by status | `byStatus()` |
| 2 | Tickets by priority | `byPriority()` |
| 3 | Engineer workload | `engineerWorkload()` |
| 4 | SLA breach analysis | `slaBreachAnalysis()` |
| 5 | Average resolution time | `averageResolutionHours()`, and by priority and category |
| 6 | Tickets by region | `byRegion()` |
| 7 | Top incident categories | `topCategories(int)` |
| 8 | Engineer performance | `engineerPerformance()` |
| 9 | Customers with repeated incidents | `repeatIncidents(int)` |

The section's worked example — *the three engineers with the lowest active
workload who have the required specialization and are currently available* —
is `EngineerRecommender`, and `--verify-assign` checks it returns exactly
that.

| Java language/API feature | Where |
|---|---|
| Lambdas | throughout; `RowMapper`, `Menu` options, comparators |
| Functional interfaces | `RowMapper`, `SlaClock`, `TicketEventListener` |
| Stream API | `TicketAnalytics`, `EngineerRecommender` |
| `Collectors.groupingBy` / `counting` / `averagingDouble` | `TicketAnalytics` |
| `Optional` | `EngineerRecommender`, every DAO `findBy...` |
| Method references | throughout; `OpenTicketDTO::toSummaryLine` |
| Default and static interface methods | `Displayable`, `DescribableEnum` |
| `Comparator` chaining | `EngineerRecommender`, `OpenTicketDTO.byUrgency()` |
| Date/Time API | `LocalDateTime` throughout; `SlaClock` |

---

## Section 17 — Multithreading

| Requirement | Implemented in | Proved by |
|---|---|---|
| **`NetworkEventProcessor`** — processes network events | `NetworkEventProcessor` | `--verify-threads` |
| **SLA Monitor** — tickets approaching their deadline | `SlaMonitor` | `--verify-threads` |
| **`NotificationProcessor`** — pending notifications | `NotificationProcessor` | `--verify-threads` |
| **`ReportGenerator`** — reports asynchronously | `ReportGenerator`, `submit(ReportKind)` → `Future` | `--verify-threads`, `--verify-reports` |
| *(additional)* the event producer feeding the queue | `NetworkEventFeeder` | `--verify-threads` |
| `ExecutorService` | `BackgroundServices` | `--verify-threads` |
| `ScheduledExecutorService` | `BackgroundServices`, `SlaMonitor` | `--verify-threads` |
| `BlockingQueue` | feeder → processor hand-off | `--verify-threads` |
| `Runnable` | `BackgroundWorker` | `--verify-threads` |
| `Callable` and `Future` | `ReportGenerator.submit(...)` and `invokeAll(...)` | `--verify-threads` |
| Synchronization | `BackgroundServices`, atomic counters in the workers | `--verify-threads` |
| Named threads, clean shutdown | `NamedThreads`, `BackgroundServices.stop()` | `--verify-threads` |

`BackgroundWorker` is a template method: every worker gets the same
start/poll/handle/stop skeleton and the same rule that one bad item is
logged and skipped rather than being allowed to kill the thread. The
`SEVERE` lines in `--verify-threads` are that rule being tested on purpose.

---

## Section 18 — Reports

All seven, in `ReportKind` / `ReportGenerator`, exportable to CSV and text.
Proved by `--verify-reports` and `--reports`.

| Code | Report |
|---|---|
| RPT1 | Ticket Volume Report |
| RPT2 | SLA Compliance Report |
| RPT3 | Engineer Performance Report |
| RPT4 | Incident Category Report |
| RPT5 | Regional Incident Report |
| RPT6 | Average Resolution Report |
| RPT7 | Critical Incident Report |

| Requirement | Implemented in |
|---|---|
| CSV export | `CsvReportWriter` |
| Text export | `TextReportWriter` |
| Format chosen at runtime | `ReportFormat.newWriter()` (Factory + Strategy) |
| Written to disk | `ReportExporter` → `reports/` |

---

## Section 19 — JDBC transaction: the assignment chain

`EngineerAssignmentServiceImpl.assignEngineer(...)`, inside a single
`TransactionTemplate` block. Proved by `--verify-assign` and `--demo`.

| # | Step |
|---|---|
| 1 | Validate the ticket exists and may be assigned |
| 2 | Validate the engineer exists |
| 3 | Check availability and capacity |
| 4 | Assign the engineer, incrementing the workload |
| 5 | Update the ticket's status and engineer |
| 6 | Insert the status history row |
| 7 | Insert the notifications |
| 8 | Insert the audit record |

Any one of these failing rolls back all of them. `--verify-assign` proves
it by forcing a failure at the last step and confirming the ticket is
untouched — a ticket can never show an engineer who was never told.

The same chain exists a second time as `sp_assign_engineer`, reachable
through `ReportDAO.assignEngineerViaProcedure` and proved in the same
harness. The Java path is the one the application uses, because it composes
with the transactions around it; the procedure runs a `START TRANSACTION`
of its own and so cannot be nested inside a caller's.

---

## Section 20 — Suggested classes

Every suggested class exists. Seven were renamed, for consistency with the
rest of the codebase rather than to depart from the document:

| Document | This project | Why |
|---|---|---|
| `ServiceDAO` | `TelecomServiceDAO` | `Service` alone collides with the service layer |
| `TicketDAO` | `TroubleTicketDAO` | Matches the entity, `TroubleTicket` |
| `EngineerDAO` | `NetworkEngineerDAO` | Matches the entity, `NetworkEngineer` |
| `SLAConfigDAO` | `SLAConfigurationDAO` | Matches the entity, `SLAConfiguration` |
| `PasswordUtil` | `PasswordHasher` | Says what it does; it is not a grab-bag of utilities |
| `OTPService` | `OtpService` | Java naming for acronyms in mixed-case identifiers |
| `SLAService` | `SlaService` | Same |

Named exactly as the document has them:

- **Models** — `Customer`, `TelecomService`, `TroubleTicket`,
  `NetworkEngineer`, `SLAConfiguration`, `TicketStatusHistory`,
  `EscalationHistory`, `NetworkEvent`, `Notification`, `Feedback`,
  `AuditLog`, `LoginHistory`
- **Services** — `AuthenticationService`, `TicketService`,
  `EngineerAssignmentService`, `EscalationService`, `NetworkEventService`,
  `NotificationService`, `ReportService`
- **DAOs** — `CustomerDAO`, `NetworkEventDAO`, `NotificationDAO`
- **Utilities** — `CaptchaGenerator`, `DBConnection`, `ReportGenerator`

Five classes exist that the document does not suggest, because the features
it does ask for need them: `UserAccount` and `UserDAO` (section 2 needs
accounts to authenticate), `LoginHistoryDAO` (section 2 asks for login
history to be kept), and `AuditLogDAO` and `FeedbackDAO` (section 20 lists
both entities, but no DAO to reach them).

---

## Common requirement — database

| Requirement | Where |
|---|---|
| Normalised tables | 13 tables in `01_schema.sql` |
| Primary keys | Every table |
| Foreign keys | Every relationship, with explicit `ON DELETE` behaviour |
| Unique constraints | Every business key — `username`, `email`, `customer_number`, `service_code`, `employee_code`, `ticket_number`, `event_reference`, one SLA row per priority, one feedback per ticket, one login per person |
| `NOT NULL` | Every column a row cannot be meaningful without |
| Check constraints | Every enum column is checked against its values, so an invalid status cannot be written even by hand. Also ratings 1–5, non-negative workload, capacity above zero, experience 0–50, resolution window at least the response window, an escalation that actually moves a level, `logout_time >= login_time`, and a resolved ticket that must carry its resolution fields |
| Status columns | `users`, `customers`, `telecom_services`, `trouble_tickets`, `network_events`, `notifications` |
| Created / updated timestamps | Every table that is ever updated |
| Indexes | On the columns actually filtered, joined and sorted on |

| SQL required | Demonstrated in |
|---|---|
| `SELECT`, `INSERT`, `UPDATE`, `DELETE` | Every DAO |
| `JOIN` | `vw_ticket_details`, `vw_open_tickets` |
| `LEFT JOIN` | `vw_open_tickets` (tickets with no engineer yet) |
| `GROUP BY` | `vw_category_incidents`, `vw_engineer_workload` |
| `HAVING` | `vw_customer_repeat_incidents` |
| `ORDER BY` | Throughout |
| Subquery | `vw_customer_repeat_incidents`, `07_sample_queries.sql` |
| `CASE` | `fn_sla_status`, `vw_sla_compliance` |
| Aggregates | `COUNT`, `AVG`, `SUM`, `MIN`, `MAX` across the views |
| Date functions | `TIMESTAMPDIFF`, `DATE_ADD`, `DATE_SUB`, `NOW`, `CURDATE` |
| Views | 7, all exercised by `--verify-db` |
| Stored procedures | 6 — five called from Java via `ReportDAO`, `sp_manager_dashboard` from `--verify-db` |
| Functions | 5, all called directly by `--verify-db` |
| Triggers | 4 — two checked by `--verify-db`, the others by `--verify-ticket` and `--verify-assign` |
| Indexes | Declared with the tables |

`07_sample_queries.sql` is a library of these, each with a comment naming
the question it answers.

`--verify-db` prints what each view, function and procedure returned rather
than asserting, because the point is to show the SQL working on real data.
The three procedures that change data are proved where their effects can be
checked and undone: `sp_assign_engineer` in `--verify-assign`,
`sp_escalate_ticket` in `--verify-escalation`, `sp_resolve_ticket` in
`--verify-ticket`.

---

## Common requirement — Java architecture

| Package required | Present |
|---|---|
| `controller` | yes |
| `service` / `service.impl` | yes |
| `dao` / `dao.impl` | yes |
| `model` | yes, plus `model.enums` |
| `dto` | yes |
| `exception` | yes |
| `validation` | yes |
| `security` | yes |
| `scheduler` | yes |
| `report` | yes |
| `util` | yes |
| `main` | yes |

Five further sub-packages exist because those areas grew large enough to
deserve their own: `service.analytics`, `service.assignment`,
`service.escalation`, `service.event`, `service.sla`.

### Core Java

| Requirement | Where |
|---|---|
| OOP, encapsulation | Every model; fields private with accessors |
| Inheritance | `BaseEntity` → `TimestampedEntity` → entities; `AbstractParty` |
| Abstract classes | `AbstractParty`, `AbstractJdbcDAO`, `BackgroundWorker`, `Dashboard` |
| Interfaces | `Identifiable`, `Displayable`, `Auditable`, `DescribableEnum`, every DAO and service |
| Polymorphism | `SlaClock`, `ReportWriter`, `TicketEventListener` |
| Collections | `List`, `Map`, `Set`, `Queue`, `PriorityQueue`, `BlockingQueue` |
| Generics | `GenericDAO<T, ID>`, `RowMapper<T>`, `Tally<T>` |
| Enums with behaviour | `Priority`, `TicketStatus`, `EscalationLevel`, `Role` |
| Exception hierarchy | `TSATMSException` and its subclasses |
| `equals` / `hashCode` / `toString` | On every entity |

### JDBC

| Requirement | Where |
|---|---|
| **Connection** | `DBConnection` (Singleton), `ConnectionScope` |
| **`PreparedStatement`** | `JdbcOperations`, `JdbcSupport`, every DAO |
| **`ResultSet`** | `RowMapper<T>` — one mapper per entity, so no `ResultSet` escapes its DAO |
| `CallableStatement` | `ReportDAOImpl` — all five procedure calls |
| **Transactions** | `TransactionTemplate`, `TransactionContext`, `ConnectionScope` |
| **Commit** | `TransactionTemplate`, at the outermost block only |
| **Rollback** | `TransactionTemplate`, on any exception |
| **Batch processing** | `JdbcOperations.executeBatch(...)`, used by `NotificationDAOImpl`, `AuditLogDAOImpl`, `NetworkEventDAOImpl` |
| **Savepoint** | `TransactionContext.savepoint(...)` / `rollbackTo(...)`, used by every harness that writes |
| Resource cleanup | try-with-resources throughout |

### Multithreading

Covered in section 17 above.

### Security

| Requirement | Where |
|---|---|
| CAPTCHA | `CaptchaGenerator`, `CaptchaChallenge` |
| Password hashing | `PasswordHasher` — PBKDF2WithHmacSHA256, per-account salt |
| OTP | `OtpService`, `OneTimePassword` |
| Account locking | `AuthenticationServiceImpl`, `UserAccount.isCurrentlyLocked()` |
| Role-based authorization | `Permission`, `AccessControl`, `Menu.guarded(...)` |
| `PreparedStatement` | Bound parameters everywhere; no user input reaches SQL text |
| *(also)* password policy | `PasswordPolicy` |
| *(also)* session management | `UserSession`, `SessionContext` |
| *(also)* audit trail | `AuditLog`, `AuditTrailListener` |

### File handling

| Requirement | Where |
|---|---|
| Reading configuration | `ConfigLoader` |
| Reading SQL scripts | `SqlScriptRunner` |
| Writing reports | `ReportExporter`, `CsvReportWriter`, `TextReportWriter` |
| Writing logs | `AppLogger` → `logs/` |

### Design patterns — minimum three required, six used

| Pattern | Where |
|---|---|
| Singleton | `ConfigLoader`, `DBConnection`, `DAOFactory` |
| DAO | `dao` and `dao.impl` |
| Factory | `DAOFactory`, `ReportFormat.newWriter()` |
| Strategy | `SlaClock`, `ReportWriter` |
| Observer | `TicketEventPublisher`, `TicketEventListener`, `AuditTrailListener` |
| Template Method | `Dashboard.open()`, `BackgroundWorker`, `AbstractJdbcDAO` |
