# TCSMS implementation record

The supplied PDF describes **Telecom Customer & Subscription Management System**
(BSS), not the existing TSATMS incident-management (OSS) product. The legacy
application and its database are retained; the new BSS uses separate tables and
an explicit database URL. No existing database has been accessed.

Baseline: `mvn -o test` succeeded on JDK 25, with **no tests to run**.
Pre-existing edits in README.md, TRACEABILITY.md and pom.xml are preserved.

## Requirements and initial gap analysis

| PDF section | Initial status | Required implementation | Verification |
|---|---|---|---|
| 1 Business scenario | MISSING | Customer/admin BSS console alongside legacy OSS | End-to-end customer/admin workflows |
| 2 Login | PARTIAL | Reuse hashing/CAPTCHA; separate BSS identities, 3-attempt lockout, recovery, history, logout | Authentication and authorization tests |
| 3 Registration | MISSING | Complete profile, unique email/mobile/username, age and password validation | Invalid/duplicate registration tests |
| 4 Mobile/SIM | MISSING | Multiple connections, SIM inventory, physical/eSIM, prepaid/postpaid | Connection ownership and uniqueness tests |
| 5 Plan catalogue | MISSING | Admin plan maintenance, search/filter/sort/compare using streams | Catalogue and validation tests |
| 6 Subscriptions | MISSING | Plan changes, type compatibility/restrictions, history, add-ons | Duplicate/inactive/restricted plan tests |
| 7 Usage | MISSING | Persist voice/SMS/data/roaming, canonical units, monthly totals | Usage aggregation tests |
| 8 Billing | MISSING | Monthly persisted invoices, rental/usage/tax/discount, due dates | Decimal arithmetic and duplicate-month tests |
| 9 Payments | MISSING | JDBC atomic payment/bill/audit chain; no duplicate payment | Rollback and concurrent payment tests |
| 10 Administrator | MISSING | Customer/plan/SIM/subscription/usage/bill/payment/complaint/report management | Role and admin workflow tests |
| 11 Complaints | MISSING | Owned subscription complaints with categories, status and resolution | Ownership and resolution tests |
| 12 Java streams | PARTIAL | All eight BSS analytics and specified functional interfaces | Analytics over persisted data |
| 13 Background processes | PARTIAL | Billing scheduler, payment notifications, durable usage processing, overdue account monitor | Worker integration tests |
| 14 Suggested classes | PARTIAL | Domain/service/DAO separation; reuse safe utilities | Source inspection and build |
| Common database | PARTIAL | Normalized BSS schema, keys/checks/indexes/timestamps, SQL examples | JDBC integration tests |
| Common Java/security/files/patterns | PARTIAL | JDBC batch/savepoint/transactions; queues/executors; CSV/text export; logging/config | Integration/build/export tests |

Implementation order: isolated schema and JDBC infrastructure; identity and
authorization; catalogue/SIM/subscription; usage/billing/payment; complaints and
reports; console/workers; automated verification and final documentation.

## Decisions where the PDF leaves policy unspecified

- Console UI matches the PDF's login menu; no web frontend is required.
- Minimum registration age 18; temporary lock 15 minutes; recovery code lifetime
  5 minutes with at most 3 guesses. These policies are documented and tested.
- Monetary values use decimal arithmetic, two decimal places. Billing uses a
  configured tax rate and due-day interval, not the inconsistent example arithmetic.
- Payments record a confirmed external payment reference. The PDF names payment
  modes but specifies no gateway; this application does not charge a card or claim
  to settle funds.
- No customer, plan, usage, or administrator is seeded in the production database.
- Integration tests use a separate in-memory relational database with synthetic
  fixtures. Production uses the operator's explicitly configured MySQL database.

## Final implementation and coverage

The original ticket/SLA application is retained behind `--legacy`; the PDF's
customer/subscription console is the default entry point. `pom.xml` retains the
user's existing Java 25/compiler upgrade. README/TRACEABILITY retain their
pre-existing edits and now clearly distinguish the two products.

