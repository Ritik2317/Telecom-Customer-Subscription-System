package com.amdocs.telecom.bss.service;

import com.amdocs.telecom.bss.dao.BssStore;
import com.amdocs.telecom.bss.dao.BssStore.*;
import com.amdocs.telecom.bss.model.Domain.*;
import com.amdocs.telecom.security.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.amdocs.telecom.bss.validation.Rules.*;

/** Persistent authentication; failed attempts commit even when login is refused. */
public final class AuthenticationService {
    @FunctionalInterface public interface RecoveryDelivery {
        void send(String email,String code);
        default void checkAvailability() { }
    }
    private final BssStore store;
    private final Clock clock;
    private final RecoveryDelivery delivery;
    private final SecureRandom random=new SecureRandom();
    public AuthenticationService(BssStore store,Clock clock,RecoveryDelivery delivery) {
        this.store=store; this.clock=clock; this.delivery=delivery;
    }
    public LocalDateTime now() { return LocalDateTime.now(clock); }
    public static String digest(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String username(String value) {
        String s=text(value,50,"Username").toLowerCase(Locale.ROOT);
        require(s.matches("[a-z0-9_.-]{3,50}"),"Username must contain 3-50 letters, digits, dots, hyphens or underscores");return s;
    }
    private HashedPassword credential(String username,char[] password) {
        List<String> problems=PasswordPolicy.violations(password,username);
        require(problems.isEmpty(),String.join("; ",problems)); return PasswordHasher.hash(password);
    }
    public long register(Registration r,char[] password) {
        try {
            String name=username(r.username()), email=email(r.email()), mobile=mobile(r.mobile());
            require(r.birthDate()!=null && !r.birthDate().isAfter(LocalDate.now(clock).minusYears(18))
                    && r.birthDate().isAfter(LocalDate.now(clock).minusYears(130)),"Customer must be at least 18 years old with a valid birth date");
            String first=text(r.firstName(),80,"First name"), last=text(r.lastName(),80,"Last name");
            String address=text(r.address(),300,"Address"),city=text(r.city(),100,"City"),country=text(r.country(),100,"Country");
            HashedPassword h=credential(name,password);
            return store.transaction(tx->{
                long user=tx.insert("INSERT INTO bss_users(username,password_hash,salt,email,role) VALUES(?,?,?,?,?)",name,h.getHash(),h.getSalt(),email,"CUSTOMER");
                long id=tx.insert("INSERT INTO bss_customers(customer_number,user_id,first_name,last_name,date_of_birth,mobile_number,address,city,country,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        "CUST-"+UUID.randomUUID(),user,first,last,r.birthDate(),mobile,address,city,country,now(),now());
                audit(tx,"USER:"+user,"REGISTER",id,"Customer registered"); return id;
            });
        } finally { if(password!=null) Arrays.fill(password,'\0'); }
    }
    /** Local operator command only. Does not install shared or default credentials. */
    public void bootstrapAdmin(String username,String email,char[] password) {
        try {
            String name=username(username), mail=email(email); HashedPassword h=credential(name,password);
            store.transaction(tx->{
                tx.required("SELECT id FROM bss_system WHERE id=1 FOR UPDATE");
                require(tx.query("SELECT id FROM bss_users WHERE role='ADMIN'").isEmpty(),"An administrator already exists; use password recovery");
                long id=tx.insert("INSERT INTO bss_users(username,password_hash,salt,email,role) VALUES(?,?,?,?,?)",name,h.getHash(),h.getSalt(),mail,"ADMIN");
                audit(tx,"OPERATOR","BOOTSTRAP_ADMIN",id,"First administrator created");return null;
            });
        } finally { if(password!=null) Arrays.fill(password,'\0'); }
    }
    public Session login(String username,char[] password,Role requested,CaptchaChallenge captcha,String answer) {
        try {
            String name=username(username);
            boolean captchaOk=captcha!=null && captcha.verify(answer);
            String token=UUID.randomUUID().toString()+UUID.randomUUID();
            Session session=store.transaction(tx->{
                Optional<Row> found=tx.one("SELECT * FROM bss_users WHERE username=? FOR UPDATE",name);
                Row u=found.orElse(null);
                boolean unlocked=u!=null && (u.time("locked_until")==null || !u.time("locked_until").isAfter(now()));
                boolean ok=captchaOk && unlocked && "ACTIVE".equals(u.text("status")) && requested.name().equals(u.text("role"))
                        && PasswordHasher.matches(password,u.text("password_hash"),u.text("salt"));
                if(!ok) {
                    if(u!=null && unlocked) {
                        int attempts=u.time("locked_until")!=null ? 1 : u.integer("failed_attempts")+1;
                        tx.update("UPDATE bss_users SET failed_attempts=?,locked_until=?,updated_at=? WHERE id=?",
                                attempts,attempts>=3?now().plusMinutes(15):null,now(),u.id("id"));
                    }
                    tx.insert("INSERT INTO bss_login_history(user_id,username,outcome) VALUES(?,?,?)",u==null?null:u.id("id"),name,"FAILED");return null;
                }
                tx.update("UPDATE bss_users SET failed_attempts=0,locked_until=NULL,last_login=?,updated_at=? WHERE id=?",now(),now(),u.id("id"));
                tx.update("DELETE FROM bss_sessions WHERE expires_at<=?",now());
                tx.update("INSERT INTO bss_sessions(token_hash,user_id,credential_version,expires_at) VALUES(?,?,?,?)",digest(token),u.id("id"),u.integer("credential_version"),now().plusHours(8));
                tx.insert("INSERT INTO bss_login_history(user_id,username,outcome,session_hash) VALUES(?,?,?,?)",u.id("id"),name,"SUCCESS",digest(token));
                return new Session(token,name,requested);
            });
            require(session!=null,"Login failed. Check credentials/CAPTCHA, role and account lock; locks last 15 minutes."); return session;
        } finally { if(password!=null) Arrays.fill(password,'\0'); }
    }
    public Row actor(Tx tx,Session session) throws SQLException {
        require(session!=null,"Please sign in");
        return tx.required("SELECT u.id,u.username,u.email,u.role FROM bss_sessions s JOIN bss_users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>? AND s.credential_version=u.credential_version AND u.status='ACTIVE'",
                digest(session.token()),now());
    }
    public Row admin(Tx tx,Session session) throws SQLException {
        Row a=actor(tx,session);require("ADMIN".equals(a.text("role")),"Administrator permission required");return a;
    }
    public Row customer(Tx tx,Row actor) throws SQLException {
        return tx.required("SELECT * FROM bss_customers WHERE user_id=?",actor.id("id"));
    }
    public void logout(Session session) {
        store.transaction(tx->{
            actor(tx,session);
            tx.update("DELETE FROM bss_sessions WHERE token_hash=?",digest(session.token()));
            tx.update("UPDATE bss_login_history SET logout_time=? WHERE session_hash=?",now(),digest(session.token()));return null;
        });
    }
    /** Always returns the same public result; never prints or returns the OTP. */
    public void requestRecovery(String username,CaptchaChallenge captcha,String answer) {
        delivery.checkAvailability();
        require(captcha!=null && captcha.verify(answer),"CAPTCHA was incorrect or expired");
        String name=username(username),code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
        String recipient=store.transaction(tx->{
            Optional<Row> found=tx.one("SELECT * FROM bss_users WHERE username=? AND status='ACTIVE' FOR UPDATE",name);
            if(found.isEmpty()) return null;
            Row u=found.get();
            Optional<Row> old=tx.one("SELECT * FROM bss_recovery WHERE user_id=?",u.id("id"));
            if(old.isPresent() && old.get().time("created_at").isAfter(now().minusMinutes(1))) return null;
            tx.update("DELETE FROM bss_recovery WHERE user_id=?",u.id("id"));
            tx.update("INSERT INTO bss_recovery(user_id,code_hash,expires_at,created_at) VALUES(?,?,?,?)",u.id("id"),digest(code+u.text("salt")),now().plusMinutes(5),now());
            return u.text("email");
        });
        if(recipient!=null) delivery.send(recipient,code);
    }
    public void recover(String username,String code,char[] password) {
        try {
            String name=username(username); HashedPassword h=credential(name,password);
            boolean success=store.transaction(tx->{
                Optional<Row> found=tx.one("SELECT * FROM bss_users WHERE username=? AND status='ACTIVE' FOR UPDATE",name);
                if(found.isEmpty()) return false;
                Row u=found.get(); Optional<Row> recovery=tx.one("SELECT * FROM bss_recovery WHERE user_id=? FOR UPDATE",u.id("id"));
                if(recovery.isEmpty()) return false;
                Row r=recovery.get();
                if(r.integer("attempts")>=3 || !r.time("expires_at").isAfter(now())) return false;
                tx.update("UPDATE bss_recovery SET attempts=attempts+1 WHERE user_id=?",u.id("id"));
                if(code==null || !MessageDigest.isEqual(digest(code+u.text("salt")).getBytes(StandardCharsets.UTF_8),r.text("code_hash").getBytes(StandardCharsets.UTF_8))) return false;
                tx.update("UPDATE bss_users SET password_hash=?,salt=?,failed_attempts=0,locked_until=NULL,credential_version=credential_version+1,updated_at=? WHERE id=?",h.getHash(),h.getSalt(),now(),u.id("id"));
                tx.update("DELETE FROM bss_recovery WHERE user_id=?",u.id("id"));
                tx.update("DELETE FROM bss_sessions WHERE user_id=?",u.id("id"));
                audit(tx,"USER:"+u.id("id"),"PASSWORD_RECOVERED",u.id("id"),"Existing sessions revoked");return true;
            });
            require(success,"Recovery code is invalid, expired or exhausted");
        } finally { if(password!=null) Arrays.fill(password,'\0'); }
    }
    static void audit(Tx tx,String actor,String action,long entity,String details) throws SQLException {
        tx.insert("INSERT INTO bss_audit(actor,action,entity_id,details) VALUES(?,?,?,?)",actor,action,entity,details);
    }
    static void audit(Tx tx,Row actor,String action,long entity,String details) throws SQLException { audit(tx,"USER:"+actor.id("id"),action,entity,details); }
}
