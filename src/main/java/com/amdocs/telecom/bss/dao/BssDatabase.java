package com.amdocs.telecom.bss.dao;

import com.amdocs.telecom.util.SqlScriptRunner;
import java.sql.*;
import java.util.*;

public final class BssDatabase {
    private final BssStore.Connections connections;
    public BssDatabase(BssStore.Connections connections) {this.connections=connections;}
    /** Explicit additive installation. No CREATE USER, DROP, DELETE, seed data or production discovery. */
    public void initialize() {
        try(Connection c=connections.open()) {
            new SqlScriptRunner(c).runScript("bss/schema.sql",Collections.emptyMap());
            String[][] indexes={{"bss_usage_month","bss_usage","subscription_id,usage_date"},{"bss_usage_pending","bss_usage","status,subscription_id"},
                {"bss_bills_overdue","bss_bills","status,due_date"},{"bss_history_time","bss_subscription_history","subscription_id,change_date"},
                {"bss_addon_time","bss_addon_history","subscription_id,plan_id,change_date"},{"bss_notifications_inbox","bss_notifications","user_id,status"},
                {"bss_login_user","bss_login_history","user_id,login_time"},{"bss_customer_city","bss_customers","city"},{"bss_complaints_status","bss_complaints","status,customer_id"}};
            for(String[] index:indexes) {
                boolean found=false;
                try(ResultSet rs=c.getMetaData().getIndexInfo(c.getCatalog(),null,index[1],false,false)) {
                    while(rs.next()) if(index[0].equalsIgnoreCase(rs.getString("INDEX_NAME"))) found=true;
                }
                if(!found) try(Statement st=c.createStatement()) {st.execute("CREATE INDEX "+index[0]+" ON "+index[1]+"("+index[2]+")");}
            }
            try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT schema_version FROM bss_system WHERE id=1")) {
                if(rs.next()) {if(rs.getInt(1)!=1) throw new IllegalStateException("Unsupported BSS schema version");}
                else try(PreparedStatement s=c.prepareStatement("INSERT INTO bss_system(id,schema_version) VALUES(1,1)")) {s.executeUpdate();}
            }
        } catch(SQLException e) {throw new IllegalStateException("Schema installation failed. "+diagnostic(e),e);}
    }
    public void check() {
        try(Connection c=connections.open();PreparedStatement s=c.prepareStatement("SELECT schema_version FROM bss_system WHERE id=1");ResultSet rs=s.executeQuery()) {
            if(!rs.next()||rs.getInt(1)!=1) throw new IllegalStateException("Initialize the BSS schema using --init");
        } catch(SQLException e) {throw new IllegalStateException("BSS database unavailable or not initialized. "+diagnostic(e),e);}
    }
    /** Driver messages can contain connection details. Expose only codes and fixed guidance. */
    private static String diagnostic(SQLException error) {
        String hint;
        switch(error.getErrorCode()) {
            case 1045: hint="MySQL rejected the login. Check db.user/db.password and any TCSMS_DB_USER/TCSMS_DB_PASSWORD environment overrides; verify the account's host grants.";break;
            case 1049: hint="The configured database does not exist. Create your database and check the schema name in db.url.";break;
            case 1044: case 1142: case 1143: case 1227:
                hint="The account lacks permission. Ask your database administrator to grant access to your TCSMS schema.";break;
            case 1146: hint="The schema tables are missing. Run --init after checking the configured database.";break;
            case 1061: hint="An index already exists. Check schema metadata permissions before rerunning --init.";break;
            default:
                hint=error.getSQLState()!=null && error.getSQLState().startsWith("08")
                    ?"Cannot establish the database connection. Check the MySQL service, host, port and TLS settings."
                    :"Check database configuration and permissions; use the SQL state and vendor code below for diagnosis.";
        }
        String state=error.getSQLState();
        if(state==null || !state.matches("[A-Za-z0-9]{5}")) state="unknown";
        return hint+" [SQL state "+state+", vendor code "+error.getErrorCode()+"]";
    }
}
