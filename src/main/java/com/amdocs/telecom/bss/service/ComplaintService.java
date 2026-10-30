package com.amdocs.telecom.bss.service;

import com.amdocs.telecom.bss.dao.BssStore;
import com.amdocs.telecom.bss.dao.BssStore.*;
import com.amdocs.telecom.bss.model.Domain.*;
import java.util.*;
import static com.amdocs.telecom.bss.validation.Rules.*;
import static com.amdocs.telecom.bss.service.AuthenticationService.audit;

public final class ComplaintService {
    private final BssStore store;private final AuthenticationService auth;private final SubscriptionService subscriptions;
    public ComplaintService(BssStore store,AuthenticationService auth,SubscriptionService subscriptions) {this.store=store;this.auth=auth;this.subscriptions=subscriptions;}
    public long create(Session s,long subscription,Category category,String description,Priority priority) {
        Objects.requireNonNull(category,"Category required");Objects.requireNonNull(priority,"Priority required");String detail=text(description,2000,"Description");
        return store.transaction(tx->{Row a=auth.actor(tx,s),sub=subscriptions.owned(tx,a,subscription,true);
            long id=tx.insert("INSERT INTO bss_complaints(complaint_number,customer_id,subscription_id,category,description,priority,status) VALUES(?,?,?,?,?,?,'OPEN')",
                    "CMP-"+UUID.randomUUID(),sub.id("customer_id"),subscription,category.name(),detail,priority.name());
            audit(tx,a,"OPEN_COMPLAINT",id,"Category "+category);BillingService.notify(tx,sub.id("customer_id"),"Complaint "+id+" opened");return id;});
    }
    public void update(Session s,long id,String status,String resolution) {
        String state=choice(status,"IN_PROGRESS","RESOLVED");String answer="RESOLVED".equals(state)?text(resolution,2000,"Resolution"):"";
        store.transaction(tx->{Row a=auth.admin(tx,s),c=tx.required("SELECT * FROM bss_complaints WHERE id=? FOR UPDATE",id);
            require(!"RESOLVED".equals(c.text("status")),"Resolved complaints cannot be changed");
            tx.update("UPDATE bss_complaints SET status=?,resolution=?,updated_at=? WHERE id=?",state,answer,auth.now(),id);
            audit(tx,a,"UPDATE_COMPLAINT",id,state);BillingService.notify(tx,c.id("customer_id"),"Complaint "+c.text("complaint_number")+": "+state);return null;});
    }
    public List<Row> list(Session s) {return store.transaction(tx->{Row a=auth.actor(tx,s);
        return "ADMIN".equals(a.text("role"))?tx.query("SELECT * FROM bss_complaints ORDER BY id"):tx.query("SELECT * FROM bss_complaints WHERE customer_id=? ORDER BY id",auth.customer(tx,a).id("id"));});}
    public List<Row> inbox(Session s) {return store.transaction(tx->{Row a=auth.actor(tx,s);return tx.query("SELECT id,message,status,created_at FROM bss_notifications WHERE user_id=? AND status IN ('DELIVERED','READ') ORDER BY id",a.id("id"));});}
    public void readNotification(Session s,long id) {store.transaction(tx->{Row a=auth.actor(tx,s);
        require(tx.update("UPDATE bss_notifications SET status='READ',updated_at=? WHERE id=? AND user_id=? AND status IN ('DELIVERED','READ')",auth.now(),id,a.id("id"))==1,"Notification not found");return null;});}
    public List<Row> loginHistory(Session s) {return store.transaction(tx->{Row a=auth.actor(tx,s);
        return tx.query("SELECT username,outcome,login_time,logout_time FROM bss_login_history WHERE user_id=? ORDER BY id",a.id("id"));});}
}