The paths below are relative to `src/main/java/com/amdocs/telecom/bss` unless a
resource/test/document path is specified. Test names refer to
`src/test/java/com/amdocs/telecom/bss/BssIntegrationTest.java`; the identical test
contract also runs on MySQL through `BssMySqlIT`.

| ID / PDF requirement | Final implementation | Verification / final status |
|---|---|---|
| 1a Central customer/subscription/billing system | `main/TcsmsApplication`, `controller/TelecomConsole`; separate BSS schema | Full console payment/complaint/admin/export flow — VERIFIED |
| 1b Customer and telecom administrator roles | Typed roles and database-backed authorization in `service/AuthenticationService` | Forged-role and cross-customer rejection — VERIFIED |
| 2a Five-option login menu, logout | `TelecomConsole.run`, role dashboards, session revocation | Console registration/EOF, complete console workflow, logout tests — VERIFIED |
| 2b Username/password, CAPTCHA | Reused PBKDF2/policy/one-use CAPTCHA; persistent BSS accounts | Wrong password/CAPTCHA, challenge reuse, malformed hash tests — VERIFIED |
| 2c Three failures, temporary lock | Durable failure count, 15-minute lock with clock-based expiry | Three-failure and expiry test — VERIFIED |
| 2d OTP recovery | Hashed 5-minute codes, 3 guesses, cooldown, password change and session revocation | Code expiry/guess/reuse/revocation tests — VERIFIED for internal flow |
| 2e Actual OTP delivery | `security/WebhookRecoveryDelivery`, HTTPS + bearer auth, timeouts, no redirects | Missing/insecure configuration tested; live delivery endpoint not supplied — IMPLEMENTED, NOT FULLY VERIFIED |
| 2f Login history / last login | `bss_login_history`, `bss_users.last_login`, customer-visible history | Success/failure/logout/last-login assertions — VERIFIED |
| 3a All customer profile fields and generated identity/date/status | `Domain.Registration`, `register`, schema users/customers | Persisted registration and profile tests — VERIFIED |
| 3b Unique email/mobile/username | Canonical validation plus database uniqueness, atomic user/profile creation | Duplicate rejection and no-orphan rollback — VERIFIED |
| 3c Age, mandatory fields, password complexity | `validation/Rules`, existing `PasswordPolicy` | Invalid email/mobile/password and under-age registration — VERIFIED |
| 4a One or more connections with SIM/mobile identifiers | `SubscriptionService.connect`, normalized SIM inventory + subscriptions | Multiple connections, rollback when same-plan constraint fails — VERIFIED |
| 4b PHYSICAL_SIM/ESIM, PREPAID/POSTPAID | Typed/checked fields and atomic SIM allocation | Both SIM and subscription types exercised — VERIFIED |
| 5a Admin plan catalogue, all specified fields | `SubscriptionService.savePlan`, retire/version used plans; -1 unlimited voice | Admin creation, filtering, immutability and unlimited-voice tests — VERIFIED |
| 5b Search/filter price/data, sort, compare | Stream predicates/comparators and two-plan comparison | Search/filter/sort/compare tests — VERIFIED |
| 6a Subscribe/upgrade/downgrade/type conversion | `changePlan`, persistent selected plan/type | Upgrade/downgrade/conversion/history tests — VERIFIED |
| 6b Add-on activation/deactivation | `bss_addons`, `bss_addon_history`, monthly allowance/rental snapshots | Add-on state/history/old-month price preservation — VERIFIED |
| 6c Inactive/duplicate plan prohibition | Service validation + unique customer/plan key | Inactive/same-plan rejection and duplicate connection rollback — VERIFIED |
| 6d Upgrade restrictions/history/actor/reason | Minimum tenure, mutual type-change permission, immutable historical prices | Tenure/conversion rejection and recorded histories — VERIFIED |
| 7a Usage fields, VOICE/SMS/DATA/ROAMING | Admin event import, canonical units, durable processing, calculated charges | All four types, units, quantities, duplicate/future/late import tests — VERIFIED |
| 7b Total data/voice/SMS, monthly/by-type totals | Monthly customer console stream aggregation; report usage grouping | Per-month/all-type usage tests and persisted report data — VERIFIED |
| 7c Highest consuming customers | Type-separated usage/customer grouping and ranking | Report generation from persisted usage — VERIFIED at fixture scale |
| 8a Monthly invoices and every monetary/date/status field | `BillingService.generateBill`, `bss_bills`, immutable `bss_month_terms` | Full invoice decimal totals, tax/discount and due date data — VERIFIED |
| 8b Reliable recurring generation | BillingScheduler catch-up, month validation, unique subscription/month | Idempotent generation, worker integration — VERIFIED |
| 9a Payment fields and four modes | `pay`, `bss_payments`, mode validation, confirmed external reference | UPI/CARD/NET_BANKING/BANK_TRANSFER exercised — VERIFIED |
| 9b Validate bill/amount, insert/update/audit/commit/rollback | Single subscription/bill-locked JDBC transaction including notification | Deliberate final audit failure rolls back all changes — VERIFIED |
| 9c No duplicates | Unique bill/reference and row locks | Duplicate bill, reused bank reference, concurrent payment tests — VERIFIED |
| 10a Admin customer management | Profile updates, enable/disable and session revocation | Profile uniqueness/ownership and account disable/reenable — VERIFIED |
| 10b Admin plan/SIM/subscription management | Versioned plans, SIM inventory/retirement, customer subscription assignment | Catalogue/SIM/connection/restriction tests — VERIFIED |
| 10c Admin usage/bills/payments | Import/view usage, invoice generation, financial lists | Invoice/payment/service and console workflow tests — VERIFIED |
| 10d Suspend/reactivate | Explicit manual suspension and overdue state; unpaid bills prevent reactivation | Overdue/pay/reactivate test — VERIFIED |
| 10e Complaint/report management | Admin complaint status/resolution and eight reports | Complete console resolution/export — VERIFIED |
| 11 All complaint fields and six categories | `ComplaintService`, checked category/priority/status fields, unique number | Owned complaint creation, unauthorized/empty/final-state rejection — VERIFIED |
| 12a Highest usage | `report/ReportService`, type/customer quantities | Persisted usage report generation — VERIFIED |
| 12b Customers by city | `groupingBy(city,counting())` | Customer/city and report tests — VERIFIED |
| 12c Price-range plans | Predicate/filter/sort | Catalogue and report tests — VERIFIED |
| 12d Most subscribed plans | Grouping/counting and descending ranking | Multi-connection and report tests — VERIFIED |
| 12e Monthly revenue | Actual confirmed payments grouped by selected month | Exact persisted revenue assertion — VERIFIED |
| 12f Usage by type | `groupingBy`, `summarizingDouble`, canonical units | Four usage types and report generation — VERIFIED |
| 12g Customers with unpaid bills | Stream sums grouped by customer; SQL examples | Unpaid bill workflow and report generation — VERIFIED |
| 12h Average monthly revenue per customer | BigDecimal revenue / registered customers at month end | Generated ARPU report; financial aggregates use exact decimals — VERIFIED at fixture scale |
| 12i Named Java functional APIs | Predicate, Function, Consumer, Supplier, Comparator, Optional, Collectors in services/reports | Compiler + exercised catalogue/report code — VERIFIED |
| 13a BillingScheduler | Hourly previous-month generation | Worker integration + invoice idempotency — VERIFIED |
| 13b PaymentNotificationService | Durable pending notifications → customer inbox, read state | Worker delivery and notification ownership — VERIFIED |
| 13c UsageProcessor | Durable pending usage + bounded BlockingQueue wakeup | Real worker processes persisted usage — VERIFIED |
| 13d AccountMonitor | Overdue and prepaid-expiry detection | Overdue/reactivation and prepaid expiry tests — VERIFIED |
| 13e Concurrency APIs | Executors, scheduled executors, Runnable/Callable/Future, synchronization, BlockingQueue | Worker start/idempotency/close, async processing, concurrent payments — VERIFIED |
| 14 Suggested classes | Domain requests, focused services, generic JDBC DAO/projections and normalized entities; safe existing utilities reused | Architecture/build inspection; suggestions not interpreted as mandatory duplicate class names — VERIFIED |
| DB1 Normalized relational entities | 19 BSS tables including schema metadata, users, customers, plans, SIMs, subscriptions, histories, usage, bills, payments, complaints, notifications and audit | Schema install twice, service integration, FK rejection — VERIFIED |
| DB2 Keys/uniqueness/NOT NULL/checks/indexes/status/timestamps | `resources/bss/schema.sql`, installer index definitions | H2 + actual MySQL schema installation/constraint tests — VERIFIED |
| DB3 SELECT/INSERT/UPDATE/DELETE, prepared statements | `dao/BssStore`, services, session/recovery cleanup | JDBC service tests — VERIFIED |
| DB4 JOIN/LEFT JOIN/GROUP BY/HAVING/ORDER BY/subquery/CASE/aggregates/date functions | `resources/bss/sample_queries.sql`, service/report queries | Five SQL reference queries executed on both database engines — VERIFIED |
| Java1 Architecture/OOP/interfaces/encapsulation/inheritance/generics/enums/collections | Existing retained architecture, immutable BSS domain, generic Row/DAO/Work, report Writer/Format; reused abstract utility infrastructure | Compilation and exercised services, inherited MySQL contract — VERIFIED |
| Java2 JDBC resource handling/batch/transactions/commit/rollback/savepoint | `BssStore.Tx`, notification batch, payment transaction | Savepoint/batch/rollback and payment failure injection — VERIFIED |
| Java3 Security | Hashing, CAPTCHA, OTP, lockout, database-backed roles, owned records, prepared SQL | Dedicated authentication/ownership/payment tests — VERIFIED except live OTP transport |
| Java4 CSV/text/config/logging | CSV/text writers, local/env config, JUL worker logging and reused AppLogger | 16 real exports, formula escaping, worker/config/startup checks — VERIFIED |
| Java5 At least three patterns | DAO, Factory and Strategy; existing Singleton infrastructure also retained | Code inspection + both export strategies exercised — VERIFIED |
| Ops1 Personal deployment configuration | Explicit independent DB URL/user/password; secure local file/env setup | Local secret exclusions and isolated packaged-JAR smoke test — VERIFIED; YOUR deployment setup remains required |

