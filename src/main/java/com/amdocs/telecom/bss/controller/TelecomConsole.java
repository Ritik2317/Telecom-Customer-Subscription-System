package com.amdocs.telecom.bss.controller;

import com.amdocs.telecom.bss.model.Domain.*;
import com.amdocs.telecom.bss.dao.BssStore.Row;
import com.amdocs.telecom.bss.service.*;
import com.amdocs.telecom.bss.report.ReportService;
import com.amdocs.telecom.security.*;
import com.amdocs.telecom.util.ConsoleReader;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** The PDF's five-option login and role-specific, keyboard-driven workflows. */
public final class TelecomConsole {
    private final ConsoleReader input;private final PrintStream out;
    private final AuthenticationService auth;private final SubscriptionService subs;
    private final BillingService billing;private final ComplaintService complaints;private final ReportService reports;
    private final Path reportDirectory;
    public TelecomConsole(ConsoleReader input,PrintStream out,AuthenticationService auth,SubscriptionService subs,
                          BillingService billing,ComplaintService complaints,ReportService reports,Path reportDirectory) {
        this.input=input;this.out=out;this.auth=auth;this.subs=subs;this.billing=billing;this.complaints=complaints;this.reports=reports;this.reportDirectory=reportDirectory;
    }
    private static final class EndInput extends RuntimeException { }
    private String ask(String label) {return input.readLine(label+": ").orElseThrow(EndInput::new);}
    private char[] password(String label) {return input.readSecret(label+": ").orElseThrow(EndInput::new);}
    private long id(String label) {return Long.parseLong(ask(label));}
    private int integer(String label) {return Integer.parseInt(ask(label));}
    private BigDecimal amount(String label) {return new BigDecimal(ask(label));}
    private boolean yes(String label) {String v=ask(label+" (yes/no)");if(!v.equalsIgnoreCase("yes")&&!v.equalsIgnoreCase("no")) throw new IllegalArgumentException("Enter yes or no");return v.equalsIgnoreCase("yes");}
    private YearMonth month() {return YearMonth.parse(ask("Billing month YYYY-MM"));}
    private <T extends Enum<T>> T choice(String label,Class<T> type) {return Enum.valueOf(type,ask(label+" "+Arrays.toString(type.getEnumConstants())).toUpperCase(Locale.ROOT));}
    private void rows(List<Row> rows) {if(rows.isEmpty()) out.println("No records found.");else rows.forEach(out::println);}
    public void run() {
        out.println("TELECOM CUSTOMER & SUBSCRIPTION MANAGEMENT SYSTEM");
        if(!input.isEchoMaskingAvailable()) out.println("This terminal cannot hide password input. Use a native terminal for private entry.");
        try {
            while(!input.isInputExhausted()) {
                out.println("1. Customer Login\n2. New Customer Registration\n3. Administrator Login\n4. Forgot Password\n5. Exit");
                try {
                    switch(ask("Option")) {
                        case "1": login(Role.CUSTOMER);break;
                        case "2": register();break;
                        case "3": login(Role.ADMIN);break;
                        case "4": recover();break;
                        case "5": return;
                        default:out.println("Choose 1-5.");
                    }
                } catch(EndInput end) {return;}catch(RuntimeException failure) {out.println("Could not complete action: "+safeMessage(failure));}
            }
        } catch(EndInput end) { /* clean EOF */ }
    }
    private String safeMessage(RuntimeException failure) {
        if(failure instanceof NumberFormatException) return "Enter a valid number.";
        if(failure instanceof java.time.format.DateTimeParseException) return "Enter dates as YYYY-MM-DD and months as YYYY-MM.";
        if(failure instanceof IllegalArgumentException || failure instanceof IllegalStateException) return Objects.toString(failure.getMessage(),"Invalid selection or configuration");
        return "Unexpected failure. No successful change was reported; contact the operator.";
    }
    private void register() {
        Registration r=new Registration(ask("Username"),ask("First name"),ask("Last name"),LocalDate.parse(ask("Date of birth YYYY-MM-DD")),ask("Email"),ask("Contact mobile +countrycode"),ask("Address"),ask("City"),ask("Country"));
        out.println(PasswordPolicy.describeRules());long id=auth.register(r,password("Password"));out.println("Registered customer "+id+". You can now sign in.");
    }
    private void login(Role role) {
        String username=ask("Username");CaptchaChallenge c=CaptchaGenerator.next();out.println(c.render());String answer=ask("CAPTCHA");
        Session session=auth.login(username,password("Password"),role,c,answer);
        out.println("Signed in as "+session+". Session expires after 8 hours.");
        try {dashboard(session);}finally {try {auth.logout(session);}catch(RuntimeException ignored) { /* revoked/expired session is already unusable */ }}
    }
    private void recover() {
        String username=ask("Username");CaptchaChallenge c=CaptchaGenerator.next();out.println(c.render());
        auth.requestRecovery(username,c,ask("CAPTCHA"));out.println("If the account is eligible, a recovery code has been sent to its registered address.");
        auth.recover(username,ask("Recovery code"),password("New password"));out.println("Password updated; previous sessions revoked.");
    }
    private void dashboard(Session s) {
        while(!input.isInputExhausted()) {
            out.println("\n1. Profile/customers\n2. Search/filter/sort plans\n3. Compare plans\n4. Subscriptions\n5. Subscribe/new connection\n6. Change plan\n7. Activate/deactivate add-on\n8. Plan/add-on history\n9. Monthly usage\n10. Bills\n11. Record confirmed payment\n12. Payments\n13. Open complaint\n14. Complaints\n15. Notifications\n16. Login history\n17. Update profile\n0. Logout");
            if(s.role()==Role.ADMIN) out.println("ADMIN: 20. Save plan\n21. Retire plan\n22. Add SIM\n23. SIM inventory\n24. Retire SIM\n25. Suspend/reactivate subscription\n26. Enable/disable customer\n27. Import usage event\n28. Generate monthly bill\n29. Update complaint\n30. Reports/export");
            try {
                String option=ask("Option");if(option.equals("0")) return;
                switch(option) {
                    case "1":rows(subs.customers(s));break;
                    case "2":rows(subs.plans(s,ask("Search name/code (blank for all)"),amount("Minimum rental"),amount("Maximum rental"),amount("Minimum data GB"),yes("Sort ascending")));break;
                    case "3":rows(subs.compare(s,id("First plan ID"),id("Second plan ID")));break;
                    case "4":rows(subs.subscriptions(s));break;
                    case "5":out.println("Connection created: "+subs.connect(s,s.role()==Role.ADMIN?id("Customer ID"):null,id("Plan ID"),ask("SIM type PHYSICAL_SIM/ESIM")));break;
                    case "6":subs.changePlan(s,id("Subscription ID"),id("New plan ID"),ask("Reason"));out.println("Plan changed; monthly pricing snapshots already opened remain fixed.");break;
                    case "7":subs.addon(s,id("Subscription ID"),id("Add-on plan ID"),yes("Activate"));out.println("Add-on updated; opened monthly snapshots remain fixed.");break;
                    case "8":long sub=id("Subscription ID");rows(subs.history(s,sub));rows(subs.addons(s,sub));break;
                    case "9":List<Row> usage=billing.usage(s,id("Subscription ID"),month());rows(usage);
                        usage.stream().filter(u->"PROCESSED".equals(u.text("status"))).collect(java.util.stream.Collectors.groupingBy(u->u.text("usage_type")+"/"+u.text("unit"),java.util.stream.Collectors.reducing(BigDecimal.ZERO,u->u.decimal("quantity"),BigDecimal::add))).forEach((k,v)->out.println(k+" total: "+v));break;
                    case "10":rows(billing.bills(s));break;
                    case "11":out.println("Record an externally confirmed payment. This does not charge an account.");out.println("Payment recorded: "+billing.pay(s,id("Bill ID"),amount("Full bill amount"),choice("Mode",PaymentMode.class),ask("Confirmed transaction reference (never enter card data)")));break;
                    case "12":rows(billing.payments(s));break;
                    case "13":out.println("Complaint created: "+complaints.create(s,id("Subscription ID"),choice("Category",Category.class),ask("Description"),choice("Priority",Priority.class)));break;
                    case "14":rows(complaints.list(s));break;
                    case "15":rows(complaints.inbox(s));String read=ask("Notification ID to mark read (blank to return)");if(!read.isBlank()) complaints.readNotification(s,Long.parseLong(read));break;
                    case "16":rows(complaints.loginHistory(s));break;
                    case "17":subs.updateProfile(s,id("Customer ID"),ask("Email"),ask("Contact mobile"),ask("Address"),ask("City"),ask("Country"));out.println("Profile updated.");break;
                    case "20":savePlan(s);break;
                    case "21":subs.retirePlan(s,id("Plan ID"));out.println("Plan retired.");break;
                    case "22":out.println("SIM added: "+subs.addSim(s,ask("SIM number"),ask("Mobile number"),ask("SIM type PHYSICAL_SIM/ESIM")));break;
                    case "23":rows(subs.sims(s));break;
                    case "24":subs.retireSim(s,id("SIM ID"));out.println("SIM retired.");break;
                    case "25":subs.subscriptionStatus(s,id("Subscription ID"),yes("Reactivate"));out.println("Subscription status updated.");break;
                    case "26":subs.customerStatus(s,id("Customer ID"),yes("Enable"));out.println("Customer status updated; sessions revoked.");break;
                    case "27":out.println("Usage queued: "+billing.recordUsage(s,ask("Unique event reference"),id("Subscription ID"),LocalDateTime.parse(ask("Usage date YYYY-MM-DDTHH:MM:SS")),choice("Usage type",UsageType.class),amount("Quantity"),ask("Unit GB/MINUTES/COUNT")));break;
                    case "28":out.println("Bill ID: "+billing.generateBill(s,id("Subscription ID"),month(),amount("Discount")));break;
                    case "29":complaints.update(s,id("Complaint ID"),ask("Status IN_PROGRESS/RESOLVED"),ask("Resolution (required when resolving)"));out.println("Complaint updated.");break;
                    case "30":YearMonth m=month();BigDecimal min=amount("Minimum plan price"),max=amount("Maximum plan price");reports.generate(s,m,min,max).forEach(t->out.println(ReportService.Format.TEXT.writer().render(t)));reports.export(s,m,reportDirectory,min,max).forEach(out::println);break;
                    default:out.println("Unknown option.");continue;
                }
            } catch(EndInput end) {return;}catch(RuntimeException failure) {out.println("Could not complete action: "+safeMessage(failure));}
        }
    }
    private void savePlan(Session s) {
        String existing=ask("Existing plan ID (blank for new)");
        Plan p=new Plan(ask("Code"),ask("Name"),ask("Type PREPAID/POSTPAID/ADDON"),amount("Monthly rental"),amount("Data allowance GB"),integer("Voice minutes (-1 unlimited)"),integer("SMS allowance"),integer("Validity days"),yes("International roaming"),yes("Active"),integer("Minimum tenure days before change"),yes("Allow prepaid/postpaid conversion"),amount("Overage per GB"),amount("Overage per voice minute"),amount("Overage per SMS"),amount("Roaming per GB"));
        out.println("Plan saved: "+subs.savePlan(s,existing.isBlank()?null:Long.valueOf(existing),p));
    }
}
