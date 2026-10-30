package com.amdocs.telecom.main;

import com.amdocs.telecom.controller.ConsoleVerification;
import com.amdocs.telecom.controller.MainMenu;
import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dto.SlaComplianceDTO;
import com.amdocs.telecom.exception.TSATMSException;
import com.amdocs.telecom.security.SecurityVerification;
import com.amdocs.telecom.report.ReportExporter;
import com.amdocs.telecom.report.ReportFormat;
import com.amdocs.telecom.report.ReportKind;
import com.amdocs.telecom.report.ReportTable;
import com.amdocs.telecom.service.ReportService;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.impl.AuthenticationServiceImpl;
import com.amdocs.telecom.service.impl.EngineerAssignmentServiceImpl;
import com.amdocs.telecom.service.impl.EscalationServiceImpl;
import com.amdocs.telecom.service.impl.NotificationServiceImpl;
import com.amdocs.telecom.service.impl.ReportServiceImpl;
import com.amdocs.telecom.service.impl.SlaServiceImpl;
import com.amdocs.telecom.service.impl.TicketServiceImpl;
import com.amdocs.telecom.service.sla.SlaEvaluation;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.util.AssignmentVerification;
import com.amdocs.telecom.util.ConfigLoader;
import com.amdocs.telecom.util.ConsoleReader;
import com.amdocs.telecom.util.DBConnection;
import com.amdocs.telecom.util.EscalationVerification;
import com.amdocs.telecom.util.DaoVerification;
import com.amdocs.telecom.util.DatabaseBootstrap;
import com.amdocs.telecom.util.EventVerification;
import com.amdocs.telecom.util.ReportVerification;
import com.amdocs.telecom.util.SecurityBootstrap;
import com.amdocs.telecom.util.SlaVerification;
import com.amdocs.telecom.util.ThreadVerification;
import com.amdocs.telecom.util.TicketVerification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Entry point for the Telecom Service Assurance and Trouble Ticket Management
 * System.
 *
 * <p>Run with no arguments it performs its startup checks and, if they
 * pass, opens section 2's sign in screen. The switches build the database,
 * provision the seeded accounts, walk one incident through the whole
 * system, or run one of the verification harnesses. {@code --help} lists
 * them all.</p>
 *
 * <p>The startup checks stay in front of the menu on purpose: a dashboard
 * that cannot reach the database is a dashboard whose every option fails
 * one at a time, and saying so once with the switch that fixes it is more
 * use than eighteen identical stack traces.</p>
 */
public final class TSATMSApplication {

    private TSATMSApplication() {
        throw new AssertionError("TSATMSApplication is not instantiable");
    }

    /** Command line switch that builds the database from the bundled scripts. */
    private static final String SETUP_DATABASE_FLAG = "--setup-db";

    /** Command line switch that exercises the views, functions and procedures. */
    private static final String VERIFY_DATABASE_FLAG = "--verify-db";

    /** Command line switch that exercises the data access layer end to end. */
    private static final String VERIFY_DAO_FLAG = "--verify-dao";

    /** Command line switch that gives the seeded accounts a real password. */
    private static final String PROVISION_USERS_FLAG = "--provision-users";

    /** Command line switch that exercises authentication and authorisation. */
    private static final String VERIFY_SECURITY_FLAG = "--verify-security";

    /** Command line switch that exercises the SLA engine end to end. */
    private static final String VERIFY_SLA_FLAG = "--verify-sla";

    /** Command line switch that prints the SLA board. */
    private static final String SLA_BOARD_FLAG = "--sla";

    /** Command line switch that exercises the ticket lifecycle end to end. */
    private static final String VERIFY_TICKET_FLAG = "--verify-ticket";

    /** Command line switch that exercises notifications and the audit trail. */
    private static final String VERIFY_EVENTS_FLAG = "--verify-events";

    /** Command line switch that exercises recommendation and assignment. */
    private static final String VERIFY_ASSIGN_FLAG = "--verify-assign";

    /** Command line switch that exercises the escalation ladder. */
    private static final String VERIFY_ESCALATION_FLAG = "--verify-escalation";

