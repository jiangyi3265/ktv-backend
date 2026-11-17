package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONObject;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static com.ruoyi.web.controller.ktv.KtvService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KtvPersistentSessionTest {
    JdbcTemplate jdbc;KtvService service;KtvPcAccountPolicy pc;
    final JSONObject admin=object("role","admin","subject","ktv-admin");
    @BeforeEach void setup() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        jdbc=new JdbcTemplate(ds);service=new KtvService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
        pc=mock(KtvPcAccountPolicy.class);ReflectionTestUtils.setField(service,"pcAccounts",pc);
        ReflectionTestUtils.setField(service,"adminPassword","Test-admin-2026!");ReflectionTestUtils.setField(service,"supplierPassword","Test-supplier-2026!");service.initialize();
    }
    String login() {return service.staffSession(object("username","A01","password","Test-supplier-2026!"),"test").getString("token");}
    LocalDateTime expires(String token) {return jdbc.queryForObject("SELECT expires_at FROM ktv_session WHERE token_hash=?",java.sql.Timestamp.class,hash(token)).toLocalDateTime();}
    void expiry(String token,LocalDateTime time) {jdbc.update("UPDATE ktv_session SET expires_at=? WHERE token_hash=?",time,hash(token));}
    @Test void staffSessionSurvivesDailyUseBeyondOriginalEightHoursAndRenewsOnlyWhenNeeded() {
        String token=login();assertTrue(expires(token).isAfter(LocalDateTime.now().plusDays(29)));
        LocalDateTime original=expires(token);service.identity(token);assertEquals(original,expires(token));
        for(int day=0;day<35;day++) {
            expiry(token,LocalDateTime.now().plusDays(28));assertEquals("supplier",service.identity(token).getString("role"));
            assertTrue(expires(token).isAfter(LocalDateTime.now().plusDays(29)));
        }
    }
    @Test void expiredAndLoggedOutTokensCannotBeRenewed() {
        String expired=login();expiry(expired,LocalDateTime.now().minusSeconds(5));assertThrows(ResponseStatusException.class,()->service.identity(expired));
        String loggedOut=login();service.logout(loggedOut);assertThrows(ResponseStatusException.class,()->service.identity(loggedOut));
    }
    @Test void supplierDisableAndPasswordResetRevokeAllDevicesWithoutRevivalOnEnable() {
        String one=login(),two=login();JSONObject a=service.snapshot(admin).getJSONArray("accounts").getJSONObject(0);
        a.put("enabled",false);service.account(admin,"A01",a);assertThrows(ResponseStatusException.class,()->service.identity(one));
        a=service.snapshot(admin).getJSONArray("accounts").getJSONObject(0);a.put("enabled",true);service.account(admin,"A01",a);
        assertThrows(ResponseStatusException.class,()->service.identity(two));String fresh=login();
        a=service.snapshot(admin).getJSONArray("accounts").getJSONObject(0);a.put("password","New-supplier-2026!");service.account(admin,"A01",a);
        assertThrows(ResponseStatusException.class,()->service.identity(fresh));
    }
    @Test void pcMobileSessionRenewsAndRechecksCredentialsOnEveryRequest() {
        when(pc.matches(1,"original-version")).thenReturn(true);String token=service.pcAdminSession(1,"original-version").getString("token");
        assertFalse(token.startsWith("pc."));expiry(token,LocalDateTime.now().plusHours(1));
        assertEquals("pc-1",service.identity(token).getString("subject"));assertTrue(expires(token).isAfter(LocalDateTime.now().plusDays(29)));
        when(pc.matches(1,"original-version")).thenReturn(false);assertThrows(ResponseStatusException.class,()->service.identity(token));
        when(pc.matches(1,"original-version")).thenReturn(true);assertThrows(ResponseStatusException.class,()->service.identity(token));
    }
    @Test void pcCredentialChangeDuringLoginDoesNotCreateSession() {
        assertThrows(ResponseStatusException.class,()->service.pcAdminSession(1,"stale-version"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ktv_session",Integer.class));
    }
    @Test void migrationPreservesValidLegacySessionsAndIsIdempotent() {
        String token=login();expiry(token,LocalDateTime.now().plusHours(2));jdbc.execute("ALTER TABLE ktv_session DROP COLUMN credential_version");
        service.initialize();service.initialize();assertEquals("supplier",service.identity(token).getString("role"));assertTrue(expires(token).isAfter(LocalDateTime.now().plusDays(29)));
    }
    @Test void customerLifetimeDoesNotSlideAndStaffLogoutKeepsOtherRole() {
        String customer=service.customerSession(null).getString("token"),staff=login();expiry(customer,LocalDateTime.now().plusDays(2));
        LocalDateTime original=expires(customer);service.identity(customer);assertEquals(original,expires(customer));service.logout(staff);
        assertEquals("customer",service.identity(customer).getString("role"));
    }
    @Test void legacyPasswordRotationDoesNotLogOutPcAdministrator() {
        when(pc.matches(1,"pc-version")).thenReturn(true);String token=service.pcAdminSession(1,"pc-version").getString("token");
        service.rotatePassword(admin,object("oldPassword","Test-admin-2026!","password","Another-secure-password"));
        assertEquals("pc-1",service.identity(token).getString("subject"));
    }
}
