package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static com.ruoyi.web.controller.ktv.KtvService.*;
import static org.junit.jupiter.api.Assertions.*;

class KtvFinanceTest {
    JdbcTemplate jdbc; KtvService service; KtvFinanceService finance;
    JSONObject admin=object("role","admin","subject","ktv-admin"), supplier=object("role","supplier","subject","A01"), customer=object("role","customer","subject","wx-finance-test");
    @BeforeEach void setup(){
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        jdbc=new JdbcTemplate(ds);service=new KtvService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
        ReflectionTestUtils.setField(service,"adminPassword","Test-admin-2026!");ReflectionTestUtils.setField(service,"supplierPassword","Test-supplier-2026!");ReflectionTestUtils.setField(service,"demoPayment",true);service.initialize();finance=new KtvFinanceService(jdbc);
    }
    String order(){return service.createOrder(customer,object("room","A08","scene",service.roomCode(admin,"A08",false).getString("scene"),"requestKey",UUID.randomUUID().toString(),"items",JSONArray.of(object("id","chicken","quantity",1)))).getString("id");}
    String pay(String id){String trade=service.paymentIntent(customer,id).getString("tradeNo");service.confirmPayment(trade,"transaction-"+id,3200,"2026-11-09T20:30:00Z","finance-test");return trade;}
    JSONObject ledger(JSONObject who){return finance.ledger(who,"","","","",1);}
    @Test void onlySuccessfulRealPaymentsAndRefundsEnterLedgerOnce(){
        String unpaid=order(),demo=order();service.advance(customer,demo,new JSONObject(),true);assertEquals(0,ledger(admin).getLongValue("total"));
        String trade=pay(unpaid);service.confirmPayment(trade,"transaction-"+unpaid,3200,"2026-11-09T20:30:00Z","finance-test");
        JSONObject first=ledger(admin);assertEquals(1,first.getLongValue("total"));assertEquals(3200,first.getLongValue("receiptCents"));assertTrue(first.getJSONArray("entries").getJSONObject(0).getString("occurredAt").startsWith("2026-11-10 04:30"));
        JSONObject refund=service.refundIntent(admin,unpaid,object("version",service.order(admin,unpaid).getIntValue("version"),"reason","退款验收"));String number=refund.getString("refundNo");
        service.confirmRefund(number,"PROCESSING",3200,3200);assertEquals(3200,ledger(admin).getLongValue("netCents"));
        service.confirmRefund(number,"SUCCESS",3200,3200);service.confirmRefund(number,"SUCCESS",3200,3200);
        JSONObject result=ledger(admin);assertEquals(2,result.getLongValue("total"));assertEquals(3200,result.getLongValue("refundCents"));assertEquals(0,result.getLongValue("netCents"));
    }
    @Test void supplierReadsOnlyAssignedOrdersAndCannotExportMerchantIdentifiers(){
        String id=order();pay(id);assertEquals(3200,ledger(supplier).getLongValue("netCents"));
        JSONObject entry=ledger(supplier).getJSONArray("entries").getJSONObject(0);assertFalse(entry.containsKey("providerNo"));
        assertThrows(ResponseStatusException.class,()->ledger(object("role","supplier","subject","A02")));
        assertThrows(ResponseStatusException.class,()->ledger(customer));
        assertThrows(ResponseStatusException.class,()->finance.export(supplier,"","","",""));
        service.account(admin,null,object("name","Second kitchen","password","Test-other-2026!","enabled",true,"process",true,"deliver",true));
        assertEquals(0,ledger(object("role","supplier","subject","A03")).getLongValue("total"));
        service.reassign(admin,id,object("supplierId","A03","version",service.order(admin,id).getIntValue("version"),"reason","交接"));
        assertEquals(0,ledger(supplier).getLongValue("total"));
        assertEquals(3200,ledger(object("role","supplier","subject","A03")).getLongValue("netCents"));
        assertEquals(3200,ledger(admin).getLongValue("netCents"));
    }
    @Test void historyBackfillIsRepeatableAndDoesNotChangeExistingAmounts(){
        String id=order();pay(id);jdbc.update("DELETE FROM ktv_finance_entry");service.initialize();service.initialize();
        assertEquals(1,ledger(admin).getLongValue("total"));assertEquals(3200,ledger(admin).getLongValue("netCents"));
    }
    @Test void httpAuthenticationAndValidationUseCorrectStatusCodes() throws Exception {
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new KtvFinanceController(finance,new KtvAccess(service))).setControllerAdvice(new KtvErrors()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/ktv-api/finance/ledger")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        String token=service.customerSession(new JSONObject()).getString("token");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/ktv-api/finance/ledger").header("X-KTV-Token",token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        String adminToken=service.staffSession(object("username","ktv-admin","password","Test-admin-2026!"),"test").getString("token");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/ktv-api/finance/ledger?from=invalid").header("X-KTV-Token",adminToken)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
    @Test void totalsCoverAllPagesAndHistoricalRefundBackfillIsIdempotent(){
        for(int i=0;i<51;i++)pay(order());
        JSONObject first=ledger(admin),last=finance.ledger(admin,"","","","",2);
        assertEquals(51,first.getLongValue("total"));assertEquals(50,first.getJSONArray("entries").size());assertEquals(1,last.getJSONArray("entries").size());assertEquals(163200,last.getLongValue("netCents"));
        String id=first.getJSONArray("entries").getJSONObject(0).getString("orderId");
        JSONObject refund=service.refundIntent(admin,id,object("version",service.order(admin,id).getIntValue("version"),"reason","历史退款"));service.confirmRefund(refund.getString("refundNo"),"SUCCESS",3200,3200);
        jdbc.update("DELETE FROM ktv_finance_entry");service.initialize();service.initialize();
        assertEquals(52,ledger(admin).getLongValue("total"));assertEquals(160000,ledger(admin).getLongValue("netCents"));
    }
    @Test void dateFiltersDailyAndCsvMatchExactAmountsAndBlockFormulaInjection(){
        String id=order();pay(id);
        assertEquals(0,finance.ledger(admin,"2026-11-09","2026-11-09","","",1).getLongValue("total"));
        assertEquals(3200,finance.ledger(admin,"2026-11-10","2026-11-10","receipt","A08",1).getLongValue("netCents"));
        assertEquals(3200,finance.daily(supplier,"2026-11-10","2026-11-10").getJSONObject(0).getLongValue("netCents"));
        String csv=finance.export(admin,"2026-11-10","2026-11-10","receipt",id);assertTrue(csv.contains("\"32.00\""));assertTrue(csv.contains("transaction-"+id));
        assertEquals("\"'=1+1\"",KtvFinanceService.csvCell("=1+1",true));
        assertEquals("\"'123456789012345678\"",KtvFinanceService.csvCell("123456789012345678",true));
        assertThrows(ResponseStatusException.class,()->finance.ledger(admin,"2026-11-11","2026-11-10","","",1));
        assertThrows(ResponseStatusException.class,()->finance.ledger(admin,"invalid","","","",1));
        assertThrows(ResponseStatusException.class,()->finance.ledger(admin,"","","invalid","",1));
        assertThrows(ResponseStatusException.class,()->finance.ledger(admin,"","","","",0));
        assertThrows(ResponseStatusException.class,()->finance.daily(admin,"2020-01-01","2026-01-01"));
    }
}
