# Telecom Customer and Subscription Management System

TCSMS is a Java console application that manages the telecom customer journey: registration, SIM activation, plan subscriptions, usage, monthly billing, payment recording and customer support. Customers and administrators have separate workflows backed by a persistent MySQL database.

The application runs in a terminal. No browser, frontend server or JavaScript package manager is required. The repository also retains an older service-assurance and trouble-ticket application, accessible through an explicit legacy command.

## Contents

- [Features](#features)
- [Technology and dependencies](#technology-and-dependencies)
- [Installation and startup](#installation-and-startup)
- [Configuration](#configuration)
- [Using the application](#using-the-application)
- [How it works](#how-it-works)
- [Project structure](#project-structure)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Legacy application](#legacy-application)
- [Further documentation](#further-documentation)

## Features

| Area | Capabilities |
|---|---|
| Authentication | Password login, single-use text CAPTCHA, account lockout, sessions, login history and OTP recovery |
| Customers | Registration, profile updates, account activation and deactivation |
| Plans | Administrator maintenance, search, price/data filters, sorting and comparison |
| SIMs and connections | Physical SIM/eSIM inventory, unique mobile numbers and multiple customer connections |
| Subscriptions | Plan upgrades/downgrades, permitted prepaid/postpaid changes, add-ons and change history |
| Usage | Persistent voice, SMS, data and roaming events, monthly allowances and calculated overage charges |
| Billing | Monthly invoices, rental, usage charges, discounts, tax and due dates |
| Payments | Confirmed external-payment records, full-amount validation and duplicate prevention |
| Support | Customer complaints, administrator resolution and an application notification inbox |
| Reports | Eight analytics reports, each exportable to CSV and text |
| Background processing | Billing, usage processing, notification delivery and overdue/expiry monitoring |

Services enforce roles and customer ownership independently of the menus. Passwords use salted PBKDF2 hashing, SQL values are bound through prepared statements, and financial operations use JDBC transactions and database constraints.

## Technology and dependencies

| Component | Version / requirement | Purpose |
|---|---|---|
| JDK | 25 | Compilation and application runtime |
| Apache Maven | 3.9+; developed with 3.9.11 | Dependency resolution, compilation, tests and packaging |
| MySQL Server | 8.0.16+ | Persistent database with enforced check constraints |
| MySQL Connector/J | 8.0.30 | Runtime JDBC driver |
| JUnit | 4.13.2, test scope | Automated tests |
| H2 | 2.2.224, test scope | Isolated relational integration tests |
| Python | 3, optional | Disposable MySQL tests and credential-audit scripts |

The Java standard library provides streams, collections, concurrency, HTTP delivery, cryptography, file handling and logging. The code demonstrates Java 8 APIs, but the project targets Java release 25.

Maven installs the dependencies declared in [pom.xml](pom.xml). The first build requires access to Maven Central. Project settings are selected through `.mvn/maven.config` and [maven-settings.xml](maven-settings.xml), with a local cache at `${user.home}/.m2/repository-tsatms`. Once dependencies and plugins are cached, an offline build can use `mvn -o package`.

## Installation and startup

Run the following commands from the repository root. Examples use PowerShell and a local MySQL installation.

### 1. Check prerequisites

```powershell
java -version
mvn -version
mysql --version
```

Maven must use JDK 25 or newer. Start a MySQL server you own before configuring the application.

### 2. Create the database and account

Connect using your local MySQL administrator account:

```powershell
mysql -h localhost -u root -p
```

If the Windows client is not on `PATH`, use its installation path:

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -h localhost -u root -p
```

Run the following SQL, replacing the password placeholder before execution:

```sql
CREATE DATABASE tcsms
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'tcsms_app'@'localhost'
  IDENTIFIED BY 'REPLACE_WITH_YOUR_OWN_STRONG_PASSWORD';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, INDEX, REFERENCES
  ON tcsms.* TO 'tcsms_app'@'localhost';

EXIT;
```

These grants are for local development. Remote deployments require an account host appropriate to the application server. If these database or account names already exist, check ownership and purpose before reusing them.

The application does not create the MySQL database or account. After schema installation, a separate runtime identity can operate with SELECT, INSERT, UPDATE and DELETE privileges on the application schema.

### 3. Create local configuration

Copy the template only if a local configuration does not already exist:

```powershell
if (!(Test-Path tcsms.properties)) {
    Copy-Item tcsms.properties.example tcsms.properties
}
```

Edit `tcsms.properties`. A local development connection without TLS can use:

```properties
db.url=jdbc:mysql://localhost:3306/tcsms?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Kolkata
db.user=tcsms_app
db.password=REPLACE_WITH_YOUR_OWN_STRONG_PASSWORD
timezone=Asia/Kolkata
tax.rate=0.18
billing.due.days=20
report.directory=reports/tcsms
```

Use the same password assigned to the MySQL application account. Do not surround property values with quotation marks. Java properties interpret backslashes as escapes; double a literal backslash or supply the password through an environment variable.

The disabled-TLS URL is for local development only. Use verified TLS, such as `sslMode=VERIFY_IDENTITY`, and a trusted server certificate for remote connections. The supplied template defaults to verified TLS.

The file is ignored by Git and loaded from the process working directory. Configuration changes do not require rebuilding. Keep personal credentials out of shared resource files.

### 4. Build, initialize and check

```powershell
mvn package
java -jar target/tsatms-jar-with-dependencies.jar --init
java -jar target/tsatms-jar-with-dependencies.jar --check
```

The build runs the default tests and generates:

| Artifact | Use |
|---|---|
| `target/tsatms-jar-with-dependencies.jar` | Runnable application with its JDBC dependency included |
| `target/tsatms.jar` | Application classes/resources; runtime dependencies must be supplied separately |

Artifact names retain the repository's original naming for compatibility.

Initialization installs the separate `bss_*` tables and indexes. It is additive and repeatable; it does not drop tables or seed production records. MySQL DDL implicitly commits, so a failure may leave some schema objects installed. Correct the problem and rerun `--init`. Run one initializer at a time.

A successful check prints:

```text
BSS database reachable; schema version 1.
```

### 5. Create your administrator and start

```powershell
java -jar target/tsatms-jar-with-dependencies.jar --create-admin
java -jar target/tsatms-jar-with-dependencies.jar
```

Administrator creation prompts for your application username, email and password. It only works when no administrator exists. These credentials are separate from the MySQL administrator and database-account credentials.

There are no default login accounts or preloaded production plans, SIMs, customers or usage records. Empty lists are expected on first startup.

## Configuration

Environment variables take precedence over matching keys in local `tcsms.properties`. The application does not automatically read `.env` files. All configuration belongs to the local application process.

| Property | Environment variable | Default / requirement |
|---|---|---|
| `db.url` | `TCSMS_DB_URL` | Required MySQL JDBC URL containing the schema name |
| `db.user` | `TCSMS_DB_USER` | Required dedicated database account |
| `db.password` | `TCSMS_DB_PASSWORD` | Required database password |
| `timezone` | `TCSMS_TIMEZONE` | `Asia/Kolkata`; align with the JDBC timezone |
| `tax.rate` | `TCSMS_TAX_RATE` | `0.18`; fraction between 0 and 1 |
| `billing.due.days` | `TCSMS_BILLING_DUE_DAYS` | `20`; permitted range 1-365 days after invoice issue |
| `report.directory` | `TCSMS_REPORT_DIRECTORY` | `reports/tcsms`; must be writable |
| `recovery.url` | `TCSMS_RECOVERY_URL` | HTTPS delivery endpoint; required for password recovery |
| `recovery.token` | `TCSMS_RECOVERY_TOKEN` | Bearer token for the delivery endpoint |

Obtain database values from your own MySQL installation or hosting administrator. Recovery settings come from an HTTPS delivery service you operate or configure. Core workflows work without that endpoint; password recovery requires it.

### Password recovery integration

The recovery adapter sends an HTTPS POST authenticated with `Authorization: Bearer <configured-token>`. Its JSON payload has this shape:

```json
{
  "recipient": "registered-address@example.com",
  "code": "<generated-one-time-code>",
  "expiresInSeconds": 300
}
```

The endpoint must deliver the code to the registered recipient and return a successful 2xx response. Redirects are refused and timeouts are bounded. Live delivery needs separate verification. The application does not print codes in place of delivering them.

## Using the application

The opening menu offers Customer Login, New Customer Registration, Administrator Login, Forgot Password and Exit.

A typical first-use sequence is:

1. Sign in as the administrator and create an active base plan.
2. Add available physical SIM/eSIM inventory with unique SIM and mobile numbers.
3. Log out and register a customer through the opening menu.
4. Sign in as the customer and subscribe using an available SIM type.
5. Import actual usage events through the administrator dashboard.
6. Review processed usage and generate an invoice after the billing month closes.
7. Record a confirmed external payment against the full outstanding invoice amount.
8. Submit and resolve complaints, inspect notifications and export reports.

Use a native terminal for masked password input. If masking is unavailable, the console displays a warning. Logout revokes the session; end-of-input exits the interactive console.

### Commands

Use one current-application command at a time:

| Command | Behavior |
|---|---|
| No argument, or `--console` | Check the schema, start workers and open the console |
| `--init` | Install the additive BSS schema and indexes |
| `--check` | Check connectivity and the schema-version marker |
| `--create-admin` | Create the first administrator interactively |
| `--workers` | Run background processes without interactive menus |
| `--help` | Print commands without requiring a database |

For unattended background processing:

```powershell
java -jar target/tsatms-jar-with-dependencies.jar --workers
```

Use a process manager and a restricted operating-system account for deployment. If starting from another working directory, provide environment variables or place `tcsms.properties` in that directory. Ctrl+C stops an unattended process.

## How it works

### Architecture and persistence

```text
Terminal input
    -> TelecomConsole
    -> Authentication and domain services
    -> Role, ownership and business-rule validation
    -> BssStore JDBC transaction
    -> MySQL tables, histories, notifications and audit records
```

The console handles input and presentation. Services enforce business rules and permissions. `BssStore` centralizes prepared statements, row mapping, generated keys, batching, savepoints and transaction boundaries. A failed transaction rolls back its changes.

The schema contains 19 BSS tables. Users link to customer profiles; customers own subscriptions; subscriptions reference SIMs and plans and have usage, invoices and complaints. Payments reference invoices. Supporting tables store plan/add-on history, monthly terms, sessions, recovery codes, login history, notifications, audits and schema metadata.

Foreign keys, uniqueness rules, check constraints and indexes protect relationships and identifiers. Immutable domain requests and generic row projections separate console inputs from JDBC results. DAO, Factory and Strategy patterns organize data access and report output.

### Business rules

- Registration requires age 18 or older, mandatory profile fields, unique username/email/contact mobile, and a password satisfying the password policy.
- Customers may have multiple connections with distinct base plans. Duplicate selection of the same base plan for the same customer is rejected.
- Inactive plans cannot be selected. Current-plan minimum tenure and mutual type-conversion permission restrict changes. Historical plans are retired and versioned rather than repriced.
- Billing follows calendar months. The start-of-month base plan, or first plan for a new connection, plus add-ons active at the start defines the monthly pricing snapshot.
- Midmonth selections affect billing in the next calendar month. Opened snapshots remain fixed. There is no prorating; a first partial month has full rental.
- Usage requires a unique source reference and canonical units: GB for data/roaming, MINUTES for voice and COUNT for SMS. Monthly allowances apply before overage rates.
- Invoices are generated only for completed months, once per connection/month. Money uses `BigDecimal`, and tax applies after discount. Late usage for an invoiced month is rejected.
- A payment must match the full outstanding amount and have a unique confirmation reference. Payment, bill status, notification and audit commit together. Subscription/bill locks prevent concurrent double payment.
- Overdue subscriptions and expired prepaid validity are monitored automatically. Administrators can reactivate subscriptions after overdue bills are paid.

Payment modes describe externally confirmed transactions. The application does not initiate UPI transfers, charge cards or settle funds. It does not implement prepaid wallets, prorating, credit notes or cancellation. Contract rental continues during subscription suspension.

### Background processes

| Worker | Schedule | Responsibility |
|---|---|---|
| BillingScheduler | At startup, then hourly | Generate missing invoices for the previous completed month |
| PaymentNotificationService | At startup, then every 2 seconds | Deliver pending records to the application inbox |
| UsageProcessor | Queue wakeup or approximately every 2 seconds | Process durably stored pending usage |
| AccountMonitor | At startup, then every 60 seconds | Identify overdue subscriptions and expired prepaid validity |

Executors run the workers, and a bounded blocking queue supplies usage wakeups. Database records survive restarts and remain authoritative. Failures are logged, pending work can be retried, and shutdown closes executors. Run one scheduler process per deployment. Administrators can generate older missed billing months individually.

### Reports

Reports cover highest usage by customer and usage type, customers by city, price-range plans, most-subscribed plans, monthly confirmed-payment revenue, usage by type, customers with unpaid bills, and monthly revenue per registered customer.

Each exports to CSV and text in the configured directory. Quantities remain grouped by type/unit, and financial totals use decimal arithmetic. CSV output escapes cells that could be interpreted as spreadsheet formulas. Restrict access to exports because they contain customer information.

### Authentication

Three failed attempts cause a 15-minute lock. Sessions expire after eight hours and are stored as token hashes. Recovery codes expire after five minutes, allow three guesses and have a one-minute resend cooldown. Successful recovery revokes existing sessions. Customer reads and writes are scoped to owned records, and administrator actions require the database-backed administrator role.

## Project structure

```text
.
|-- pom.xml                         Dependencies, tests and packaging
|-- maven-settings.xml              Project-scoped Maven settings
|-- tcsms.properties.example        Safe configuration template
|-- docs/
|   |-- SETUP.md                    Detailed setup and architecture
|   `-- IMPLEMENTATION.md           Requirements and verification record
|-- scripts/
|   |-- test_mysql.py               Disposable MySQL and packaged-JAR checks
|   `-- audit_configuration.py      Redacted credential-pattern audit
|-- src/main/java/com/amdocs/telecom/
|   |-- bss/
|   |   |-- controller/             Console menus
|   |   |-- service/                Authentication and domain operations
|   |   |-- dao/                    JDBC access and schema installation
|   |   |-- model/                  Domain requests and sessions
|   |   |-- validation/             Shared input rules
|   |   |-- security/               HTTPS recovery delivery
|   |   |-- scheduler/              Background workers
|   |   |-- report/                 Analytics and export writers
|   |   |-- util/                   Local/environment configuration
|   |   `-- main/                   Current application entry point
|   `-- ...                         Shared utilities and legacy OSS
|-- src/main/resources/
|   |-- bss/                        Schema, index reference and SQL examples
|   `-- db/                         Separate legacy database scripts
`-- src/test/java/com/amdocs/telecom/bss/
    |-- BssIntegrationTest.java
    |-- BssDatabaseDiagnosticsTest.java
    `-- BssMySqlIT.java
```

Local properties, generated reports, logs and build output are ignored by Git. Keep real credentials out of shared templates and source files.

## Testing

### Default suite

```powershell
mvn test
```

The default suite contains 29 JDBC/console integration tests and two database-diagnostic tests. Integration tests use isolated H2 databases in MySQL compatibility mode and synthetic fixtures, without personal database credentials.

Coverage includes registration, authentication, ownership, subscriptions, usage, invoices, payment rollback/concurrency, complaints, background processing, all eight report outputs and console workflows. H2 and JUnit are test-only and are excluded from the runtime JAR.

### MySQL and packaged startup checks

With Python and MySQL server/client tools installed:

```powershell
python scripts/test_mysql.py
```

To check the built executable as well:

```powershell
mvn package
python scripts/test_mysql.py --smoke-jar
```

If MySQL tools are not on `PATH`:

```powershell
python scripts/test_mysql.py --mysql-bin "C:\Program Files\MySQL\MySQL Server 8.0\bin" --smoke-jar
```

The helper creates a separate data directory under `target`, binds a fresh loopback port, generates random credentials, runs the 29-test MySQL contract and stops its server. It does not use the installed service or `tcsms.properties`. Optional JAR checks cover schema installation, connectivity, administrator provisioning and console startup. Diagnostics remain under `target/mysql-test-*`.

### Additional checks

```powershell
java -jar target/tsatms-jar-with-dependencies.jar --help
python scripts/audit_configuration.py
git diff --check
```

Credential scanning reports candidate locations without revealing values. Findings require review; this heuristic does not prove that no secret has ever been exposed.

Live OTP delivery, production deployment and million-customer load performance need separate verification. Stream-based analytics currently materialize their input in memory.

## Troubleshooting

| Symptom | Check or action |
|---|---|
| MySQL error `1045`, SQL state `28000` | Login was rejected. Verify the database username/password, environment overrides and account host grants. |
| MySQL error `1049` | Create the database named in the JDBC URL or correct that name. |
| MySQL error `1044`, `1142`, `1143` or `1227` | Ask the database administrator to grant the required schema permissions. |
| MySQL error `1146` | Required tables are missing. Check the selected database and run `--init`. |
| Connection or TLS failure | Check the MySQL service, host, port, firewall and TLS/certificate settings. |
| Missing required property | Check the working directory, local configuration and matching environment variables. |
| Recovery delivery unavailable | Configure both recovery settings and verify the HTTPS endpoint. |
| Empty lists or reports | Populate the relevant plans, SIMs, customers and transactions; no production demonstration data is seeded. |
| Administrator already exists | Use the existing account or configured recovery. Provisioning does not reset an existing account. |
| Password input visible | Use a native terminal that supports masked input. |
| Unsupported Java release | Check `JAVA_HOME` and `mvn -version`; Maven must use JDK 25 or newer. |

For error 1045, test the same database account independently:

```powershell
mysql -h localhost -u tcsms_app -p -D tcsms
```

A MySQL administrator can reset that account with `ALTER USER`; update the local application password to match afterward. Do not place passwords in command-line arguments or share them in troubleshooting output.

Maven may print a `sun.misc.Unsafe` deprecation warning from its bundled Guice library on JDK 25. Check the final build result and test summary. A successful build does not verify personal database configuration.

## Legacy application

TSATMS is the retained service-assurance and trouble-ticket OSS. It includes incident assignment, SLA monitoring, escalation and legacy reports, with separate roles, configuration and schema scripts.

```powershell
java -jar target/tsatms-jar-with-dependencies.jar --legacy --help
```

All legacy switches require the `--legacy` prefix. Legacy schema scripts can drop and recreate tables; do not use them to initialize TCSMS or run them against data you need to preserve. The current BSS reads `tcsms.properties` and does not fall back to legacy database credentials.

Retained legacy details are in [TRACEABILITY.md](TRACEABILITY.md) and [INFORMATION.md](INFORMATION.md). Current product coverage is documented separately.

## Further documentation

- [Detailed setup and architecture](docs/SETUP.md): configuration, integrations, billing decisions and deployment guidance.
- [Implementation and requirements report](docs/IMPLEMENTATION.md): original gaps, requirement mapping, verification and limitations.
- [Configuration template](tcsms.properties.example): supported settings with safe placeholders.
- [BSS schema](src/main/resources/bss/schema.sql): tables and constraints.
- [SQL reference](src/main/resources/bss/sample_queries.sql): joins, grouping, subqueries and reporting aggregates.
