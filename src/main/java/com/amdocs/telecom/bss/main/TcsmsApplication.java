package com.amdocs.telecom.bss.main;

import com.amdocs.telecom.bss.controller.TelecomConsole;
import com.amdocs.telecom.bss.dao.*;
import com.amdocs.telecom.bss.util.BssConfiguration;
import com.amdocs.telecom.bss.service.*;
import com.amdocs.telecom.bss.security.WebhookRecoveryDelivery;
import com.amdocs.telecom.bss.report.ReportService;
import com.amdocs.telecom.bss.scheduler.BackgroundServices;
import com.amdocs.telecom.util.ConsoleReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;

public final class TcsmsApplication {
    private TcsmsApplication() { }
    public static void main(String[] args) {
        try {run(args);}catch(RuntimeException e) {System.err.println("TCSMS: "+e.getMessage());System.exit(1);}
    }
    public static void run(String[] args) {
        String command=args.length==0?"--console":args[0];
        if(args.length>1) throw new IllegalArgumentException("Use one command at a time; see --help");
        if(command.equals("--help")) {
            System.out.println("TCSMS: --init (additive schema), --create-admin (first operator account), --check, --console (default), --workers (background service), --help. Legacy OSS: --legacy [old options].\nConfigure your own MySQL with TCSMS_DB_URL, TCSMS_DB_USER, TCSMS_DB_PASSWORD. See docs/SETUP.md.");return;
        }
        if(!java.util.Set.of("--init","--create-admin","--check","--console","--workers").contains(command)) throw new IllegalArgumentException("Unknown command; use --help or --legacy");
        BssConfiguration config=new BssConfiguration();String url=config.required("db.url");
        if(!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("Production requires your explicitly configured jdbc:mysql:// URL");
        String user=config.required("db.user"),password=config.required("db.password");
        BssStore.Connections connections=()->DriverManager.getConnection(url,user,password);
        DriverManager.setLoginTimeout(10);BssDatabase database=new BssDatabase(connections);
        if(command.equals("--init")) {database.initialize();System.out.println("BSS schema initialized. No accounts or customer records were seeded.");return;}
        database.check();if(command.equals("--check")) {System.out.println("BSS database reachable; schema version 1.");return;}
        BssStore store=new BssStore(connections);Clock clock=Clock.system(ZoneId.of(config.get("timezone","Asia/Kolkata")));
        AuthenticationService auth=new AuthenticationService(store,clock,new WebhookRecoveryDelivery(config.get("recovery.url",""),config.get("recovery.token","")));
        if(command.equals("--create-admin")) {
            ConsoleReader reader=new ConsoleReader();String name=reader.readRequired("Administrator username: ").orElseThrow(()->new IllegalArgumentException("Input ended"));
            String email=reader.readRequired("Administrator email: ").orElseThrow(()->new IllegalArgumentException("Input ended"));
            if(!reader.isEchoMaskingAvailable()) System.out.println("Password input is visible in this terminal; prefer a native terminal.");
            char[] secret=reader.readSecret("Administrator password: ").orElseThrow(()->new IllegalArgumentException("Input ended"));
            auth.bootstrapAdmin(name,email,secret);System.out.println("Your administrator account was created.");return;
        }
        SubscriptionService subs=new SubscriptionService(store,auth);
        BillingService billing=new BillingService(store,auth,subs,new BigDecimal(config.get("tax.rate","0.18")),Integer.parseInt(config.get("billing.due.days","20")));
        try(BackgroundServices workers=new BackgroundServices(billing)) {
            Thread hook=new Thread(workers::close,"tcsms-shutdown");Runtime.getRuntime().addShutdownHook(hook);
            try {
                workers.start();
                if(command.equals("--workers")) {try {new java.util.concurrent.CountDownLatch(1).await();}catch(InterruptedException e) {Thread.currentThread().interrupt();}}
                else new TelecomConsole(new ConsoleReader(),System.out,auth,subs,billing,new ComplaintService(store,auth,subs),new ReportService(store,auth),Path.of(config.get("report.directory","reports/tcsms"))).run();
            } finally {try {Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException ignored) { /* shutdown in progress */ }}
        }
    }
}
