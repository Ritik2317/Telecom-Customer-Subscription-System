package com.amdocs.telecom.bss;

import com.amdocs.telecom.bss.dao.BssStore;
import org.junit.After;
import java.sql.*;
import java.util.UUID;

/** Runs the same contract against a disposable MySQL process created by scripts/test_mysql.py. */
public final class BssMySqlIT extends BssIntegrationTest {
    @Override protected BssStore.Connections createConnections() {
        String server=System.getProperty("tcsms.test.mysql.url","");
        if(!server.startsWith("jdbc:mysql://127.0.0.1:")) throw new IllegalArgumentException("Use scripts/test_mysql.py to start an isolated loopback MySQL server");
        String password=System.getenv("TCSMS_TEST_MYSQL_PASSWORD");
        if(password==null || password.isBlank()) throw new IllegalArgumentException("Disposable MySQL test credentials are missing");
        String schema="tcsms_it_"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=DriverManager.getConnection(server,"root",password);Statement st=c.createStatement()) {st.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        catch(SQLException e) {throw new IllegalStateException("Cannot create isolated test schema",e);}
        url=server.replace("/?","/"+schema+"?");
        return ()->DriverManager.getConnection(url,"root",password);
    }
    @Override @After public void shutdownDatabase() { /* The helper stops its own MySQL process; no existing schema is dropped. */ }
    @Override protected Connection createSqlReferenceConnection() throws SQLException {return DriverManager.getConnection(url,"root",System.getenv("TCSMS_TEST_MYSQL_PASSWORD"));}
}
