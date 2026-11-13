package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static com.ruoyi.web.controller.ktv.KtvService.object;
import static org.junit.jupiter.api.Assertions.*;

class KtvOrderRaceTest {
    KtvService service; JdbcTemplate jdbc;
    final JSONObject admin=object("role","admin","subject","ktv-admin");
    final JSONObject worker=object("role","supplier","subject","A01");
    final JSONObject customer=object("role","customer","subject","wx-race-test");

    @BeforeEach void setup() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        jdbc=new JdbcTemplate(ds); service=new KtvService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
        ReflectionTestUtils.setField(service,"adminPassword","Fixture-race-admin-2026!");
        ReflectionTestUtils.setField(service,"supplierPassword","Fixture-race-worker-2026!"); service.initialize();
    }
    String paidOrder() {
        String scene=service.roomCode(admin,"A08",false).getString("scene");
        String id=service.createOrder(customer,object("room","A08","scene",scene,"requestKey",UUID.randomUUID().toString(),"items",JSONArray.of(object("id","chicken","quantity",2)))).getString("id");
        service.confirmPayment(service.paymentIntent(customer,id).getString("tradeNo"),"transaction-"+id,6400,"2026-11-13T12:00:00+08:00","race-test");
        return id;
    }
    JSONObject move(String id,String status) {return object("version",service.order(admin,id).getIntValue("version"),"status",status,"checkedIds",JSONArray.of("chicken"));}
    List<Integer> race(Callable<?> first,Callable<?> second) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            List<Future<Integer>> futures=new ArrayList<>();
            for(Callable<?> action:Arrays.asList(first,second)) futures.add(pool.submit(()->{
                ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));
                try {action.call();return 200;} catch(ResponseStatusException e){return e.getRawStatusCode();}
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            List<Integer> result=new ArrayList<>();for(Future<Integer> f:futures)result.add(f.get(10,TimeUnit.SECONDS));Collections.sort(result);return result;
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void twoWorkersAcceptingTheSameVersionRecordOnlyOneAcceptance() throws Exception {
        String id=paidOrder();JSONObject input=move(id,"preparing");int version=input.getIntValue("version");
        assertEquals(Arrays.asList(200,200),race(()->service.advance(worker,id,input,false),()->service.advance(worker,id,input,false)));
        JSONObject order=service.order(admin,id);assertEquals("preparing",order.getString("status"));assertEquals(version+1,order.getIntValue("version"));
        assertEquals(1,order.getJSONArray("events").stream().filter(x->"食堂接单".equals(((JSONObject)x).getString("label"))).count());
    }
    @Test void stockIssueAndAcceptanceHaveOneWinnerWithoutLostUpdates() throws Exception {
        String id=paidOrder();JSONObject accept=move(id,"preparing"),issue=object("version",accept.get("version"),"reason","实物缺货","itemIds",JSONArray.of("chicken"));
        assertEquals(Arrays.asList(200,409),race(()->service.advance(worker,id,accept,false),()->service.reportStockIssue(worker,id,issue)));
        JSONObject order=service.order(admin,id);assertEquals(accept.getIntValue("version")+1,order.getIntValue("version"));
        if("stock_issue".equals(order.getString("status"))){assertNotNull(order.getJSONObject("stockIssue"));assertNull(order.getString("stockConfirmedAt"));}
        else {assertEquals("preparing",order.getString("status"));assertNotNull(order.getString("stockConfirmedAt"));assertNull(order.getJSONObject("stockIssue"));}
    }
    @Test void refundAndDeliveryCompletionCannotOverwriteEachOther() throws Exception {
        String id=paidOrder();service.advance(worker,id,move(id,"preparing"),false);service.advance(worker,id,move(id,"delivering"),false);
        JSONObject deliver=move(id,"completed"),refund=object("version",deliver.get("version"),"reason","商家同意退款");
        assertEquals(Arrays.asList(200,409),race(()->service.advance(worker,id,deliver,false),()->service.refundIntent(admin,id,refund)));
        JSONObject order=service.order(admin,id);assertTrue(Arrays.asList("completed","refunding").contains(order.getString("status")));assertEquals(deliver.getIntValue("version")+1,order.getIntValue("version"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ktv_finance_entry WHERE kind='refund'",Integer.class));
    }
    @Test void concurrentRefundCallbacksDeduplicateAndLateProcessingCannotRegressSuccess() throws Exception {
        String id=paidOrder();String refund=service.refundIntent(admin,id,object("version",service.order(admin,id).get("version"),"reason","退款回调验收")).getString("refundNo");
        Callable<?> success=()->{service.confirmRefund(refund,"SUCCESS",6400,6400);return null;};
        assertEquals(Arrays.asList(200,200),race(success,success));service.confirmRefund(refund,"PROCESSING",6400,6400);
        assertEquals("refunded",service.order(admin,id).getString("status"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ktv_finance_entry WHERE kind='refund'",Integer.class));
        assertEquals(0L,jdbc.queryForObject("SELECT SUM(amount_cents) FROM ktv_finance_entry",Long.class));
    }
}
