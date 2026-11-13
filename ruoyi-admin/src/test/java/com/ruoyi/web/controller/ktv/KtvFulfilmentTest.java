package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static com.ruoyi.web.controller.ktv.KtvService.*;
import static org.junit.jupiter.api.Assertions.*;

class KtvFulfilmentTest {
    KtvService service;JdbcTemplate jdbc;TransactionTemplate tx;String scene;
    JSONObject admin=object("role","admin","subject","ktv-admin"),supplier=object("role","supplier","subject","A01"),customer=object("role","customer","subject","wx-fulfilment");
    @BeforeEach void setup(){DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));service=new KtvService(jdbc,tx);ReflectionTestUtils.setField(service,"adminPassword","Fixture-admin-2026!");ReflectionTestUtils.setField(service,"supplierPassword","Fixture-kitchen-2026!");service.initialize();scene=service.roomCode(admin,"A08",false).getString("scene");}
    String order(boolean paid){String id=service.createOrder(customer,object("room","A08","scene",scene,"requestKey",UUID.randomUUID().toString(),"items",JSONArray.of(object("id","chicken","quantity",2)))).getString("id");if(paid){String trade=service.paymentIntent(customer,id).getString("tradeNo");service.confirmPayment(trade,"tx-"+id,6400,"2026-11-13T12:00:00+08:00","fulfilment");}return id;}
    JSONObject body(String id){return object("version",service.order(admin,id).getIntValue("version"),"reason","鸡丁饭只剩一份","itemIds",JSONArray.of("chicken"));}
    @Test void stockIssueRequiresPaidAssignedOrderAndValidVersion(){String id=order(false);assertThrows(ResponseStatusException.class,()->service.reportStockIssue(supplier,id,body(id)));String paid=order(true);assertThrows(ResponseStatusException.class,()->service.reportStockIssue(customer,paid,body(paid)));JSONObject b=body(paid);b.put("version",0);assertThrows(ResponseStatusException.class,()->service.reportStockIssue(supplier,paid,b));b.put("version",service.order(admin,paid).getIntValue("version"));b.put("itemIds",JSONArray.of("unknown"));assertThrows(ResponseStatusException.class,()->service.reportStockIssue(supplier,paid,b));}
    @Test void exceptionPausesFulfilmentAndMerchantCanResume(){String id=order(true);service.reportStockIssue(supplier,id,body(id));assertEquals("stock_issue",service.order(customer,id).getString("status"));assertNotNull(service.order(supplier,id).getJSONObject("stockIssue"));assertEquals(1,service.snapshot(customer,1,null,"active","","","").getJSONArray("orders").size());assertThrows(ResponseStatusException.class,()->service.advance(supplier,id,object("status","preparing"),false));assertThrows(ResponseStatusException.class,()->service.resumeStockOrder(supplier,id,body(id)));service.resumeStockOrder(admin,id,body(id));assertEquals("pending",service.order(customer,id).getString("status"));assertEquals(2,service.order(admin,id).getIntValue("dispatchRound"));assertFalse(service.order(admin,id).containsKey("stockIssue"));}
    @Test void newAcceptanceRequiresCheckedStockAndDeliveryStillRequiresPacking(){String id=order(true);JSONObject b=object("status","preparing","confirmStock",true,"checkedIds",new JSONArray(),"version",service.order(admin,id).getIntValue("version"));assertThrows(ResponseStatusException.class,()->service.advance(supplier,id,b,false));b.put("checkedIds",JSONArray.of("chicken"));service.advance(supplier,id,b,false);assertNotNull(service.order(admin,id).getString("stockConfirmedAt"));assertThrows(ResponseStatusException.class,()->service.advance(supplier,id,object("status","delivering","checkedIds",new JSONArray()),false));service.advance(supplier,id,object("status","delivering","checkedIds",JSONArray.of("chicken")),false);service.advance(supplier,id,object("status","completed"),false);assertEquals("completed",service.order(customer,id).getString("status"));assertThrows(ResponseStatusException.class,()->service.reportStockIssue(supplier,id,body(id)));}
    @Test void legacyAcceptanceCannotBypassStockConfirmation() {
        String id=order(true);
        for(JSONObject input: new JSONObject[]{object("status","preparing"),object("status","preparing","confirmStock",false),object("status","preparing","checkedIds",JSONArray.of("unknown"))}) {
            ResponseStatusException error=assertThrows(ResponseStatusException.class,()->service.advance(supplier,id,input,false));
            assertEquals(400,error.getRawStatusCode());
            assertEquals("pending",service.order(admin,id).getString("status"));
            assertNull(service.order(admin,id).getString("stockConfirmedAt"));
        }
        service.advance(supplier,id,object("status","preparing","checkedIds",JSONArray.of("chicken")),false);
        assertEquals("preparing",service.order(admin,id).getString("status"));
        assertNotNull(service.order(admin,id).getString("stockConfirmedAt"));
    }
    @Test void reassignmentClearsIssueAndOnlyReplacementCanAccept(){String id=order(true);service.reportStockIssue(supplier,id,body(id));service.account(admin,null,object("name","备货乙","password","Fixture-second-2026!","enabled",true,"process",true,"deliver",true));JSONObject b=body(id);b.put("supplierId","A03");service.reassign(admin,id,b);assertEquals("pending",service.order(admin,id).getString("status"));assertFalse(service.order(admin,id).containsKey("stockIssue"));assertThrows(ResponseStatusException.class,()->service.order(supplier,id));assertThrows(ResponseStatusException.class,()->service.reportStockIssue(supplier,id,body(id)));}
    @Test void supplierCannotRefundAndMerchantRefundUsesExistingFinancialPath(){String id=order(true);service.reportStockIssue(supplier,id,body(id));assertThrows(ResponseStatusException.class,()->service.refundIntent(supplier,id,body(id)));JSONObject intent=service.refundIntent(admin,id,body(id));assertEquals("refunding",service.order(customer,id).getString("status"));assertThrows(ResponseStatusException.class,()->service.resumeStockOrder(admin,id,body(id)));service.confirmRefund(intent.getString("refundNo"),"SUCCESS",6400,6400);assertEquals("refunded",service.order(customer,id).getString("status"));}
}
