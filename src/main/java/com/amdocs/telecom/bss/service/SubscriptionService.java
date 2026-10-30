package com.amdocs.telecom.bss.service;

import com.amdocs.telecom.bss.dao.BssStore;
import com.amdocs.telecom.bss.dao.BssStore.*;
import com.amdocs.telecom.bss.model.Domain.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import static com.amdocs.telecom.bss.validation.Rules.*;
import static com.amdocs.telecom.bss.service.AuthenticationService.audit;

public final class SubscriptionService {
    private final BssStore store;
    private final AuthenticationService auth;
    public SubscriptionService(BssStore store,AuthenticationService auth) { this.store=store;this.auth=auth; }
    private LocalDate today() { return auth.now().toLocalDate(); }

    public long savePlan(Session s,Long id,Plan p) {
        String code=text(p.code(),40,"Plan code"),name=text(p.name(),100,"Plan name"),type=choice(p.type(),"PREPAID","POSTPAID","ADDON");
        nonnegative(p.rental(),"Rental");nonnegative(p.dataGb(),"Data allowance");
        nonnegative(p.dataRate(),"Data rate");nonnegative(p.voiceRate(),"Voice rate");nonnegative(p.smsRate(),"SMS rate");nonnegative(p.roamingRate(),"Roaming rate");
        require(p.dataGb().scale()<=4 && p.dataRate().scale()<=4 && p.voiceRate().scale()<=4 && p.smsRate().scale()<=4 && p.roamingRate().scale()<=4,"Allowances and rates allow at most four decimal places");
        require(p.rental().scale()<=2,"Rental allows at most two decimal places");
        require(p.voiceMinutes()>=-1 && p.sms()>=0 && p.validityDays()>0 && p.minimumTenureDays()>=0,"Invalid plan allowances or validity; -1 means unlimited voice");
        return store.transaction(tx->{
            Row actor=auth.admin(tx,s);
            Object[] values={code,name,type,p.rental(),p.dataGb(),p.voiceMinutes(),p.sms(),p.validityDays(),p.roaming(),p.active(),p.minimumTenureDays(),p.allowTypeChange(),p.dataRate(),p.voiceRate(),p.smsRate(),p.roamingRate()};
            long key;
            if(id==null) key=tx.insert("INSERT INTO bss_plans(plan_code,plan_name,plan_type,monthly_rental,data_gb,voice_minutes,sms_allowance,validity_days,roaming,active,minimum_tenure_days,allow_type_change,data_rate,voice_rate,sms_rate,roaming_rate) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",values);
            else {
                tx.required("SELECT id FROM bss_plans WHERE id=? FOR UPDATE",id);
                // Existing contracts keep their pricing; retire the old plan and create a version instead.
                require(tx.query("SELECT id FROM bss_subscriptions WHERE plan_id=? UNION SELECT subscription_id AS id FROM bss_addons WHERE plan_id=? UNION SELECT id FROM bss_subscription_history WHERE old_plan_id=? OR new_plan_id=? UNION SELECT id FROM bss_addon_history WHERE plan_id=?",id,id,id,id,id).isEmpty(),"This plan has subscribers or historical contracts; retire it and create a new plan version");
                List<Object> args=new ArrayList<>(Arrays.asList(values));args.add(auth.now());args.add(id);
                tx.update("UPDATE bss_plans SET plan_code=?,plan_name=?,plan_type=?,monthly_rental=?,data_gb=?,voice_minutes=?,sms_allowance=?,validity_days=?,roaming=?,active=?,minimum_tenure_days=?,allow_type_change=?,data_rate=?,voice_rate=?,sms_rate=?,roaming_rate=?,updated_at=? WHERE id=?",args.toArray());key=id;
            }
            audit(tx,actor,"SAVE_PLAN",key,"Catalogue updated");return key;
        });
    }
    public void retirePlan(Session s,long id) {
        store.transaction(tx->{Row a=auth.admin(tx,s);tx.required("SELECT id FROM bss_plans WHERE id=? FOR UPDATE",id);
            tx.update("UPDATE bss_plans SET active=FALSE,updated_at=? WHERE id=?",auth.now(),id);audit(tx,a,"RETIRE_PLAN",id,"Existing contracts preserved");return null;});
    }
    public List<Row> plans(Session s,String search,BigDecimal minPrice,BigDecimal maxPrice,BigDecimal minData,boolean ascending) {
        nonnegative(minPrice,"Minimum price");nonnegative(maxPrice,"Maximum price");nonnegative(minData,"Minimum data");
        require(minPrice.compareTo(maxPrice)<=0,"Minimum price must not exceed maximum price");
        return store.transaction(tx->{
            Row a=auth.actor(tx,s); boolean admin="ADMIN".equals(a.text("role"));
            Predicate<Row> visible=p->admin || p.flag("active");
            Predicate<Row> matching=p->(p.text("plan_name")+" "+p.text("plan_code")).toLowerCase(Locale.ROOT).contains(Objects.toString(search,"").toLowerCase(Locale.ROOT));
            Comparator<Row> comparator=Comparator.comparing(p->p.decimal("monthly_rental"));
            if(!ascending) comparator=comparator.reversed();
            return tx.query("SELECT * FROM bss_plans").stream().filter(visible.and(matching))
                    .filter(p->p.decimal("monthly_rental").compareTo(minPrice)>=0 && p.decimal("monthly_rental").compareTo(maxPrice)<=0)
                    .filter(p->p.decimal("data_gb").compareTo(minData)>=0).sorted(comparator.thenComparing(p->p.text("plan_code"))).collect(Collectors.toList());
        });
    }
    public List<Row> compare(Session s,long first,long second) {
        require(first!=second,"Select two different plans");
        List<Row> rows=plans(s,"",BigDecimal.ZERO,new BigDecimal("999999999"),BigDecimal.ZERO,true).stream().filter(p->p.id("id")==first||p.id("id")==second).collect(Collectors.toList());
        require(rows.size()==2,"Both plans must be visible and available");return rows;
    }
    public long addSim(Session s,String number,String mobile,String type) {
        String sim=text(number,32,"SIM number"),phone=mobile(mobile),kind=choice(type,"PHYSICAL_SIM","ESIM");
        require(sim.matches("[0-9]{10,32}"),"SIM number must contain 10-32 digits");
        return store.transaction(tx->{Row a=auth.admin(tx,s);
            long id=tx.insert("INSERT INTO bss_sims(sim_number,mobile_number,sim_type,status) VALUES(?,?,?,'AVAILABLE')",sim,phone,kind);
            audit(tx,a,"ADD_SIM",id,"SIM inventory created");return id;});
    }
    public List<Row> sims(Session s) { return store.transaction(tx->{auth.admin(tx,s);return tx.query("SELECT * FROM bss_sims ORDER BY id");}); }
    public void retireSim(Session s,long id) {
        store.transaction(tx->{Row a=auth.admin(tx,s);Row sim=tx.required("SELECT * FROM bss_sims WHERE id=? FOR UPDATE",id);
            require("AVAILABLE".equals(sim.text("status")),"Only an unassigned SIM can be retired");
            tx.update("UPDATE bss_sims SET status='RETIRED',updated_at=? WHERE id=?",auth.now(),id);audit(tx,a,"RETIRE_SIM",id,"Unassigned SIM retired");return null;});
    }
    public long connect(Session s,Long customerId,long planId,String simType) {
        String kind=choice(simType,"PHYSICAL_SIM","ESIM");
        return store.transaction(tx->{
            Row a=auth.actor(tx,s);
            long customer="ADMIN".equals(a.text("role")) ? Objects.requireNonNull(customerId,"Customer ID required") : auth.customer(tx,a).id("id");
            tx.required("SELECT c.id FROM bss_customers c JOIN bss_users u ON u.id=c.user_id WHERE c.id=? AND u.status='ACTIVE' FOR UPDATE",customer);
            Row plan=tx.required("SELECT * FROM bss_plans WHERE id=? FOR UPDATE",planId);
            require(plan.flag("active") && !"ADDON".equals(plan.text("plan_type")),"Choose an active base plan");
            Row sim=tx.required("SELECT * FROM bss_sims WHERE status='AVAILABLE' AND sim_type=? ORDER BY id LIMIT 1 FOR UPDATE",kind);
            long id=tx.insert("INSERT INTO bss_subscriptions(subscription_number,customer_id,sim_id,plan_id,subscription_type,status,activation_date,plan_changed_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",
                    "SUB-"+UUID.randomUUID(),customer,sim.id("id"),planId,plan.text("plan_type"),today(),today());
            tx.update("UPDATE bss_sims SET status='ASSIGNED',updated_at=? WHERE id=?",auth.now(),sim.id("id"));
            history(tx,a,id,null,planId,"Initial subscription");audit(tx,a,"SUBSCRIBE",id,"Connection activated");return id;
        });
    }
    /** Ownership is checked inside every transaction, never entrusted to the console. */
    public Row owned(Tx tx,Row a,long id,boolean lock) throws SQLException {
        Row sub=tx.required("SELECT * FROM bss_subscriptions WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(!"ADMIN".equals(a.text("role"))) require(sub.id("customer_id")==auth.customer(tx,a).id("id"),"Record not found or access denied");
        return sub;
    }
    public void changePlan(Session s,long subscription,long target,String reason) {
        String why=text(reason,300,"Change reason");
        store.transaction(tx->{Row a=auth.actor(tx,s),sub=owned(tx,a,subscription,true);
            require("ACTIVE".equals(sub.text("status")),"Only active subscriptions may change plan");
            Row old=tx.required("SELECT * FROM bss_plans WHERE id=?",sub.id("plan_id"));
            Row next=tx.required("SELECT * FROM bss_plans WHERE id=? FOR UPDATE",target);
            require(target!=sub.id("plan_id"),"Already subscribed to this plan");
            require(next.flag("active") && !"ADDON".equals(next.text("plan_type")),"Target must be an active base plan");
            require(!today().isBefore(sub.date("plan_changed_at").plusDays(old.integer("minimum_tenure_days"))),"Current plan minimum tenure has not elapsed");
            require(old.text("plan_type").equals(next.text("plan_type")) || (old.flag("allow_type_change") && next.flag("allow_type_change")),"These plans do not permit prepaid/postpaid conversion");
            tx.update("UPDATE bss_subscriptions SET plan_id=?,subscription_type=?,plan_changed_at=?,updated_at=? WHERE id=?",target,next.text("plan_type"),today(),auth.now(),subscription);
            history(tx,a,subscription,old.id("id"),target,why);audit(tx,a,"CHANGE_PLAN",subscription,why);return null;});
    }
    private void history(Tx tx,Row a,long subscription,Long old,long next,String reason) throws SQLException {
        tx.insert("INSERT INTO bss_subscription_history(subscription_id,old_plan_id,new_plan_id,change_reason,changed_by,change_date) VALUES(?,?,?,?,?,?)",subscription,old,next,reason,a.id("id"),auth.now());
    }
    public void addon(Session s,long subscription,long planId,boolean active) {
        store.transaction(tx->{Row a=auth.actor(tx,s),sub=owned(tx,a,subscription,true);
            require("ACTIVE".equals(sub.text("status")),"Only active subscriptions can change add-ons");
            Row plan=tx.required("SELECT * FROM bss_plans WHERE id=? FOR UPDATE",planId);
            require("ADDON".equals(plan.text("plan_type")) && (!active || plan.flag("active")),"Choose an active add-on plan");
            if(tx.one("SELECT plan_id FROM bss_addons WHERE subscription_id=? AND plan_id=?",subscription,planId).isPresent())
                tx.update("UPDATE bss_addons SET active=?,updated_at=? WHERE subscription_id=? AND plan_id=?",active,auth.now(),subscription,planId);
            else { require(active,"Add-on is not active");tx.update("INSERT INTO bss_addons(subscription_id,plan_id,active) VALUES(?,?,TRUE)",subscription,planId); }
            tx.insert("INSERT INTO bss_addon_history(subscription_id,plan_id,active,change_date,changed_by) VALUES(?,?,?,?,?)",subscription,planId,active,auth.now(),a.id("id"));
            history(tx,a,subscription,sub.id("plan_id"),sub.id("plan_id"),(active?"Activate":"Deactivate")+" add-on "+plan.text("plan_code"));
            audit(tx,a,"CHANGE_ADDON",subscription,plan.text("plan_code")+" active="+active);return null;});
    }
    public void subscriptionStatus(Session s,long id,boolean active) {
        store.transaction(tx->{Row a=auth.admin(tx,s);owned(tx,a,id,true);
            if(active) require(tx.query("SELECT id FROM bss_bills WHERE subscription_id=? AND status='UNPAID' AND due_date<?",id,today()).isEmpty(),"Pay overdue bills before reactivation");
            tx.update("UPDATE bss_subscriptions SET status=?,updated_at=? WHERE id=?",active?"ACTIVE":"SUSPENDED",auth.now(),id);
            audit(tx,a,"SUBSCRIPTION_STATUS",id,active?"ACTIVE":"SUSPENDED");return null;});
    }
    public List<Row> subscriptions(Session s) {
        return store.transaction(tx->{Row a=auth.actor(tx,s);
            String sql="SELECT s.*,p.plan_name,m.mobile_number,m.sim_number,m.sim_type FROM bss_subscriptions s JOIN bss_plans p ON p.id=s.plan_id JOIN bss_sims m ON m.id=s.sim_id";
            return "ADMIN".equals(a.text("role"))?tx.query(sql+" ORDER BY s.id"):tx.query(sql+" WHERE s.customer_id=? ORDER BY s.id",auth.customer(tx,a).id("id"));});
    }
    public List<Row> history(Session s,long id) { return store.transaction(tx->{Row a=auth.actor(tx,s);owned(tx,a,id,false);return tx.query("SELECT * FROM bss_subscription_history WHERE subscription_id=? ORDER BY id",id);}); }
    public List<Row> addons(Session s,long id) { return store.transaction(tx->{Row a=auth.actor(tx,s);owned(tx,a,id,false);return tx.query("SELECT p.plan_code,p.plan_name,a.active FROM bss_addons a JOIN bss_plans p ON p.id=a.plan_id WHERE a.subscription_id=?",id);}); }
    public List<Row> customers(Session s) { return store.transaction(tx->{
        Row a=auth.actor(tx,s);String sql="SELECT c.*,u.username,u.email,u.status,u.last_login FROM bss_customers c JOIN bss_users u ON u.id=c.user_id";
        return "ADMIN".equals(a.text("role"))?tx.query(sql+" ORDER BY c.id"):tx.query(sql+" WHERE c.user_id=?",a.id("id"));}); }
    public void updateProfile(Session s,long id,String email,String mobile,String address,String city,String country) {
        String mail=email(email),phone=mobile(mobile),street=text(address,300,"Address"),town=text(city,100,"City"),nation=text(country,100,"Country");
        store.transaction(tx->{Row a=auth.actor(tx,s);Row c=tx.required("SELECT * FROM bss_customers WHERE id=? FOR UPDATE",id);
            require("ADMIN".equals(a.text("role")) || c.id("user_id")==a.id("id"),"Access denied");
            tx.update("UPDATE bss_customers SET mobile_number=?,address=?,city=?,country=?,updated_at=? WHERE id=?",phone,street,town,nation,auth.now(),id);
            tx.update("UPDATE bss_users SET email=?,updated_at=? WHERE id=?",mail,auth.now(),c.id("user_id"));
            audit(tx,a,"UPDATE_PROFILE",id,"Contact profile updated");return null;});
    }
    public void customerStatus(Session s,long id,boolean active) {
        store.transaction(tx->{Row a=auth.admin(tx,s),c=tx.required("SELECT user_id FROM bss_customers WHERE id=? FOR UPDATE",id);
            tx.update("UPDATE bss_users SET status=?,credential_version=credential_version+1,updated_at=? WHERE id=?",active?"ACTIVE":"DISABLED",auth.now(),c.id("user_id"));
            tx.update("DELETE FROM bss_sessions WHERE user_id=?",c.id("user_id"));audit(tx,a,"CUSTOMER_STATUS",id,active?"ACTIVE":"DISABLED");return null;});
    }
}
