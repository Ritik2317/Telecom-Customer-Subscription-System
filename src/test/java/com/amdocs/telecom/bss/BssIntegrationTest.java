package com.amdocs.telecom.bss;

import com.amdocs.telecom.bss.dao.*;
import com.amdocs.telecom.bss.dao.BssStore.*;
import com.amdocs.telecom.bss.model.Domain.*;
import com.amdocs.telecom.bss.service.*;
import com.amdocs.telecom.bss.report.ReportService;
import com.amdocs.telecom.bss.scheduler.BackgroundServices;
import com.amdocs.telecom.bss.controller.TelecomConsole;
import com.amdocs.telecom.bss.security.WebhookRecoveryDelivery;
import com.amdocs.telecom.security.*;
import com.amdocs.telecom.util.ConsoleReader;
import org.junit.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real JDBC/SQL/service integration tests; no fake DAOs or financial success responses. */
public class BssIntegrationTest {
    static final String PASSWORD="Test!Pass493";
    static final class MutableClock extends Clock {
        Instant instant=Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return instant;}
        void advance(Duration duration){instant=instant.plus(duration);}
    }
    MutableClock clock;BssStore store;BssDatabase database;AuthenticationService auth;
    SubscriptionService subs;BillingService billing;ComplaintService complaints;ReportService reports;
    Session admin,alice,bob;long aliceId,bobId,plan,sub;String url;
    AtomicReference<String> code=new AtomicReference<>();
    private BigDecimal n(String s){return new BigDecimal(s);}
    private Registration registration(String username,String email,String mobile){return new Registration(username,"Test","Customer",LocalDate.of(1990,1,1),email,mobile,"1 Test Street","Mumbai","India");}
    private Plan plan(String code,String type,String rental,boolean active,int tenure,boolean change){return new Plan(code,code,type,n(rental),n("1"),10,2,30,true,active,tenure,change,n("2"),n("0.10"),n("1"),n("5"));}
    private CaptchaChallenge captcha(){return CaptchaGenerator.next();}
    private String answer(CaptchaChallenge challenge){return challenge.render().split("\\|")[1].replace(" ","");}
    private Session login(String username,Role role){CaptchaChallenge c=captcha();return auth.login(username,PASSWORD.toCharArray(),role,c,answer(c));}
    private void renew(){admin=login("operator",Role.ADMIN);alice=login("alice",Role.CUSTOMER);bob=login("bobby",Role.CUSTOMER);}
    private RuntimeException rejected(Runnable action){try{action.run();fail("Expected operation to be rejected");return null;}catch(RuntimeException expected){return expected;}}
    private long count(String table){return store.transaction(tx->tx.required("SELECT COUNT(*) AS n FROM "+table).id("n"));}
    private Row row(String sql,Object...args){return store.transaction(tx->tx.required(sql,args));}
    private void monthEnd(){clock.advance(Duration.ofDays(31));renew();}
    private long bill(){monthEnd();return billing.generateBill(admin,sub,YearMonth.of(2026,1),BigDecimal.ZERO);}
    protected BssStore.Connections createConnections() {
        url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        return ()->DriverManager.getConnection(url,"sa","");
    }
    @Before public void setup(){
        clock=new MutableClock();
        BssStore.Connections connections=createConnections();store=new BssStore(connections);database=new BssDatabase(connections);database.initialize();
        auth=new AuthenticationService(store,clock,(email,value)->code.set(value));subs=new SubscriptionService(store,auth);
        billing=new BillingService(store,auth,subs,n("0.18"),20);complaints=new ComplaintService(store,auth,subs);reports=new ReportService(store,auth);
        auth.bootstrapAdmin("operator","operator@example.test",PASSWORD.toCharArray());
        aliceId=auth.register(registration("alice","alice@example.test","+919111111111"),PASSWORD.toCharArray());
        bobId=auth.register(registration("bobby","bob@example.test","+919222222222"),PASSWORD.toCharArray());renew();
        plan=subs.savePlan(admin,null,plan("BASE","POSTPAID","100",true,0,true));
        subs.addSim(admin,"8991000000000000001","+919333333333","ESIM");
        sub=subs.connect(alice,null,plan,"ESIM");
    }
    @After public void shutdownDatabase() throws Exception {try(Connection c=DriverManager.getConnection(url,"sa","");Statement s=c.createStatement()){s.execute("SHUTDOWN");}}

    @Test public void schemaIsAdditiveAndRepeatable(){
        database.initialize();database.check();assertEquals(3,count("bss_users"));assertEquals(1,count("bss_subscriptions"));
        rejected(()->store.transaction(tx->tx.insert("INSERT INTO bss_customers(customer_number,user_id,first_name,last_name,date_of_birth,mobile_number,address,city,country) VALUES('bad',9999,'a','b','1990-01-01','+911234567890','a','a','a')")));
    }
    @Test public void registrationValidatesAndRollsBackDuplicates(){
        rejected(()->auth.register(registration("newuser","alice@example.test","+919444444444"),PASSWORD.toCharArray()));
        rejected(()->auth.register(registration("newuser","unique@example.test","+919111111111"),PASSWORD.toCharArray()));
        rejected(()->auth.register(registration("newuser","invalid","+919444444444"),PASSWORD.toCharArray()));
        rejected(()->auth.register(registration("newuser","unique@example.test","12345"),PASSWORD.toCharArray()));
        Registration child=new Registration("child","Test","Child",LocalDate.of(2020,1,1),"child@example.test","+919444444444","a","b","c");
        rejected(()->auth.register(child,PASSWORD.toCharArray()));
        rejected(()->auth.register(registration("newuser","unique@example.test","+919444444444"),"weak".toCharArray()));
        assertEquals(3,count("bss_users"));assertEquals(2,count("bss_customers"));
        char[] secret=PASSWORD.toCharArray();auth.register(registration("newuser","unique@example.test","+919444444444"),secret);assertEquals('\0',secret[0]);
        assertEquals(4,count("bss_users"));
    }
    @Test public void captchaIsSingleUseAndFailuresLockForFifteenMinutes(){
        CaptchaChallenge c=captcha();rejected(()->auth.login("alice",PASSWORD.toCharArray(),Role.CUSTOMER,c,"WRONG"));
        rejected(()->auth.login("alice",PASSWORD.toCharArray(),Role.CUSTOMER,c,answer(c)));
        CaptchaChallenge third=captcha();rejected(()->auth.login("alice","wrong".toCharArray(),Role.CUSTOMER,third,answer(third)));
        assertEquals(3,row("SELECT failed_attempts FROM bss_users WHERE username='alice'").integer("failed_attempts"));
        rejected(()->login("alice",Role.CUSTOMER));clock.advance(Duration.ofMinutes(16));alice=login("alice",Role.CUSTOMER);
        assertEquals(0,row("SELECT failed_attempts FROM bss_users WHERE username='alice'").integer("failed_attempts"));
        assertNotNull(row("SELECT last_login FROM bss_users WHERE username='alice'").time("last_login"));
    }
    @Test public void sessionsCannotForgeRolesAndLogoutRevokesToken(){
        Session forged=new Session(alice.token(),"operator",Role.ADMIN);
        rejected(()->subs.savePlan(forged,null,plan("EVIL","POSTPAID","1",true,0,true)));
        rejected(()->subs.subscriptions(new Session("made-up","operator",Role.ADMIN)));
        rejected(()->subs.history(bob,sub));rejected(()->billing.usage(bob,sub,YearMonth.of(2026,1)));
        auth.logout(alice);rejected(()->subs.customers(alice));
        assertTrue(complaints.loginHistory(login("alice",Role.CUSTOMER)).stream().anyMatch(h->h.time("logout_time")!=null));
        clock.advance(Duration.ofHours(9));rejected(()->subs.customers(admin));
    }
    @Test public void passwordRecoveryExpiresLimitsAttemptsAndRevokesSessions(){
        CaptchaChallenge c=captcha();auth.requestRecovery("alice",c,answer(c));assertNotNull(code.get());
        String first=code.get();rejected(()->auth.recover("alice","invalid",PASSWORD.toCharArray()));
        rejected(()->auth.recover("alice","invalid",PASSWORD.toCharArray()));rejected(()->auth.recover("alice","invalid",PASSWORD.toCharArray()));
        rejected(()->auth.recover("alice",first,PASSWORD.toCharArray()));
        clock.advance(Duration.ofMinutes(2));c=captcha();auth.requestRecovery("alice",c,answer(c));
        String second=code.get();clock.advance(Duration.ofMinutes(6));rejected(()->auth.recover("alice",second,PASSWORD.toCharArray()));
        c=captcha();auth.requestRecovery("alice",c,answer(c));auth.recover("alice",code.get(),"Changed!Pass967".toCharArray());
        rejected(()->subs.customers(alice));CaptchaChallenge login=captcha();Session reset=auth.login("alice","Changed!Pass967".toCharArray(),Role.CUSTOMER,login,answer(login));
        assertEquals(1,subs.customers(reset).size());rejected(()->auth.recover("alice",code.get(),PASSWORD.toCharArray()));
    }
    @Test public void recoveryTransportFailsClearlyWithoutCredentials(){
        WebhookRecoveryDelivery missing=new WebhookRecoveryDelivery("","");rejected(()->missing.checkAvailability());
        rejected(()->new WebhookRecoveryDelivery("http://example.test/recover","placeholder"));
        CaptchaChallenge c=captcha();auth.requestRecovery("unknown",c,answer(c));assertNull(code.get());
    }
    @Test public void planSearchComparisonAndRetirementAreFunctional(){
        long second=subs.savePlan(admin,null,plan("PREMIUM","POSTPAID","200",true,0,true));
        subs.savePlan(admin,null,plan("HIDDEN","POSTPAID","10",false,0,true));
        assertEquals(1,subs.plans(alice,"prem",BigDecimal.ZERO,n("500"),BigDecimal.ONE,false).size());
        assertEquals(2,subs.compare(alice,plan,second).size());
        assertEquals("PREMIUM",subs.plans(alice,"",BigDecimal.ZERO,n("500"),BigDecimal.ZERO,false).get(0).text("plan_code"));
        rejected(()->subs.savePlan(admin,plan,plan("BASE","POSTPAID","999",true,0,true)));
        subs.retirePlan(admin,second);rejected(()->subs.changePlan(alice,sub,second,"Try retired"));
        rejected(()->subs.compare(alice,plan,second));
        rejected(()->subs.savePlan(alice,null,plan("NO","POSTPAID","1",true,0,true)));
    }
    @Test public void multipleConnectionsEnforceSimAndPlanUniqueness(){
        subs.addSim(admin,"8991000000000000002","+919444444444","PHYSICAL_SIM");
        rejected(()->subs.connect(alice,null,plan,"PHYSICAL_SIM"));assertEquals("AVAILABLE",subs.sims(admin).get(1).text("status"));
        long p=subs.savePlan(admin,null,plan("SECOND","PREPAID","50",true,0,true));
        long second=subs.connect(alice,null,p,"PHYSICAL_SIM");assertTrue(second!=sub);assertEquals(2,subs.subscriptions(alice).size());
        rejected(()->subs.addSim(admin,"8991000000000000002","+919555555555","ESIM"));
        rejected(()->subs.retireSim(admin,1));assertEquals(2,count("bss_subscription_history"));
    }
    @Test public void upgradesDowngradesTypeChangesAndHistoryRespectRules(){
        long premium=subs.savePlan(admin,null,plan("PREMIUM","POSTPAID","200",true,0,true));
        subs.changePlan(alice,sub,premium,"Upgrade");subs.changePlan(alice,sub,plan,"Downgrade");
        long prepaid=subs.savePlan(admin,null,plan("PREPAID","PREPAID","50",true,5,true));
        subs.changePlan(alice,sub,prepaid,"Type conversion");assertEquals("PREPAID",subs.subscriptions(alice).get(0).text("subscription_type"));
        rejected(()->subs.changePlan(alice,sub,plan,"Before tenure"));
        clock.advance(Duration.ofDays(6));renew();subs.changePlan(alice,sub,plan,"After tenure");
        assertEquals(5,subs.history(alice,sub).size());rejected(()->subs.changePlan(alice,sub,plan,"Duplicate"));
        long blocked=subs.savePlan(admin,null,plan("NO-CONVERT","PREPAID","30",true,0,false));rejected(()->subs.changePlan(alice,sub,blocked,"Forbidden conversion"));
    }
    @Test public void addonsAndHistoricalMonthlyPricingArePersisted(){
        long addon=subs.savePlan(admin,null,plan("ADDON","ADDON","20",true,0,true));subs.addon(alice,sub,addon,true);
        billing.recordUsage(admin,"jan-data",sub,auth.now(),UsageType.DATA,n("3"),"GB");billing.processPendingUsage();
        long premium=subs.savePlan(admin,null,plan("PREMIUM","POSTPAID","200",true,0,true));subs.changePlan(alice,sub,premium,"Upgrade mid-cycle");
        long b=bill();assertEquals(0,row("SELECT plan_rental FROM bss_bills WHERE id=?",b).decimal("plan_rental").compareTo(n("120")));
        assertEquals(1,subs.addons(alice,sub).size());subs.addon(alice,sub,addon,false);
        assertEquals(0,row("SELECT rental FROM bss_month_terms WHERE subscription_id=? AND billing_month='2026-01'",sub).decimal("rental").compareTo(n("120")));
    }
    @Test public void usageAllTypesAndMonthlyBillingUseExactMoney(){
        billing.recordUsage(admin,"data",sub,auth.now(),UsageType.DATA,n("3"),"GB");
        billing.recordUsage(admin,"voice",sub,auth.now(),UsageType.VOICE,n("12"),"MINUTES");
        billing.recordUsage(admin,"sms",sub,auth.now(),UsageType.SMS,n("3"),"COUNT");
        billing.recordUsage(admin,"roaming",sub,auth.now(),UsageType.ROAMING,n("2"),"GB");
        billing.processPendingUsage();assertEquals(4,billing.usage(alice,sub,YearMonth.of(2026,1)).size());
        long b=bill();Row invoice=row("SELECT * FROM bss_bills WHERE id=?",b);
        assertEquals(0,n("15.20").compareTo(invoice.decimal("usage_charges")));assertEquals(0,n("135.94").compareTo(invoice.decimal("total_amount")));
        assertEquals(b,billing.generateBill(admin,sub,YearMonth.of(2026,1),BigDecimal.ZERO));assertEquals(1,count("bss_bills"));
        rejected(()->billing.recordUsage(admin,"late",sub,LocalDateTime.of(2026,1,5,0,0),UsageType.DATA,BigDecimal.ONE,"GB"));
    }
    @Test public void usageRejectsInvalidUnitsDatesDuplicatesAndUnauthorizedImport(){
        rejected(()->billing.recordUsage(alice,"event",sub,auth.now(),UsageType.DATA,BigDecimal.ONE,"GB"));
        rejected(()->billing.recordUsage(admin,"event",sub,auth.now(),UsageType.DATA,BigDecimal.ONE,"MB"));
        rejected(()->billing.recordUsage(admin,"event",sub,auth.now().plusDays(1),UsageType.DATA,BigDecimal.ONE,"GB"));
        rejected(()->billing.recordUsage(admin,"event",sub,auth.now(),UsageType.SMS,n("1.5"),"COUNT"));
        rejected(()->billing.recordUsage(admin,"event",sub,auth.now(),UsageType.DATA,n("-1"),"GB"));
        billing.recordUsage(admin,"event",sub,auth.now(),UsageType.DATA,BigDecimal.ONE,"GB");
        rejected(()->billing.recordUsage(admin,"event",sub,auth.now(),UsageType.DATA,BigDecimal.ONE,"GB"));assertEquals(1,count("bss_usage"));
        rejected(()->billing.generateBill(admin,sub,YearMonth.of(2026,1),BigDecimal.ZERO));
    }
    @Test public void paymentCommitsBillNotificationAndAuditAndBlocksDuplicates(){
        long b=bill();BigDecimal amount=row("SELECT total_amount FROM bss_bills WHERE id=?",b).decimal("total_amount");
        rejected(()->billing.pay(bob,b,amount,PaymentMode.UPI,"unauthorized"));
        rejected(()->billing.pay(alice,b,BigDecimal.ONE,PaymentMode.CARD,"wrong-amount"));
        long id=billing.pay(alice,b,amount,PaymentMode.UPI,"bank-confirmed-1");assertTrue(id>0);
        assertEquals("PAID",billing.bills(alice).get(0).text("status"));assertEquals(1,billing.payments(alice).size());assertEquals(0,billing.payments(bob).size());
        assertEquals(1,row("SELECT COUNT(*) AS n FROM bss_audit WHERE action='CONFIRM_PAYMENT'").id("n"));
        rejected(()->billing.pay(alice,b,amount,PaymentMode.UPI,"bank-confirmed-2"));assertEquals(1,count("bss_payments"));
    }
    @Test public void paymentRollsBackWhenLastAuditStepFails(){
        long b=bill();BigDecimal amount=billing.bills(alice).get(0).decimal("total_amount");
        store.transaction(tx->{tx.update("ALTER TABLE bss_audit ADD CONSTRAINT fail_payment_audit CHECK(action <> 'CONFIRM_PAYMENT')");return null;});
        long notifications=count("bss_notifications");rejected(()->billing.pay(alice,b,amount,PaymentMode.BANK_TRANSFER,"confirmed-but-rollback"));
        assertEquals(0,count("bss_payments"));assertEquals("UNPAID",billing.bills(alice).get(0).text("status"));assertEquals(notifications,count("bss_notifications"));
        store.transaction(tx->{tx.update("ALTER TABLE bss_audit DROP "+(url.startsWith("jdbc:mysql:")?"CHECK":"CONSTRAINT")+" fail_payment_audit");return null;});
        billing.pay(alice,b,amount,PaymentMode.BANK_TRANSFER,"confirmed-but-rollback");assertEquals(1,count("bss_payments"));
    }
    @Test public void concurrentPaymentsCreateExactlyOnePayment() throws Exception {
        long b=bill();BigDecimal amount=billing.bills(alice).get(0).decimal("total_amount");ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();for(int i=0;i<2;i++){String ref="concurrent-"+i;futures.add(pool.submit(()->{start.await();try{billing.pay(alice,b,amount,PaymentMode.NET_BANKING,ref);return true;}catch(RuntimeException e){return false;}}));}
            start.countDown();int successes=0;for(Future<Boolean> future:futures) if(future.get(15,TimeUnit.SECONDS)) successes++;
            assertEquals(1,successes);assertEquals(1,count("bss_payments"));assertEquals("PAID",billing.bills(alice).get(0).text("status"));
        } finally {pool.shutdownNow();}
    }
    @Test public void complaintsEnforceOwnershipAndResolution(){
        rejected(()->complaints.create(bob,sub,Category.BILLING,"Wrong owner",Priority.HIGH));
        long id=complaints.create(alice,sub,Category.BILLING,"Please explain usage charge",Priority.MEDIUM);
        assertEquals(0,complaints.list(bob).size());rejected(()->complaints.update(alice,id,"RESOLVED","No"));
        rejected(()->complaints.update(admin,id,"RESOLVED",""));complaints.update(admin,id,"IN_PROGRESS","");complaints.update(admin,id,"RESOLVED","Charges explained");
        assertEquals("Charges explained",complaints.list(alice).get(0).text("resolution"));rejected(()->complaints.update(admin,id,"IN_PROGRESS",""));
    }
    @Test public void overdueAndCustomerDisableRevokeAccess(){
        long b=bill();clock.advance(Duration.ofDays(21));renew();billing.monitorAccounts();
        assertEquals("OVERDUE",subs.subscriptions(alice).get(0).text("status"));rejected(()->subs.subscriptionStatus(admin,sub,true));
        billing.pay(alice,b,billing.bills(alice).get(0).decimal("total_amount"),PaymentMode.CARD,"paid-overdue");subs.subscriptionStatus(admin,sub,true);
        subs.customerStatus(admin,aliceId,false);rejected(()->subs.customers(alice));rejected(()->login("alice",Role.CUSTOMER));
        subs.customerStatus(admin,aliceId,true);alice=login("alice",Role.CUSTOMER);assertEquals(1,subs.customers(alice).size());
    }
    @Test public void reportsContainPersistedDataAndExportBothFormats() throws Exception {
        billing.recordUsage(admin,"report-event",sub,auth.now(),UsageType.DATA,n("3"),"GB");billing.processPendingUsage();long b=bill();
        List<ReportService.Table> january=reports.generate(admin,YearMonth.of(2026,1),BigDecimal.ZERO,n("1000"));
        Map<String,ReportService.Table> byName=new HashMap<>();january.forEach(t->byName.put(t.name(),t));
        assertEquals("3.0000",byName.get("highest-usage-customers").rows().get(0).get(1));
        assertEquals(List.of("Mumbai","2"),byName.get("customers-by-city").rows().get(0));
        assertEquals(List.of("BASE","BASE","100.00"),byName.get("plans-in-price-range").rows().get(0));
        assertEquals(List.of("BASE","1"),byName.get("most-subscribed-plans").rows().get(0));
        assertEquals("3.0",byName.get("usage-by-type").rows().get(0).get(1));
        assertEquals("122.72",byName.get("customers-with-unpaid-bills").rows().get(0).get(1));
        billing.pay(alice,b,billing.bills(alice).get(0).decimal("total_amount"),PaymentMode.UPI,"reported-payment");
        List<ReportService.Table> result=reports.generate(admin,YearMonth.of(2026,2),BigDecimal.ZERO,n("1000"));assertEquals(8,result.size());
        assertEquals("122.72",result.stream().filter(t->t.name().equals("monthly-revenue")).findFirst().get().rows().get(0).get(1));
        assertEquals("61.36",result.stream().filter(t->t.name().equals("average-monthly-revenue-per-customer")).findFirst().get().rows().get(0).get(3));
        rejected(()->reports.generate(alice,YearMonth.of(2026,1),BigDecimal.ZERO,n("1000")));
        Path dir=Files.createTempDirectory("tcsms-reports-");try {List<Path> files=reports.export(admin,YearMonth.of(2026,1),dir,BigDecimal.ZERO,n("1000"));assertEquals(16,files.size());for(Path file:files) assertTrue(Files.size(file)>0);}
        finally {try(var files=Files.list(dir)){for(Path file:files.toList()) Files.delete(file);}Files.delete(dir);}
        ReportService.Table dangerous=new ReportService.Table("test",List.of("Name"),List.of(List.of("=1+1")));assertTrue(ReportService.Format.CSV.writer().render(dangerous).contains("'=1+1"));
    }
    @Test public void notificationsWorkersAndDurableUsageQueueWork() throws Exception {
        billing.recordUsage(admin,"queued",sub,auth.now(),UsageType.DATA,n("3"),"GB");complaints.create(alice,sub,Category.NETWORK,"Signal issue",Priority.HIGH);
        assertEquals(0,complaints.inbox(alice).size());
        try(BackgroundServices workers=new BackgroundServices(billing)) {
            workers.start();workers.start();workers.wakeUsage();assertTrue(workers.isRunning());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<deadline && (complaints.inbox(alice).isEmpty() || !"PROCESSED".equals(row("SELECT status FROM bss_usage WHERE event_reference='queued'").text("status")))) Thread.sleep(20);
            assertEquals("PROCESSED",row("SELECT status FROM bss_usage WHERE event_reference='queued'").text("status"));assertEquals(1,complaints.inbox(alice).size());
        }
        long id=complaints.inbox(alice).get(0).id("id");rejected(()->complaints.readNotification(bob,id));complaints.readNotification(alice,id);assertEquals("READ",complaints.inbox(alice).get(0).text("status"));
    }
    @Test public void savepointsBatchAndRollbackUseRealJdbc(){
        long before=count("bss_audit");store.transaction(tx->{Savepoint point=tx.savepoint();tx.batch("INSERT INTO bss_audit(actor,action,entity_id,details) VALUES(?,?,?,?)",List.of(new Object[]{"TEST","BATCH",1,"first"},new Object[]{"TEST","BATCH",2,"second"}));tx.rollbackTo(point);return null;});assertEquals(before,count("bss_audit"));
        rejected(()->store.transaction(tx->{tx.update("INSERT INTO bss_audit(actor,action,entity_id,details) VALUES('TEST','ROLLBACK',1,'test')");throw new IllegalArgumentException("intentional rollback");}));assertEquals(before,count("bss_audit"));
    }
    @Test public void consoleRegistrationAndEofUseActualServices(){
        String script="2\nconsoleuser\nConsole\nCustomer\n1990-01-01\nconsole@example.test\n+919777777777\n1 Road\nDelhi\nIndia\n"+PASSWORD+"\n5\n";
        ByteArrayOutputStream output=new ByteArrayOutputStream();PrintStream out=new PrintStream(output,true,StandardCharsets.UTF_8);
        TelecomConsole console=new TelecomConsole(new ConsoleReader(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),out),out,auth,subs,billing,complaints,reports,Path.of("target/test-reports"));console.run();
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Registered customer"));assertEquals(4,count("bss_users"));
        new TelecomConsole(new ConsoleReader(new ByteArrayInputStream(new byte[0]),out),out,auth,subs,billing,complaints,reports,Path.of("target/test-reports")).run();
    }
    @Test public void malformedStoredPasswordFailsClosed(){assertFalse(PasswordHasher.matches(PASSWORD.toCharArray(),"PBKDF2-SHA256:invalid-hex","aabb"));}
    @Test public void commonSqlExamplesExecuteOnTheActualDatabase() throws Exception {
        try(Connection c=createSqlReferenceConnection()) {assertEquals(5,new com.amdocs.telecom.util.SqlScriptRunner(c).runScript("bss/sample_queries.sql",Collections.emptyMap()));}
    }
    protected Connection createSqlReferenceConnection() throws SQLException {return DriverManager.getConnection(url,"sa","");}

    @Test public void profileChangesValidateOwnershipAndUniqueness(){
        rejected(()->subs.updateProfile(bob,aliceId,"new@example.test","+919666666666","Road","Delhi","India"));
        rejected(()->subs.updateProfile(alice,aliceId,"bob@example.test","+919666666666","Road","Delhi","India"));
        subs.updateProfile(alice,aliceId,"changed@example.test","+919666666666","New Road","Delhi","India");
        assertEquals("Delhi",subs.customers(alice).get(0).text("city"));assertEquals("changed@example.test",subs.customers(alice).get(0).text("email"));
    }
    @Test public void prepaidValidityAndUnlimitedVoiceAreApplied(){
        Plan p=new Plan("PRE","Prepaid","PREPAID",n("50"),BigDecimal.ONE,-1,2,2,false,true,0,true,n("2"),n("1"),n("1"),n("5"));
        long pre=subs.savePlan(admin,null,p);subs.changePlan(alice,sub,pre,"Select prepaid");
        // The existing monthly snapshot starts with BASE; the new plan is the next cycle's price.
        clock.advance(Duration.ofDays(1));renew();billing.recordUsage(admin,"valid-prepaid",sub,auth.now(),UsageType.VOICE,n("5000"),"MINUTES");
        billing.processPendingUsage();assertEquals(0,BigDecimal.ZERO.compareTo(row("SELECT charge FROM bss_usage WHERE event_reference='valid-prepaid'").decimal("charge")));
        clock.advance(Duration.ofDays(2));renew();billing.monitorAccounts();assertEquals("EXPIRED",subs.subscriptions(alice).get(0).text("status"));
        rejected(()->billing.recordUsage(admin,"expired-prepaid",sub,auth.now(),UsageType.VOICE,BigDecimal.ONE,"MINUTES"));
        long before=count("bss_plans");rejected(()->subs.savePlan(admin,plan,plan("BASE","POSTPAID","999",true,0,true)));assertEquals(before,count("bss_plans"));
    }
    @Test public void discountTaxAndBillValidationAreEnforced(){
        rejected(()->billing.generateBill(admin,sub,YearMonth.of(2025,12),BigDecimal.ZERO));
        monthEnd();rejected(()->billing.generateBill(alice,sub,YearMonth.of(2026,1),BigDecimal.ZERO));
        rejected(()->billing.generateBill(admin,sub,YearMonth.of(2026,1),n("101")));
        long b=billing.generateBill(admin,sub,YearMonth.of(2026,1),n("10"));Row r=row("SELECT * FROM bss_bills WHERE id=?",b);
        assertEquals(0,n("16.20").compareTo(r.decimal("tax_amount")));assertEquals(0,n("106.20").compareTo(r.decimal("total_amount")));
    }
    @Test public void duplicateBankReferenceCannotPayAnotherBill(){
        long secondPlan=subs.savePlan(admin,null,plan("SECOND","POSTPAID","100",true,0,true));
        subs.addSim(admin,"8991000000000000002","+919444444444","ESIM");long second=subs.connect(alice,null,secondPlan,"ESIM");
        long firstBill=bill(),secondBill=billing.generateBill(admin,second,YearMonth.of(2026,1),BigDecimal.ZERO);
        BigDecimal due=billing.bills(alice).get(0).decimal("total_amount");billing.pay(alice,firstBill,due,PaymentMode.UPI,"one-bank-transaction");
        rejected(()->billing.pay(alice,secondBill,due,PaymentMode.UPI,"one-bank-transaction"));
        assertEquals("UNPAID",row("SELECT status FROM bss_bills WHERE id=?",secondBill).text("status"));assertEquals(1,count("bss_payments"));
    }
    @Test public void cumulativeRoundingDoesNotLoseFragmentedUsageCharges(){
        for(int i=0;i<10;i++) billing.recordUsage(admin,"small-"+i,sub,auth.now(),UsageType.ROAMING,n("0.0001"),"GB");
        billing.processPendingUsage();assertEquals(0,n("0.01").compareTo(row("SELECT SUM(charge) AS amount FROM bss_usage").decimal("amount")));
    }
    @Test public void consoleCustomerPaymentComplaintAndAdministratorResolutionWork() throws Exception {
        long b=bill();BigDecimal due=billing.bills(alice).get(0).decimal("total_amount");
        String script="1\nalice\n$CAPTCHA\n"+PASSWORD+"\n4\n10\n11\n"+b+"\n"+due+"\nUPI\nconsole-bank-reference\n13\n"+sub+"\nBILLING\nExplain bill\nMEDIUM\n14\n0\n3\noperator\n$CAPTCHA\n"+PASSWORD+"\n14\n29\n1\nRESOLVED\nExplained charges\n30\n2026-02\n0\n1000\n0\n5\n";
        ByteArrayOutputStream output=new ByteArrayOutputStream();PrintStream out=new PrintStream(output,true,StandardCharsets.UTF_8);
        class InteractiveInput extends InputStream {
            final Iterator<String> lines=Arrays.asList(script.split("\n")).iterator();byte[] current=new byte[0];int offset;
            @Override public int read(){if(offset==current.length){if(!lines.hasNext()) return -1;String line=lines.next();
                if(line.equals("$CAPTCHA")){String displayed=output.toString(StandardCharsets.UTF_8);int end=displayed.lastIndexOf('|');int start=displayed.lastIndexOf('|',end-1);line=displayed.substring(start+1,end).replace(" ","");}
                current=(line+"\n").getBytes(StandardCharsets.UTF_8);offset=0;}return current[offset++]&255;}
            @Override public int read(byte[] buffer,int start,int length){int next=read();if(next<0)return -1;buffer[start]=(byte)next;int count=1;while(count<length && offset<current.length)buffer[start+count++]=current[offset++];return count;}
        }
        Path dir=Files.createTempDirectory("tcsms-console-");
        try {new TelecomConsole(new ConsoleReader(new InteractiveInput(),out),out,auth,subs,billing,complaints,reports,dir).run();
            String shown=output.toString(StandardCharsets.UTF_8);assertFalse(shown,shown.contains("Could not complete action"));
            assertTrue(shown.contains("Payment recorded"));assertTrue(shown.contains("Complaint updated"));assertEquals("PAID",billing.bills(login("alice",Role.CUSTOMER)).get(0).text("status"));
            assertEquals("RESOLVED",complaints.list(login("alice",Role.CUSTOMER)).get(0).text("status"));try(var files=Files.list(dir)){assertEquals(16,files.count());}
        } finally {try(var files=Files.list(dir)){for(Path file:files.toList()) Files.delete(file);}Files.delete(dir);}
    }
}
