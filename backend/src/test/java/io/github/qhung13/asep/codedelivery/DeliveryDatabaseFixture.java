package io.github.qhung13.asep.codedelivery;

import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Owned-instance proof before returning any fixture DB capability. Never used by production. */
final class DeliveryDatabaseFixture {
    static PGSimpleDataSource runtime()throws Exception{
        assumeTrue(Boolean.getBoolean("asep.delivery.postgres"),"Use owned delivery harness");
        assertEquals("true",System.getenv("ASEP_DELIVERY_DISPOSABLE"));String url=System.getenv("ASEP_DELIVERY_JDBC_URL");assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/asep_delivery_test_[0-9a-f]{32}"));
        var source=source(url,"asep_delivery_runtime",System.getenv("ASEP_DELIVERY_DB_PASSWORD"));
        try(var db=source.getConnection();var query=db.prepareStatement("SELECT current_user='asep_delivery_runtime' AND database_name=current_database() AND server_started_at=pg_postmaster_start_time() AND nonce=? FROM asep_delivery_test_proof.instance WHERE singleton")){
            query.setString(1,System.getenv("ASEP_DELIVERY_NONCE"));try(var rows=query.executeQuery()){assertTrue(rows.next());assertTrue(rows.getBoolean(1));assertFalse(rows.next());}
        }return source;
    }
    static PGSimpleDataSource issuer()throws Exception{var runtime=runtime();String password=System.getenv("ASEP_DELIVERY_TEST_ISSUER_PASSWORD");assertNotNull(password);assertTrue(password.matches("[0-9a-f]{64}"));return source(runtime.getURL(),"asep_delivery_issuer",password);}
    static PGSimpleDataSource source(String url,String role,String password){var source=new PGSimpleDataSource();source.setUrl(url);source.setUser(role);source.setPassword(password);source.setConnectTimeout(3);source.setSocketTimeout(5);source.setOptions("-c statement_timeout=4000 -c lock_timeout=3000");return source;}
}