## Important files changed

- New `src/main/java/com/amdocs/telecom/bss/`: required product implementation.
- New `src/main/resources/bss/`: additive schema, indexes and SQL reference.
- New `src/test/java/com/amdocs/telecom/bss/`: JDBC integration contract and actual
  MySQL adapter; tests are isolated from production credentials.
- `src/main/java/com/amdocs/telecom/main/TSATMSApplication.java`: default BSS /
  explicit legacy routing. Original OSS classes/resources remain available.
- `security/PasswordHasher.java`: malformed stored hash now fails closed instead
  of throwing a hex-decoding error; covered by the integration contract.
- `util/SqlScriptRunner.java`: statement failures no longer print SQL text or
  driver error content that could disclose credentials during legacy setup.
- `util/ConfigLoader.java`: environment overrides for retained legacy properties.
- `pom.xml`: test-only H2/JUnit, explicit Surefire, updated product description;
  original Java upgrade and runtime dependency retained.
- `.gitignore`, `tcsms.properties.example`, README, TRACEABILITY and these docs:
  safe personal setup, actual architecture, product distinction, verification.
- `scripts/test_mysql.py`: starts/stops an isolated MySQL server, reruns the same
  contract, optionally smoke-tests the packaged JAR without personal credentials.
- `scripts/audit_configuration.py`: redacted heuristic scan of current production
  files and every reachable Git commit.

