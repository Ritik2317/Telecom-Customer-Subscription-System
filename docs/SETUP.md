# Customer & Subscription Management System (TCSMS)

The supplied 17-page PDF specifies a **console BSS application** with customer
and administrator roles. This is now the default application. The original
trouble-ticket/SLA OSS remains available with `--legacy`. Its older README and
traceability sections describe that separate product, not PDF coverage.

## Prerequisites and commands

- JDK 25 (preserves the existing workspace's Java upgrade), Maven 3.9+, MySQL 8.0.16+.
- Java 8 streams, functional interfaces, JDBC and concurrency APIs required by
  the PDF are used; the project does not target a Java 8 runtime.
- Maven downloads the existing MySQL driver plus JUnit and H2 for tests. The
  project-scoped Maven settings avoid changing machine-wide configuration.

```powershell
mvn test
mvn package
Copy-Item tcsms.properties.example tcsms.properties
# Edit local tcsms.properties with your own database URL, user and password.
java -jar target/tsatms-jar-with-dependencies.jar --init
java -jar target/tsatms-jar-with-dependencies.jar --create-admin
java -jar target/tsatms-jar-with-dependencies.jar --check
java -jar target/tsatms-jar-with-dependencies.jar
```

The archive retains its original name for compatibility. `--help` needs no
database. `--init` installs additive `bss_*` tables and indexes in the configured
database; it never creates a database/user, drops tables, or seeds customers.
Installation is repeatable. MySQL DDL implicitly commits; if installation fails,
correct the reported configuration/permissions issue and rerun. Version 1 is
recorded only after all tables/indexes exist. Do not run initialization from
multiple processes concurrently.

Ask your own MySQL administrator to create a dedicated empty database and user.
For a local development instance you own, the following is an illustrative
operator script, **not executed by this application**:

```sql
CREATE DATABASE tcsms CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'tcsms_app'@'localhost' IDENTIFIED BY '<choose-your-own-strong-password>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, INDEX, REFERENCES ON tcsms.* TO 'tcsms_app'@'localhost';
```

Use a separate installer identity if your production policies require it. After
installation, the runtime account only needs SELECT/INSERT/UPDATE/DELETE on this
database. The application never needs root or grants on another database.
Production TLS should use `sslMode=VERIFY_IDENTITY` with a trusted server
certificate. For a loopback-only local development database without TLS, you may
explicitly choose `sslMode=DISABLED&allowPublicKeyRetrieval=true`; do not copy
that setting to a remote deployment.

`--create-admin` prompts for **your** username, email and password and only works
when no administrator exists. There are no default accounts/passwords, seeded
plans, customers, SIMs or usage. Start by creating plans and SIM inventory from
the administrator menu, register a customer from option 2, and subscribe from
the customer dashboard. Empty lists are expected before these actions.

## Configuration and personal setup checklist

Environment variables override the corresponding keys in ignored local
`tcsms.properties`. The application does not automatically parse `.env` files.
All settings are server/local process settings; there is no browser bundle.

| Environment variable | File key / purpose | Where to obtain / required action | Verification |
|---|---|---|---|
| `TCSMS_DB_URL` | `db.url`: your MySQL JDBC endpoint/schema | Your own local database or hosting console. Required; implementation ready, your deployment not configured. | `--init`, then `--check` |
| `TCSMS_DB_USER` | `db.user`: dedicated database user | Create through your MySQL administrator. Required. | `--check` |
| `TCSMS_DB_PASSWORD` | `db.password`: database password | Set in your own database account. Required, never in a shared resource file. | `--check` |
| `TCSMS_RECOVERY_URL` | `recovery.url`: HTTPS OTP delivery webhook | An endpoint you operate that sends email/SMS to the supplied registered address. Optional for core workflows; required for password recovery. Adapter implemented; external delivery not verified. | Run Forgot Password and receive the code at the registered address |
| `TCSMS_RECOVERY_TOKEN` | `recovery.token`: bearer authentication for that endpoint | Generate in your endpoint/provider configuration. Required with recovery URL. | Verify unauthorized calls fail and the app can deliver a code |
| `TCSMS_TIMEZONE` | `timezone`, default `Asia/Kolkata` | Set your intended billing zone; match JDBC `serverTimezone`. | Compare login/usage/bill dates |
| `TCSMS_TAX_RATE` | `tax.rate`, default `0.18` | Confirm applicable product/business policy; fraction between 0 and 1. | Generate an invoice and check `(rental + usage - discount) * rate` |
| `TCSMS_BILLING_DUE_DAYS` | `billing.due.days`, default `20` | Business policy, 1-365 days after issue date. | Inspect invoice due date |
| `TCSMS_REPORT_DIRECTORY` | `report.directory`, default `reports/tcsms` | Writable local directory under your control. | Admin report export creates CSV/text files |

MySQL can run locally without a paid hosting account. Hosting and recovery
delivery costs depend on your chosen provider; none is selected or provisioned
by this project. No OAuth, Firebase, Supabase, cloud storage, payment gateway or
other user's accounts are used. Payment modes are ledger labels for **externally
confirmed transactions**, not integrations that charge or settle money. Never
enter card numbers, CVVs, bank passwords or UPI PINs.

Changing the registered administrator's email/contact configuration is not the
same as configuring a delivery service. Set both recovery variables and verify
the endpoint before relying on password recovery. The endpoint accepts JSON
`recipient`, `code`, `expiresInSeconds`; it must send the code and return 2xx.
Redirects are refused; connection and request timeouts are bounded. Codes and
tokens are never written to application output. With delivery unconfigured,
Forgot Password explicitly reports that setup is required.

## Workflows and billing policy

- Login menu: Customer Login, Registration, Administrator Login, Forgot Password,
  Exit. Role menus connect to real persistent JDBC operations. Native terminal
  input masks passwords; IDE/redirected terminals display a warning if masking
  is unavailable. EOF exits cleanly.
- Registration requires all profile fields and minimum age 18, a unique email,
  canonical international mobile, username, and a complex password. Business
  identifiers/timestamps are generated. Customer accounts cannot grant themselves
  administrator privileges. Administrators can disable/re-enable customers;
  disabling revokes sessions. Customers and admins can update contact profiles.
- Each connection receives an available physical SIM/eSIM atomically. SIM/mobile
  identifiers are unique. A customer can have multiple connections, with distinct
  base plans; the same base plan cannot be selected twice by the same customer.
- Plans can be created/edited before use; historical/active contracts are immutable.
  Retire and version used plans. `voiceMinutes=-1` represents unlimited voice.
  Inactive plans cannot be selected. Both plans must permit type conversion.
  Minimum tenure applies to the current plan, including downgrades. Every plan
  and add-on change has history and actor identity.
- Billing cycles are calendar months. The base plan at the start of the month
  (or first plan for a new connection), plus add-ons active at the start, defines
  the month's immutable pricing snapshot. Midmonth changes affect billing in the
  next calendar month. There is no prorating; a first partial month has full rental.
  The console shows the subscription's selected plan immediately; monthly terms
  govern billing/allowances. These are explicit choices because the PDF supplies
  no proration/effective-date rules.
- Admin imports real usage events, each with a unique source reference. Usage is
  stored durably as PENDING, then processed by a worker. Canonical units: GB for
  data/roaming, MINUTES for voice, COUNT for SMS. Convert MB/seconds before import.
  The app validates dates, units, positivity, integer SMS quantities, activation
  and prepaid validity. Roaming requires an enabled monthly plan. Monthly base and
  add-on allowances are applied before configured overage rates. Cumulative
  rounding prevents fragmented usage from losing charges. Late usage for an
  invoiced month is rejected; no credit-note/adjustment workflow is specified.
- Bills are generated only for completed months, once per subscription/month,
  with exact decimal rental, usage, discount, tax, total, and issue-relative due
  date. Tax applies after discount. Zero-total invoices are marked paid.
  Prepaid labels/validity are supported; no wallet/recharge mechanism is specified.
- Record confirmed external payments with full bill amount, mode and unique
  transaction reference. Partial/duplicate payments are rejected. Payment,
  bill status, notification and audit commit/rollback together. Subscription
  and bill row locks plus unique constraints prevent concurrent double payment.
- Complaints require an owned subscription, category, priority and description.
  Administrators move them to IN_PROGRESS/RESOLVED, with a required resolution.
  Customers only see their own complaints, connections, bills, payments, usage,
  login history and notifications.
- Notifications are an in-application inbox. PENDING records are delivered to the
  inbox by the notification worker and can be marked READ by their owner.
- Accounts with overdue bills become OVERDUE; expired prepaid validity becomes
  EXPIRED. Administrators reactivate after overdue bills are paid. Customer
  disabling and subscription suspension are separate administrative controls.
  Contract rental continues during suspension; cancellation is outside this PDF.
- Reports: highest usage **per type** (never sum GB with minutes), customers by
  city, price-range plans, most-subscribed plans, monthly confirmed-payment revenue,
  usage by type, customers with unpaid bills, and monthly revenue per registered
  customer at month end. CSV and text exports use persisted rows; CSV values that
  could be interpreted as formulas are escaped.

## Background execution and deployment

The console starts BillingScheduler (hourly catch-up for the previous completed
month), PaymentNotificationService (2 seconds), UsageProcessor (2 seconds or queue
wakeup), and AccountMonitor (60 seconds). Database queues survive restarts. For
an unattended process:

```powershell
java -jar target/tsatms-jar-with-dependencies.jar --workers
```

Run under your normal process/service manager with environment variables and a
restricted OS identity. Use one scheduler process per deployment; mutations remain
transactional and invoice uniqueness is enforced. Worker failures are logged
without secrets and retried, and shutdown closes executors. The first run bills
the previous month; administrators can generate older missed months individually.
Back up your own database and protect exported reports as customer data. No
deployment, paid service, Git commit or push was performed by this implementation.

## Architecture

`com.amdocs.telecom.bss` contains `controller`, `service`, `model`, `validation`,
`security`, `dao`, `report`, `scheduler`, `util`, `main`. The original packages
remain the legacy OSS implementation. The main jar entry point routes to BSS by
default and to OSS with `--legacy`.

Data flows from `TelecomConsole` through permission/ownership checked services
into `BssStore` transactions. `BssStore.Tx` wraps prepared statements, result-set
mapping, generated keys, batching and savepoints. No SQL values are concatenated.
Dynamic SQL fragments are fixed program code. `Domain` provides immutable typed
request/session models; `Row` is a generic immutable query projection. The
suggested PDF classes are conceptual, not mandatory names; focused services and
normalized tables cover those entities without duplicating legacy classes.

Schema relationships: users → customers → subscriptions → usage/bills/complaints;
SIMs and plans link to subscriptions; bills → payments; subscription/add-on history
preserves changes; month terms snapshot pricing; users → sessions/recovery/login
history/notifications. Audit rows record actor/action/entity without secrets.
Foreign keys, business-key unique constraints, checks and indexes protect integrity.

Patterns include DAO (`BssStore` JDBC boundary), Strategy and Factory (report
writers), plus the reused Singleton configuration and abstract/base utility
infrastructure. Streams demonstrate Predicate, Function, Consumer, Supplier,
Comparator, Optional, groupingBy and summarizingDouble; money uses BigDecimal.

Authentication reuses PBKDF2 hashing/policy and one-use CAPTCHA. Three failed
attempts cause a 15-minute lock; sessions expire after 8 hours and are stored as
hashes. OTPs expire after 5 minutes with three guesses and a one-minute resend
cooldown; only hashed codes are persisted. Recovery revokes all sessions. Local
secret files, generated logs/reports/builds are ignored; shared resources contain
no real credentials. Legacy SQL script errors no longer include SQL text that
could contain provisioning secrets.

## Verification

```powershell
mvn test
mvn package
java -jar target/tsatms-jar-with-dependencies.jar --help
# Optional: full JDBC contract on a disposable local MySQL server, not your service
python scripts/test_mysql.py
# Also check the already-built executable JAR against the disposable server:
python scripts/test_mysql.py --smoke-jar
# If MySQL binaries are not on PATH:
python scripts/test_mysql.py --mysql-bin 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
```

The default tests use real SQL against isolated H2 databases in MySQL mode;
H2/JUnit are test-only and absent from the runtime jar. The Python helper creates
a separate MySQL data directory, binds a fresh local port, assigns a random test
password without displaying it, runs the same contract, and stops its own process.
Diagnostics remain under ignored `target/mysql-test-*`. It never uses the
installed MySQL service or the credentials in `tcsms.properties`. Tests create
synthetic customers/usage only in these isolated databases.

See [IMPLEMENTATION.md](IMPLEMENTATION.md) for requirement coverage, actual test
results and remaining verification limits. Legacy harnesses require their own
explicitly authorized database; they are not run against an unknown installation.
