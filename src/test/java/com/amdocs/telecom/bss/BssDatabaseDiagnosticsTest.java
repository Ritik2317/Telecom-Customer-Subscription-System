package com.amdocs.telecom.bss;

import com.amdocs.telecom.bss.dao.BssDatabase;
import java.sql.SQLException;
import org.junit.Test;
import static org.junit.Assert.*;

public class BssDatabaseDiagnosticsTest {
    @Test public void loginFailureProvidesActionableCodesWithoutDriverSecrets() {
        BssDatabase database=new BssDatabase(()->{throw new SQLException("private-driver-detail","28000",1045);});
        try {database.check();fail("Expected failure");}
        catch(IllegalStateException failure) {
            assertTrue(failure.getMessage().contains("MySQL rejected the login"));
            assertTrue(failure.getMessage().contains("28000"));
            assertTrue(failure.getMessage().contains("1045"));
            assertFalse(failure.getMessage().contains("private-driver-detail"));
        }
    }
    @Test public void missingDatabaseHasDifferentGuidanceFromMissingTables() {
        BssDatabase database=new BssDatabase(()->{throw new SQLException("private-driver-detail","42000",1049);});
        try {database.initialize();fail("Expected failure");}
        catch(IllegalStateException failure) {assertTrue(failure.getMessage().contains("database does not exist"));}
    }
}