## Verification evidence and limits

- Baseline: `mvn -o test` succeeded but had no test sources.
- `mvn test`: expanded BSS integration suite succeeds against real H2 JDBC SQL.
- `mvn package`: tests and executable/dependency JAR generation succeed.
- `python scripts/test_mysql.py`: same service contract succeeds against an
  independently initialized MySQL 8.0.46 instance, including failure injection and
  concurrent payments. No installed service or existing schema was used.
- `java -jar target/tsatms-jar-with-dependencies.jar --help` and
  `--legacy --help`: both entry points work without database access.
- Runtime archive inspection: BSS entry point present; no H2, JUnit, test classes,
  `.env` or private `tcsms.properties` packaged.
- `git diff --check`: passes. `git check-ignore` confirms local properties,
  environment files, build output and reports are excluded.

Final command summaries and the redacted history scan result are recorded below.
No existing database, cloud resource,
repository history or uncommitted user changes were reset or deleted. No commits,
pushes or deployment were performed.

Remaining limits:

1. **BLOCKED — personal deployment:** supply your own database/account and create
   your administrator. The implementation is exercised on isolated databases; it
   deliberately does not infer ownership of any installed database.
2. **IMPLEMENTED, NOT FULLY VERIFIED — external OTP delivery:** supply and verify
   your HTTPS delivery endpoint/token. Internal recovery/code lifecycle is tested.
