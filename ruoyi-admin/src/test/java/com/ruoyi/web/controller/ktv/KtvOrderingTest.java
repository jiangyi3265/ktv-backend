package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static com.ruoyi.web.controller.ktv.KtvService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KtvOrderingTest {
    KtvService service; JdbcTemplate jdbc; String scene;
    final JSONObject admin=object("role","admin","subject","ktv-admin"),customer=object("role","customer","subject","wx-ordering-test"),supplier=object("role","supplier","subject","A01");
    @BeforeEach void setup() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        jdbc=new JdbcTemplate(ds);service=new KtvService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
        ReflectionTestUtils.setField(service,"adminPassword","Test-admin-2026!");ReflectionTestUtils.setField(service,"supplierPassword","Test-supplier-2026!");ReflectionTestUtils.setField(service,"demoPayment",true);service.initialize();
        scene=service.roomCode(admin,"A08",false).getString("scene");
    }
    JSONObject setting() {return service.snapshot(admin).getJSONObject("ordering");}
    void toggle(boolean enabled) {service.orderingSettings(admin,object("enabled",enabled,"version",setting().getLongValue("version")));}
    JSONObject draft() {return object("room","A08","scene",scene,"requestKey",UUID.randomUUID().toString(),"items",JSONArray.of(object("id","chicken","quantity",1)));}
    String order() {return service.createOrder(customer,draft()).getString("id");}
    @Test void absentSettingIsClosedForEveryoneAndCatalogRemainsAvailableToAdmin() {
        assertFalse(setting().getBooleanValue("enabled"));assertFalse(service.snapshot(customer).getBooleanValue("orderingEnabled"));
        assertEquals(0,service.snapshot(customer).getJSONArray("products").size());assertEquals(4,service.snapshot(admin).getJSONArray("products").size());
        assertThrows(ResponseStatusException.class,this::order);assertTrue(service.snapshot(supplier).containsKey("orders"));
    }
    @Test void persistedToggleRestoresCatalogAndSurvivesRestart() {
        toggle(true);assertEquals(4,service.snapshot(customer).getJSONArray("products").size());service.initialize();assertTrue(setting().getBooleanValue("enabled"));
        toggle(false);service.initialize();assertFalse(setting().getBooleanValue("enabled"));assertEquals(0,service.snapshot(customer).getJSONArray("products").size());
        assertTrue(service.audits(admin,1).toString().contains("store.ordering"));
    }
    @Test void closeBlocksExistingUnpaidOrderPaymentAndDuplicateCreationWithoutDeletingHistory() {
        toggle(true);JSONObject body=draft();String id=service.createOrder(customer,body).getString("id");toggle(false);
        assertThrows(ResponseStatusException.class,()->service.createOrder(customer,body));
        assertThrows(ResponseStatusException.class,()->service.paymentIntent(customer,id));
        assertThrows(ResponseStatusException.class,()->service.advance(customer,id,new JSONObject(),true));
        assertEquals("unpaid",service.order(customer,id).getString("status"));assertEquals(1,service.snapshot(customer).getJSONArray("orders").size());
        service.cancel(customer,id);assertEquals("cancelled",service.order(customer,id).getString("status"));
    }
    @Test void alreadyInitiatedRealPaymentIsAccountedAndCanBeFulfilledWhileClosed() {
        toggle(true);String id=order(),trade=service.paymentIntent(customer,id).getString("tradeNo");toggle(false);
        service.confirmPayment(trade,"ordering-payment-1",3200,"2026-11-17T12:00:00+08:00","ordering-test");
        assertTrue(service.order(customer,id).getBooleanValue("paid"));
        service.advance(supplier,id,object("status","preparing","checkedIds",JSONArray.of("chicken")),false);
        service.advance(supplier,id,object("status","delivering","checkedIds",JSONArray.of("chicken")),false);
        service.advance(supplier,id,object("status","completed"),false);
        assertEquals("completed",service.order(customer,id).getString("status"));
    }
    @Test void closedStoreCanStillRefundPreviouslyPaidOrder() {
        toggle(true);String id=order();service.confirmPayment(service.paymentIntent(customer,id).getString("tradeNo"),"ordering-payment-2",3200,"2026-11-17T12:00:00+08:00","ordering-test");toggle(false);
        JSONObject o=service.order(admin,id);JSONObject refund=service.refundIntent(admin,id,object("reason","暂停营业","version",o.getIntValue("version")));
        service.confirmRefund(refund.getString("refundNo"),"SUCCESS",3200,3200);assertEquals("refunded",service.order(customer,id).getString("status"));
    }
    @Test void roleChecksRejectSupplierAndCustomerAndValidateStrictValues() {
        for(JSONObject who:new JSONObject[]{customer,supplier}) assertEquals(403,assertThrows(ResponseStatusException.class,()->service.orderingSettings(who,object("enabled",true,"version",1))).getRawStatusCode());
        assertThrows(ResponseStatusException.class,()->service.orderingSettings(admin,object("enabled","false","version",1)));
        assertThrows(ResponseStatusException.class,()->service.orderingSettings(admin,object("enabled",true,"version",1.5)));
        assertFalse(setting().getBooleanValue("enabled"));
    }
    @Test void staleAdminUpdateCannotOverwriteNewerState() {
        JSONObject original=setting();toggle(true);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.orderingSettings(admin,object("enabled",false,"version",original.getLongValue("version")))).getRawStatusCode());
        assertTrue(setting().getBooleanValue("enabled"));
    }
    @Test void staffPortalIsIndependentPersistentAndKeepsPcAndCustomerAccess() {
        assertTrue(service.publicSiteSettings().getBooleanValue("staffPortalEnabled"));
        String staffToken=service.staffSession(object("username","A01","password","Test-supplier-2026!"),"portal-test").getString("token");
        JSONObject settings=service.staffPortalSettings(admin,object("enabled",false,"version",1));
        assertFalse(service.publicSiteSettings().getBooleanValue("staffPortalEnabled"));
        assertFalse(setting().getBooleanValue("enabled"));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.staffSession(object("username","A01","password","Test-supplier-2026!"),"portal-test")).getRawStatusCode());
        KtvAccess access=new KtvAccess(service);
        org.springframework.mock.web.MockHttpServletRequest request=new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-KTV-Token",staffToken);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->access.who(request)).getRawStatusCode());
        assertEquals("customer",service.snapshot(customer).getString("role"));
        assertNotNull(service.snapshot(admin).getJSONObject("staffPortal"));
        service.initialize();assertFalse(service.publicSiteSettings().getBooleanValue("staffPortalEnabled"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.staffPortalSettings(admin,object("enabled",true,"version",1))).getRawStatusCode());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.staffPortalSettings(supplier,object("enabled",true,"version",2))).getRawStatusCode());
        service.staffPortalSettings(admin,object("enabled",true,"version",settings.getLongValue("version")));
        assertEquals("supplier",access.who(request).getString("role"));
        assertTrue(service.audits(admin,1).toString().contains("store.staffPortal"));
    }
    @Test void httpRouteChecksAuthenticationAndReturnsSavedState() throws Exception {
        KtvAccess access=new KtvAccess(service);KtvWechat wechat=mock(KtvWechat.class);
        org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new KtvOperationsController(service,wechat,access)).setControllerAdvice(new KtvErrors()).build();
        mvc.perform(put("/ktv-api/admin/ordering").contentType("application/json").content("{\"enabled\":true,\"version\":1}")).andExpect(status().isUnauthorized());
        String token=service.staffSession(object("username","A01","password","Test-supplier-2026!"),"test").getString("token");
        mvc.perform(put("/ktv-api/admin/ordering").header("X-KTV-Token",token).contentType("application/json").content("{\"enabled\":true,\"version\":1}")).andExpect(status().isForbidden());
        token=service.staffSession(object("username","ktv-admin","password","Test-admin-2026!"),"test-admin").getString("token");
        mvc.perform(put("/ktv-api/admin/ordering").header("X-KTV-Token",token).contentType("application/json").content("{\"enabled\":true,\"version\":1}")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
    }
}
