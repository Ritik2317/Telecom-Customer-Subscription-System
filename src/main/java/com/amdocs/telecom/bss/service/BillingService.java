package com.amdocs.telecom.bss.service;

import com.amdocs.telecom.bss.dao.BssStore;
import com.amdocs.telecom.bss.dao.BssStore.*;
import com.amdocs.telecom.bss.model.Domain.*;
import java.math.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.amdocs.telecom.bss.validation.Rules.*;
import static com.amdocs.telecom.bss.service.AuthenticationService.audit;

/** Usage/billing/payment share the subscription row lock, serializing monthly financial changes. */
public final class BillingService {
    private final BssStore store;
    private final AuthenticationService auth;
    private final SubscriptionService subscriptions;
    private final BigDecimal taxRate;
    private final int dueDays;
    public BillingService(BssStore store,AuthenticationService auth,SubscriptionService subscriptions,BigDecimal taxRate,int dueDays) {
        this.store=store;this.auth=auth;this.subscriptions=subscriptions;
        require(taxRate!=null && taxRate.signum()>=0 && taxRate.compareTo(BigDecimal.ONE)<=0,"Tax rate must be between 0 and 1");
        require(dueDays>=1 && dueDays<=365,"Bill due interval must be 1-365 days");this.taxRate=taxRate;this.dueDays=dueDays;
    }
    private Row terms(Tx tx,Row sub,YearMonth month) throws SQLException {
        Optional<Row> existing=tx.one("SELECT * FROM bss_month_terms WHERE subscription_id=? AND billing_month=?",sub.id("id"),month.toString());
        if(existing.isPresent()) return existing.get();
        LocalDateTime end=month.plusMonths(1).atDay(1).atStartOfDay();
        LocalDateTime start=month.atDay(1).atStartOfDay();
        Optional<Row> prior=tx.one("SELECT p.* FROM bss_subscription_history h JOIN bss_plans p ON p.id=h.new_plan_id WHERE h.subscription_id=? AND h.change_date<=? ORDER BY h.change_date DESC,h.id DESC LIMIT 1",sub.id("id"),start);
        Row plan=prior.isPresent()?prior.get():tx.required("SELECT p.* FROM bss_subscription_history h JOIN bss_plans p ON p.id=h.new_plan_id WHERE h.subscription_id=? AND h.change_date<? ORDER BY h.change_date,h.id LIMIT 1",sub.id("id"),end);
        List<Row> addons=tx.query("SELECT p.* FROM bss_addon_history h JOIN bss_plans p ON p.id=h.plan_id WHERE h.subscription_id=? AND h.change_date<=? AND h.active=TRUE AND NOT EXISTS (SELECT 1 FROM bss_addon_history n WHERE n.subscription_id=h.subscription_id AND n.plan_id=h.plan_id AND n.change_date<=? AND n.id>h.id)",sub.id("id"),start,start);
        BigDecimal rental=plan.decimal("monthly_rental"),data=plan.decimal("data_gb");int voice=plan.integer("voice_minutes"),sms=plan.integer("sms_allowance");boolean roaming=plan.flag("roaming");
        for(Row addon:addons) { rental=rental.add(addon.decimal("monthly_rental"));data=data.add(addon.decimal("data_gb"));sms+=addon.integer("sms_allowance");voice=voice==-1||addon.integer("voice_minutes")==-1?-1:voice+addon.integer("voice_minutes");roaming|=addon.flag("roaming"); }
        tx.update("INSERT INTO bss_month_terms(subscription_id,billing_month,rental,data_gb,voice_minutes,sms_allowance,roaming,data_rate,voice_rate,sms_rate,roaming_rate) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                sub.id("id"),month.toString(),rental,data,voice,sms,roaming,plan.decimal("data_rate"),plan.decimal("voice_rate"),plan.decimal("sms_rate"),plan.decimal("roaming_rate"));
        return tx.required("SELECT * FROM bss_month_terms WHERE subscription_id=? AND billing_month=?",sub.id("id"),month.toString());
    }
    public long recordUsage(Session session,String reference,long subscription,LocalDateTime date,UsageType type,BigDecimal quantity,String unit) {
        String ref=text(reference,100,"Usage event reference");nonnegative(quantity,"Usage quantity");require(quantity.signum()>0 && quantity.scale()<=4,"Quantity must be positive with at most four decimals");
        require(date!=null && !date.isAfter(auth.now()),"Usage date cannot be in the future");Objects.requireNonNull(type,"Usage type required");
        String expected=type==UsageType.DATA||type==UsageType.ROAMING?"GB":type==UsageType.VOICE?"MINUTES":"COUNT";
        require(expected.equalsIgnoreCase(unit),"Use canonical unit "+expected+" (convert MB to GB before importing)");
        if(type==UsageType.SMS) require(quantity.stripTrailingZeros().scale()<=0,"SMS quantity must be a whole number");
        return store.transaction(tx->{
            Row a=auth.admin(tx,session),sub=subscriptions.owned(tx,a,subscription,true);
            require("ACTIVE".equals(sub.text("status")),"Usage requires an active subscription");
            require(!date.toLocalDate().isBefore(sub.date("activation_date")),"Usage predates activation");
            require("POSTPAID".equals(sub.text("subscription_type")) || !date.toLocalDate().isBefore(sub.date("plan_changed_at")),"Prepaid usage must belong to the current plan period");
            YearMonth month=YearMonth.from(date); ensureUnbilled(tx,subscription,month);
            Row terms=terms(tx,sub,month);
            require(type!=UsageType.ROAMING || terms.flag("roaming"),"International roaming is not enabled");
            if("PREPAID".equals(sub.text("subscription_type"))) {
                Row plan=tx.required("SELECT validity_days FROM bss_plans WHERE id=?",sub.id("plan_id"));
                require(date.toLocalDate().isBefore(sub.date("plan_changed_at").plusDays(plan.integer("validity_days"))),"Prepaid validity has expired");
            }
            long id=tx.insert("INSERT INTO bss_usage(event_reference,subscription_id,usage_date,usage_type,quantity,unit,status) VALUES(?,?,?,?,?,?,'PENDING')",ref,subscription,date,type.name(),quantity,expected);
            audit(tx,a,"IMPORT_USAGE",id,"Usage queued durably");return id;
        });
    }
    private void ensureUnbilled(Tx tx,long id,YearMonth month) throws SQLException {
        require(tx.query("SELECT id FROM bss_bills WHERE subscription_id=? AND billing_month=?",id,month.toString()).isEmpty(),"This month is already billed; late usage requires a separate adjustment process");
    }
    private void process(Tx tx,Row sub,YearMonth month) throws SQLException {
        Row t=terms(tx,sub,month);
        List<Row> usage=tx.query("SELECT * FROM bss_usage WHERE subscription_id=? AND usage_date>=? AND usage_date<? ORDER BY usage_date,id",sub.id("id"),month.atDay(1).atStartOfDay(),month.plusMonths(1).atDay(1).atStartOfDay());
        Map<String,BigDecimal> used=new HashMap<>();
        for(Row row:usage) {
            String type=row.text("usage_type");BigDecimal before=used.getOrDefault(type,BigDecimal.ZERO),after=before.add(row.decimal("quantity"));used.put(type,after);
            BigDecimal allowance=type.equals("DATA")?t.decimal("data_gb"):type.equals("VOICE")?BigDecimal.valueOf(t.integer("voice_minutes")):type.equals("SMS")?BigDecimal.valueOf(t.integer("sms_allowance")):BigDecimal.ZERO;
            BigDecimal rate=t.decimal(type.toLowerCase(Locale.ROOT)+"_rate");
            BigDecimal charge=allowance.signum()<0?BigDecimal.ZERO:money(after.subtract(allowance).max(BigDecimal.ZERO).multiply(rate)).subtract(money(before.subtract(allowance).max(BigDecimal.ZERO).multiply(rate)));
            tx.update("UPDATE bss_usage SET charge=?,status='PROCESSED',updated_at=? WHERE id=?",money(charge),auth.now(),row.id("id"));
        }
    }
    public void processPendingUsage() {
        List<Row> pending=store.transaction(tx->tx.query("SELECT DISTINCT subscription_id FROM bss_usage WHERE status='PENDING'"));
        for(Row candidate:pending) store.transaction(tx->{
            Row sub=tx.required("SELECT * FROM bss_subscriptions WHERE id=? FOR UPDATE",candidate.id("subscription_id"));
            Set<YearMonth> months=new HashSet<>();for(Row u:tx.query("SELECT usage_date FROM bss_usage WHERE subscription_id=? AND status='PENDING'",sub.id("id"))) months.add(YearMonth.from(u.time("usage_date")));
            for(YearMonth month:months) process(tx,sub,month);return null;
        });
    }
    private long generate(Tx tx,Row sub,YearMonth month,BigDecimal discount,String actor) throws SQLException {
        Optional<Row> existing=tx.one("SELECT id FROM bss_bills WHERE subscription_id=? AND billing_month=?",sub.id("id"),month.toString());
        if(existing.isPresent()) return existing.get().id("id");
        require(month.isBefore(YearMonth.from(auth.now())),"Only completed billing months can be invoiced");
        require(!month.isBefore(YearMonth.from(sub.date("activation_date"))),"Billing month predates subscription");
        Row t=terms(tx,sub,month);process(tx,sub,month);
        BigDecimal usage=tx.required("SELECT COALESCE(SUM(charge),0) AS amount FROM bss_usage WHERE subscription_id=? AND usage_date>=? AND usage_date<?",sub.id("id"),month.atDay(1).atStartOfDay(),month.plusMonths(1).atDay(1).atStartOfDay()).decimal("amount");
        BigDecimal subtotal=t.decimal("rental").add(usage);require(discount.compareTo(subtotal)<=0,"Discount exceeds subtotal");
        BigDecimal tax=money(subtotal.subtract(discount).multiply(taxRate)),total=money(subtotal.subtract(discount).add(tax));
        long id=tx.insert("INSERT INTO bss_bills(bill_number,subscription_id,billing_month,plan_rental,usage_charges,tax_amount,discount,total_amount,due_date,status) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "INV-"+month+"-"+sub.id("id"),sub.id("id"),month.toString(),t.decimal("rental"),usage,tax,discount,total,auth.now().toLocalDate().plusDays(dueDays),total.signum()==0?"PAID":"UNPAID");
        notify(tx,sub.id("customer_id"),"Bill INV-"+month+"-"+sub.id("id")+" generated: "+total);
        audit(tx,actor,"GENERATE_BILL",id,"Billing month "+month);return id;
    }
    public long generateBill(Session s,long subscription,YearMonth month,BigDecimal discount) {
        nonnegative(discount,"Discount");require(discount.scale()<=2,"Discount allows at most two decimals");
        return store.transaction(tx->{Row a=auth.admin(tx,s),sub=subscriptions.owned(tx,a,subscription,true);return generate(tx,sub,month,discount,"USER:"+a.id("id"));});
    }
    /** Scheduler entry point; the previous completed month is generated exactly once per subscription. */
    public void generateMonthlyBills() {
        YearMonth month=YearMonth.from(auth.now()).minusMonths(1);
        List<Row> subs=store.transaction(tx->tx.query("SELECT id FROM bss_subscriptions WHERE activation_date<?",month.plusMonths(1).atDay(1)));
        for(Row r:subs) store.transaction(tx->{Row sub=tx.required("SELECT * FROM bss_subscriptions WHERE id=? FOR UPDATE",r.id("id"));return generate(tx,sub,month,BigDecimal.ZERO,"SYSTEM");});
    }
    public long pay(Session s,long bill,BigDecimal amount,PaymentMode mode,String reference) {
        nonnegative(amount,"Payment amount");require(amount.signum()>0 && amount.scale()<=2,"Payment must be positive, with at most two decimals");
        Objects.requireNonNull(mode,"Payment mode required");String ref=text(reference,100,"Confirmed external transaction reference");
        return store.transaction(tx->{
            Row a=auth.actor(tx,s);
            Row initial=tx.required("SELECT subscription_id FROM bss_bills WHERE id=?",bill);
            Row sub=subscriptions.owned(tx,a,initial.id("subscription_id"),true);
            Row b=tx.required("SELECT * FROM bss_bills WHERE id=? FOR UPDATE",bill);
            require("UNPAID".equals(b.text("status")),"Bill is already paid");require(amount.compareTo(b.decimal("total_amount"))==0,"Payment must equal the outstanding bill amount");
            long id=tx.insert("INSERT INTO bss_payments(transaction_reference,bill_id,customer_id,amount,payment_mode,status,payment_date) VALUES(?,?,?,?,?,'CONFIRMED',?)",ref,bill,sub.id("customer_id"),amount,mode.name(),auth.now());
            tx.update("UPDATE bss_bills SET status='PAID',updated_at=? WHERE id=?",auth.now(),bill);
            notify(tx,sub.id("customer_id"),"Confirmed payment "+ref+" recorded for bill "+b.text("bill_number"));
            audit(tx,a,"CONFIRM_PAYMENT",id,"Bill "+bill+"; mode "+mode);
            return id;
        });
    }
    static void notify(Tx tx,long customer,String message) throws SQLException {
        Row c=tx.required("SELECT user_id FROM bss_customers WHERE id=?",customer);
        tx.insert("INSERT INTO bss_notifications(user_id,message,status) VALUES(?,?,'PENDING')",c.id("user_id"),message);
    }
    public List<Row> usage(Session s,long sub,YearMonth month) {
        return store.transaction(tx->{Row a=auth.actor(tx,s);subscriptions.owned(tx,a,sub,false);
            return tx.query("SELECT * FROM bss_usage WHERE subscription_id=? AND usage_date>=? AND usage_date<? ORDER BY usage_date,id",sub,month.atDay(1).atStartOfDay(),month.plusMonths(1).atDay(1).atStartOfDay());});
    }
    public List<Row> bills(Session s) {return financialRows(s,false);}
    public List<Row> payments(Session s) {return financialRows(s,true);}
    private List<Row> financialRows(Session s,boolean payments) {
        return store.transaction(tx->{Row a=auth.actor(tx,s);
            String sql=payments?"SELECT b.* FROM bss_payments b JOIN bss_customers c ON c.id=b.customer_id":"SELECT b.*,c.customer_number FROM bss_bills b JOIN bss_subscriptions sub ON sub.id=b.subscription_id JOIN bss_customers c ON c.id=sub.customer_id";
            return "ADMIN".equals(a.text("role"))?tx.query(sql+" ORDER BY b.id"):tx.query(sql+" WHERE c.user_id=? ORDER BY b.id",a.id("id"));});
    }
    public void monitorAccounts() {
        store.transaction(tx->{
            for(Row s:tx.query("SELECT s.id,s.customer_id FROM bss_subscriptions s WHERE s.status='ACTIVE' AND EXISTS(SELECT 1 FROM bss_bills b WHERE b.subscription_id=s.id AND b.status='UNPAID' AND b.due_date<?) FOR UPDATE",auth.now().toLocalDate())) {
                tx.update("UPDATE bss_subscriptions SET status='OVERDUE',updated_at=? WHERE id=?",auth.now(),s.id("id"));
                notify(tx,s.id("customer_id"),"Subscription suspended for overdue bills");audit(tx,"SYSTEM","OVERDUE",s.id("id"),"Overdue account identified");
            }
            for(Row s:tx.query("SELECT s.id,s.customer_id,s.plan_changed_at,p.validity_days FROM bss_subscriptions s JOIN bss_plans p ON p.id=s.plan_id WHERE s.status='ACTIVE' AND s.subscription_type='PREPAID' FOR UPDATE")) {
                if(!auth.now().toLocalDate().isBefore(s.date("plan_changed_at").plusDays(s.integer("validity_days")))) {
                    tx.update("UPDATE bss_subscriptions SET status='EXPIRED',updated_at=? WHERE id=?",auth.now(),s.id("id"));
                    notify(tx,s.id("customer_id"),"Prepaid validity expired");audit(tx,"SYSTEM","PREPAID_EXPIRED",s.id("id"),"Validity elapsed");
                }
            }
            return null;
        });
    }
    public void deliverNotifications() {
        store.transaction(tx->{List<Object[]> rows=new ArrayList<>();
            for(Row n:tx.query("SELECT id FROM bss_notifications WHERE status='PENDING' FOR UPDATE")) rows.add(new Object[]{TimestampValue.now(auth.now()),n.id("id")});
            tx.batch("UPDATE bss_notifications SET status='DELIVERED',updated_at=? WHERE id=?",rows);return null;});
    }
    private static final class TimestampValue { static java.sql.Timestamp now(LocalDateTime t) {return java.sql.Timestamp.valueOf(t);} }
}