    /** Command line switch that exercises the background workers. */
    private static final String VERIFY_THREADS_FLAG = "--verify-threads";

    /** Command line switch that exercises the analytics and the reports. */
    private static final String VERIFY_REPORTS_FLAG = "--verify-reports";

    /** Command line switch that prints the seven reports and exports them. */
    private static final String REPORTS_FLAG = "--reports";

    /**
     * Command line switch that rebuilds the functions, triggers, views and
     * procedures without touching a row.
     */
    private static final String REFRESH_LOGIC_FLAG = "--refresh-db-logic";

    /** Command line switch that drives the dashboards from scripted input. */
    private static final String VERIFY_CONSOLE_FLAG = "--verify-console";

    /** Command line switch that walks one incident through the whole system. */
    private static final String DEMO_FLAG = "--demo";

    /** Command line switch that opens the interactive sign in screen. */
    private static final String LOGIN_FLAG = "--login";

    /** Command line switch that runs the startup checks and stops. */
    private static final String CHECK_FLAG = "--check";

    /** Command line switch that lists every other switch. */
    private static final String HELP_FLAG = "--help";

    public static void main(String[] args) {
        if (args.length == 0 || !"--legacy".equals(args[0])) {
            com.amdocs.telecom.bss.main.TcsmsApplication.main(args);
            return;
        }
        args = java.util.Arrays.copyOfRange(args, 1, args.length);
        int exitCode;
        try {
            if (hasFlag(args, HELP_FLAG)) {
                exitCode = printUsage();
            } else if (hasFlag(args, SETUP_DATABASE_FLAG)) {
                exitCode = buildDatabase();
            } else if (hasFlag(args, VERIFY_DATABASE_FLAG)) {
                exitCode = verifyDatabase();
            } else if (hasFlag(args, VERIFY_DAO_FLAG)) {
                exitCode = verifyDataAccessLayer();
            } else if (hasFlag(args, PROVISION_USERS_FLAG)) {
                exitCode = provisionUsers();
            } else if (hasFlag(args, VERIFY_SECURITY_FLAG)) {
                exitCode = verifySecurityLayer();
            } else if (hasFlag(args, VERIFY_SLA_FLAG)) {
                exitCode = verifySlaEngine();
            } else if (hasFlag(args, SLA_BOARD_FLAG)) {
                exitCode = showSlaBoard();
            } else if (hasFlag(args, VERIFY_TICKET_FLAG)) {
                exitCode = verifyTicketLifecycle();
            } else if (hasFlag(args, VERIFY_EVENTS_FLAG)) {
                exitCode = verifyEvents();
            } else if (hasFlag(args, VERIFY_ASSIGN_FLAG)) {
                exitCode = verifyAssignment();
            } else if (hasFlag(args, VERIFY_ESCALATION_FLAG)) {
                exitCode = verifyEscalation();
            } else if (hasFlag(args, VERIFY_THREADS_FLAG)) {
                exitCode = verifyThreads();
            } else if (hasFlag(args, VERIFY_REPORTS_FLAG)) {
                exitCode = verifyReports();
            } else if (hasFlag(args, VERIFY_CONSOLE_FLAG)) {
                exitCode = ConsoleVerification.execute();
            } else if (hasFlag(args, DEMO_FLAG)) {
                exitCode = runDemo();
            } else if (hasFlag(args, REPORTS_FLAG)) {
                exitCode = showReports();
            } else if (hasFlag(args, REFRESH_LOGIC_FLAG)) {
                exitCode = refreshDatabaseLogic();
            } else if (hasFlag(args, LOGIN_FLAG)) {
                exitCode = openSignIn();
            } else if (hasFlag(args, CHECK_FLAG)) {
                exitCode = runStartupChecks();
            } else {
                exitCode = runApplication();
            }
        } catch (TSATMSException failure) {
            System.out.println();
            System.out.println("  Startup failed.");
            System.out.println("  " + failure.toDisplayString());
            AppLogger.error(TSATMSApplication.class, "Startup failed", failure);
            exitCode = 2;
        } catch (RuntimeException failure) {
            System.out.println();
            System.out.println("  Startup failed unexpectedly: " + failure);
            AppLogger.error(TSATMSApplication.class, "Unexpected startup failure", failure);
            exitCode = 3;
        } finally {
            AppLogger.shutdown();
        }
        System.exit(exitCode);
    }

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String argument : args) {
            if (flag.equalsIgnoreCase(argument)) {
                return true;
            }
        }
        return false;
    }

    private static int verifyDatabase() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        printBanner(config);
        return DatabaseBootstrap.verifyContent();
    }

    private static int verifyDataAccessLayer() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "Data access layer verification requested");
        printBanner(config);
        return DaoVerification.execute();
    }

    private static int provisionUsers() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "Account provisioning requested");
        printBanner(config);
        return SecurityBootstrap.provisionSeededAccounts();
    }

    private static int verifySecurityLayer() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "Security layer verification requested");
        printBanner(config);
        return SecurityVerification.execute();
    }

    private static int verifySlaEngine() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "SLA engine verification requested");
        printBanner(config);
        return SlaVerification.execute();
    }

    private static int verifyTicketLifecycle() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "Ticket lifecycle verification requested");
        printBanner(config);
        return TicketVerification.execute();
    }

    private static int verifyEvents() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "Notification and audit verification requested");
        printBanner(config);
        return EventVerification.execute();
    }

    private static int verifyAssignment() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "Engineer assignment verification requested");
        printBanner(config);
        return AssignmentVerification.execute();
    }

    private static int verifyEscalation() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "Escalation verification requested");
        printBanner(config);
        return EscalationVerification.execute();
    }

    private static int verifyThreads() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "Thread verification requested");
        printBanner(config);
        return ThreadVerification.execute();
    }

    private static int verifyReports() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "Analytics and report verification requested");
        printBanner(config);
        return ReportVerification.execute();
    }

    /**
     * Prints all seven reports and writes each one out as CSV and as text.
     *
     * <p>Read only, and the manager dashboard of a later phase will offer
     * the same seven from a menu. Every report is built from a single
     * snapshot, so the pack describes one instant rather than seven.</p>
     */
    private static int showReports() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        printBanner(config);

        ReportService reports = new ReportServiceImpl();
        Map<ReportKind, ReportTable> pack = reports.generateAll(null, null);

        for (ReportKind kind : ReportKind.values()) {
            System.out.println(reports.preview(pack.get(kind), ReportFormat.TEXT));
            System.out.println();
        }

        System.out.println("  Exported");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        for (ReportKind kind : ReportKind.values()) {
            for (Path file : new ReportExporter().exportAll(pack.get(kind),
                    ReportFormat.CSV, ReportFormat.TEXT)) {
                System.out.println("  " + file);
            }
        }
        System.out.println();
        return 0;
    }

    /**
     * Brings the database's programmable objects up to date after a script
     * changes, without the full rebuild that would discard the provisioned
     * passwords along with the rest of the data.
     */
    private static int refreshDatabaseLogic() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "Database logic refresh requested");
        printBanner(config);
        return DatabaseBootstrap.refreshProgrammableObjects();
    }

    /**
     * Prints the SLA board: the configured bands, compliance so far, and
     * whatever is currently at risk or overdue.
     *
     * <p>Read only, and the manager dashboard of a later phase will show the
     * same figures inside the menus.</p>
     */
    private static int showSlaBoard() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        printBanner(config);

        SlaService sla = new SlaServiceImpl();

        System.out.println("  SLA bands (section 8)");
        System.out.println(sla.describeBands());
        System.out.println();

        System.out.println("  Compliance by band");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println(String.format("  %-10s %6s %8s %6s %9s %10s %10s",
                "Priority", "Total", "Done", "Met", "Breached", "Compliance", "Avg hours"));
        for (SlaComplianceDTO row : sla.compliance()) {
            System.out.println("  " + row.toSummaryLine());
        }
        System.out.println();

        List<SlaEvaluation> attention = sla.needingAttention();
        System.out.println("  Needing attention now (" + attention.size() + ")");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        if (attention.isEmpty()) {
            System.out.println("  Nothing is at risk or overdue.");
        } else {
            System.out.println("  " + SlaEvaluation.summaryHeading());
            for (SlaEvaluation evaluation : attention) {
                System.out.println("  " + evaluation.toSummaryLine());
            }
        }
        System.out.println();
        return 0;
    }

    /**
     * Lists the switches, grouped by what somebody would be trying to do.
     */
    private static int printUsage() {
        System.out.println();
        System.out.println("  java -jar tsatms-jar-with-dependencies.jar [switch]");
        System.out.println();
        System.out.println("  With no switch: run the startup checks, then open the sign in screen.");
        System.out.println();

        System.out.println("  Setting up");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        usage(SETUP_DATABASE_FLAG, "build the schema, logic and seed data from scratch");
        usage(REFRESH_LOGIC_FLAG, "reload only the functions, views, procedures and triggers");
        usage(PROVISION_USERS_FLAG, "give the seeded accounts their password");
        usage(CHECK_FLAG, "run the startup checks and stop");
        System.out.println();

        System.out.println("  Using it");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        usage(LOGIN_FLAG, "open the sign in screen without the startup checks");
        usage(DEMO_FLAG, "walk one incident through the whole system, then roll it back");
        usage(SLA_BOARD_FLAG, "print the SLA board");
        usage(REPORTS_FLAG, "build all seven reports and export them to CSV and text");
        System.out.println();

        System.out.println("  Proving it works");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        usage(VERIFY_DATABASE_FLAG, "views, functions and procedures return data");
        usage(VERIFY_DAO_FLAG, "the data access layer");
        usage(VERIFY_SECURITY_FLAG, "CAPTCHA, hashing, OTP, lockout, role based access");
        usage(VERIFY_SLA_FLAG, "the SLA engine and its clocks");
        usage(VERIFY_TICKET_FLAG, "the ticket lifecycle");
        usage(VERIFY_EVENTS_FLAG, "notifications and the audit trail");
        usage(VERIFY_ASSIGN_FLAG, "engineer recommendation and assignment");
        usage(VERIFY_ESCALATION_FLAG, "the escalation ladder");
        usage(VERIFY_THREADS_FLAG, "the background workers");
        usage(VERIFY_REPORTS_FLAG, "the Stream analytics and the reports");
        usage(VERIFY_CONSOLE_FLAG, "the dashboards, driven from scripted input");
        System.out.println();
        return 0;
    }

    private static void usage(String flag, String description) {
        System.out.println(String.format("    %-22s %s", flag, description));
    }

    private static int runDemo() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "End to end demonstration requested");
        printBanner(config);
        return DemoRunner.execute();
    }

    /**
     * The application proper: check that the system is fit to run, then
     * open section 2's front screen.
     *
     * <p>The checks come first because a menu that cannot reach the
     * database is a menu whose every option fails one at a time. Better to
     * say so once, with the switch that fixes it.</p>
     */
    private static int runApplication() {
        int checks = runStartupChecks();
        if (checks != 0) {
            return checks;
        }
        // The banner is already on the screen from the checks above.
        return openMainMenu();
    }

    /**
     * Opens section 2's sign in screen on its own, for when the state of
     * the system is already known and the checks are not wanted.
     */
    private static int openSignIn() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        printBanner(config);
        return openMainMenu();
    }

    /**
     * Section 2's front screen, from which every dashboard is reached.
     */
    private static int openMainMenu() {
        DAOFactory factory = DAOFactory.getInstance();
        new MainMenu(new ConsoleReader(),
                new AuthenticationServiceImpl(),
                new TicketServiceImpl(),
                new EngineerAssignmentServiceImpl(),
                new EscalationServiceImpl(),
                new SlaServiceImpl(),
                new NotificationServiceImpl(),
                new ReportServiceImpl(),
                factory).run();
        return 0;
    }

    private static int buildDatabase() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "Database build requested");

        printBanner(config);

        if (config.isPlaceholder("db.admin.password")) {
            System.out.println("  Cannot build the database: db.admin.password is still set to");
            System.out.println("  the CHANGE_ME placeholder. Set it in application.properties to");
            System.out.println("  the MySQL root password chosen during installation.");
            System.out.println();
            return 1;
        }

        return DatabaseBootstrap.execute();
    }

    private static int runStartupChecks() {
        ConfigLoader config = ConfigLoader.getInstance();
        AppLogger.initialize();
        AppLogger.info(TSATMSApplication.class, "TSATMS starting up");

        printBanner(config);

        List<Check> checks = new ArrayList<>();
        checks.add(configurationCheck(config));
        checks.add(loggingCheck(config));
        checks.addAll(databaseChecks(config));

        System.out.println("  Startup checks");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        for (Check check : checks) {
            check.print();
        }
        System.out.println("  " + AppConstants.LINE_SINGLE);

        return printSummary(checks);
    }

    private static void printBanner(ConfigLoader config) {
        String name = config.getString("app.name", "Telecom Service Assurance System");
        String version = config.getString("app.version", "1.0.0");
        String environment = config.getString("app.environment", "DEV");

        System.out.println();
        System.out.println("  " + AppConstants.LINE_DOUBLE);
        System.out.println("  " + centre(name.toUpperCase()));
        System.out.println("  " + centre("Version " + version + "   |   Environment " + environment));
        System.out.println("  " + AppConstants.LINE_DOUBLE);
        System.out.println();
    }

    private static Check configurationCheck(ConfigLoader config) {
        return Check.pass("Configuration",
                config.keys().size() + " properties from " + config.getSourceDescription());
    }

    private static Check loggingCheck(ConfigLoader config) {
        String warning = AppLogger.getInitialisationWarning();
        if (warning != null) {
            return Check.fail("Logging", warning, null);
        }
        String detail = AppLogger.getLogFilePath()
                + "  (file=" + config.getString("log.level", "INFO")
                + ", console=" + config.getString("log.console.level", "WARNING") + ")";
        return Check.pass("Logging", detail);
    }

    private static List<Check> databaseChecks(ConfigLoader config) {
        List<Check> checks = new ArrayList<>();

        DBConnection database;
        try {
            database = DBConnection.getInstance();
        } catch (TSATMSException failure) {
            checks.add(Check.fail("JDBC driver", failure.getMessage(),
                    "Run 'mvn clean package' so the MySQL connector is on the classpath."));
            return checks;
        }
        checks.add(Check.pass("JDBC driver", config.getString("db.driver", "com.mysql.cj.jdbc.Driver")));

        String target = database.getHost() + ":" + database.getPort();

        if (config.isPlaceholder("db.admin.password")) {
            checks.add(Check.fail("MySQL server",
                    "db.admin.password is still set to the CHANGE_ME placeholder",
                    "Open src/main/resources/application.properties and set db.admin.password "
                            + "to the MySQL root password chosen during installation."));
            checks.add(Check.pending("Schema '" + database.getSchema() + "'",
                    "cannot be checked until the administrator password is set"));
            checks.add(Check.pending("Account '" + database.getApplicationUser() + "'",
                    "created during Phase 2"));
            return checks;
        }

        Optional<String> version = database.getServerVersion();
        if (!version.isPresent()) {
            checks.add(Check.fail("MySQL server",
                    "no response from " + target + " as '" + database.getAdministratorUser() + "'",
                    "Confirm the MySQL80 service is running and that db.admin.username "
                            + "and db.admin.password are correct."));
            checks.add(Check.pending("Schema '" + database.getSchema() + "'",
                    "cannot be checked while the server is unreachable"));
            checks.add(Check.pending("Account '" + database.getApplicationUser() + "'",
                    "created during Phase 2"));
            return checks;
        }
        checks.add(Check.pass("MySQL server", version.get() + " at " + target));

        if (database.schemaExists()) {
            checks.add(Check.pass("Schema '" + database.getSchema() + "'", "present"));
            if (database.canApplicationUserConnect()) {
                checks.add(Check.pass("Account '" + database.getApplicationUser() + "'", "connected"));
                checks.add(loginAccountsCheck());
            } else {
                checks.add(Check.pending("Account '" + database.getApplicationUser() + "'",
                        "not able to connect yet"));
            }
        } else {
            checks.add(Check.pending("Schema '" + database.getSchema() + "'",
                    "not built yet, run with " + SETUP_DATABASE_FLAG));
            checks.add(Check.pending("Account '" + database.getApplicationUser() + "'",
                    "created by the database build"));
        }
        return checks;
    }

    /**
     * Whether the seeded accounts can actually be signed in with. The seed
     * script cannot produce a PBKDF2 hash, so until provisioning has run
     * every account holds a sentinel and no login will succeed.
     */
    private static Check loginAccountsCheck() {
        try {
            long total = DAOFactory.getInstance().getUserDAO().count();
            int awaiting = DAOFactory.getInstance().getUserDAO().findAwaitingPassword().size();

            if (awaiting == 0) {
                return Check.pass("Login accounts", total + " provisioned");
            }
            return Check.pending("Login accounts",
                    awaiting + " of " + total + " still awaiting a password, run with "
                            + PROVISION_USERS_FLAG);
        } catch (TSATMSException failure) {
            return Check.fail("Login accounts", failure.getMessage(),
                    "Confirm the database build completed, then run with " + SETUP_DATABASE_FLAG);
        }
    }

    private static int printSummary(List<Check> checks) {
        long passed = checks.stream().filter(check -> check.state == State.PASS).count();
        long pending = checks.stream().filter(check -> check.state == State.PENDING).count();
        long failed = checks.stream().filter(check -> check.state == State.FAIL).count();

        System.out.println();
        System.out.println("  " + passed + " passed, " + pending + " pending, " + failed + " failed");

        if (failed > 0) {
            System.out.println();
            System.out.println("  Phase 1 could not complete. Resolve the failures above and run again.");
            AppLogger.warn(TSATMSApplication.class, "Startup checks reported " + failed + " failure(s)");
            System.out.println();
            return 1;
        }

        System.out.println();
        if (pending > 0) {
            System.out.println("  Some steps are still outstanding. See the [WAIT] lines above");
            System.out.println("  for the switch that completes each one.");
            System.out.println();
            // Pending is not failure, but a menu whose accounts have no
            // passwords yet is not worth opening either.
            return 1;
        }
        AppLogger.info(TSATMSApplication.class, "Startup checks completed cleanly");
        return 0;
    }

    private static String centre(String text) {
        int width = AppConstants.CONSOLE_WIDTH;
        if (text.length() >= width) {
            return text;
        }
        int leading = (width - text.length()) / 2;
        StringBuilder builder = new StringBuilder(width);
        for (int index = 0; index < leading; index++) {
            builder.append(' ');
        }
        return builder.append(text).toString();
    }

    /* ---------- Startup check reporting ---------- */

    private enum State {
        PASS("[ OK ]"),
        PENDING("[WAIT]"),
        FAIL("[FAIL]");

        private final String marker;

        State(String marker) {
            this.marker = marker;
        }
    }

    private static final class Check {

        private final State state;
        private final String label;
        private final String detail;
        private final String hint;

        private Check(State state, String label, String detail, String hint) {
            this.state = state;
            this.label = label;
            this.detail = detail;
            this.hint = hint;
        }

        static Check pass(String label, String detail) {
            return new Check(State.PASS, label, detail, null);
        }

        static Check pending(String label, String detail) {
            return new Check(State.PENDING, label, detail, null);
        }

        static Check fail(String label, String detail, String hint) {
            return new Check(State.FAIL, label, detail, hint);
        }

        void print() {
            System.out.println(String.format("  %s  %-26s %s", state.marker, label, detail));
            if (hint != null) {
                System.out.println(String.format("  %6s  %-26s %s", "", "", "-> " + hint));
            }
        }
    }
}