3. Legacy OSS database harnesses were not run; they target a different product
   and require explicit ownership/configuration of their own database. Compilation
   and the legacy help command succeed; existing source/history was retained.
4. No million-customer load test, external security assessment, or deployment
   environment test was performed. Java-stream reports materialize their input;
   production-scale pagination/streaming/batch-window tuning would require measured
   workload requirements. No numerical performance target is specified in the PDF.
5. Browser/responsive tests are not applicable to the PDF's console UI. Keyboard
   workflows are tested using the actual console and real services/JDBC.
6. The document does not specify payment settlement APIs, prepaid wallets,
   prorating, bill adjustments or cancellation. These are not represented as
   implemented integrations. The explicit billing decisions are in SETUP.md.

All applicable local product workflows are implemented. This is a tested console
implementation with remaining personal setup and live-delivery verification,
not a claim that external services or an unconfigured production deployment are
already operational.

## Final execution record — 30 October 2026

| Executed command/check | Observed result |
|---|---|
| Baseline `mvn -o test` | SUCCESS; no tests existed |
| `mvn test` | SUCCESS; expanded suite reached 29 tests, 0 failures/errors/skips |
| `mvn package` | SUCCESS; 29 tests passed; both executable JARs generated |
| `python scripts/test_mysql.py --smoke-jar` | SUCCESS; 29 tests passed on disposable MySQL 8.0.46; additive init, `--check`, first-admin creation and default console exit passed against its separate smoke schema |
| Targeted `mvn -Dtest=BssIntegrationTest#reportsContainPersistedDataAndExportBothFormats test` | SUCCESS; exact expected data asserted for all eight analytics reports; 16 export files verified |
| Packaged `--help`, `--legacy --help` | Both returned successfully without DB access |
| JAR archive inspection | BSS entry present; H2/JUnit/test classes/private properties/.env absent |
| `git diff --check` | Clean |
| `git check-ignore` | Local properties, .env, target and report paths ignored |
| `python scripts/audit_configuration.py` | 10 reachable commits / 491 distinct production snapshots scanned; 6 locations flagged without exposing values, reviewed as synthetic passwords in the retained SecurityVerification harness (3 current + same 3 historical). No real production credential identified by this heuristic scan; remote URL has no embedded password |
| Disposable-process cleanup | No running mysqld process points to this workspace's `target/mysql-test-*` directories |

The first broad scanner also flagged normal Java setter/configuration expressions;
these were reviewed and the scanner was corrected to detect quoted credential
literals in Java. It deliberately continues to report legacy security harness
fixtures rather than silently ignoring them. The legacy harness explicitly creates
throwaway verification accounts; these are not previous-user credentials and are
never used by the new BSS product. No real secret values were printed or reused.

Secret scanning is heuristic, not a forensic guarantee: ignored local files,
unreachable/pruned Git objects, provider logs and remote access histories are
outside its scope. No Git history was rewritten. Current personal production
database and recovery credentials remain unset and must be supplied by the user.
