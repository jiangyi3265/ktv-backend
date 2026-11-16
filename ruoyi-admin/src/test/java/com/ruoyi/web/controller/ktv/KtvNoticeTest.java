package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import static com.ruoyi.web.controller.ktv.KtvService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KtvNoticeTest extends KtvFulfilmentTest {
    KtvNoticeService notices;KtvWechat gateway;
    final String webhook="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=00000000-0000-0000-0000-000000000000";
    @BeforeEach void notifications(){gateway=mock(KtvWechat.class);notices=new KtvNoticeService(jdbc,tx,service,gateway);ReflectionTestUtils.setField(notices,"encryptionKey",String.join("",Collections.nCopies(64,"a")));ReflectionTestUtils.setField(notices,"overdueMinutes",5);notices.initialize();when(gateway.login(anyString())).thenReturn("staff-openid");when(gateway.notificationTemplates()).thenReturn(object("data",JSONArray.of(object("priTmplId","fixture-template","title","订单待处理通知","type",2,"content","包厢：{{thing1.DATA}}\n订单号：{{character_string2.DATA}}\n时间：{{time3.DATA}}"))));}
    void group(String scope){notices.configure(admin,object("scope",scope,"webhook",webhook));}
    void template(){notices.configure(admin,object("scope","template","templateId","fixture-template","fields",object("thing1","room","character_string2","order","time3","time")));}
    void grant(){JSONObject bound=notices.bind(supplier,"login-code");notices.subscribe(supplier,object("nonce",bound.get("nonce"),"templateId",bound.get("templateId"),"accepted",true));}
    void customerTemplateAndGrant() {
        when(gateway.notificationTemplates()).thenReturn(object("data",JSONArray.of(object("priTmplId","customer-fixture","title","订单状态通知","type",2,"content","状态：{{phrase1.DATA}}\n包厢：{{thing2.DATA}}"))));
        notices.configure(admin,object("scope","customer-template","templateId","customer-fixture","fields",object("phrase1","status","thing2","room")));
        when(gateway.login("customer-code")).thenReturn("fulfilment");
        JSONObject b=notices.bind(customer,"customer-code");
        notices.subscribe(customer,object("nonce",b.get("nonce"),"templateId",b.get("templateId"),"accepted",true));
    }
    @Test void customerAuthorizationIsOwnedAndAcceptanceUsesCustomerTemplateAndPage() {
        customerTemplateAndGrant();
        assertThrows(Exception.class,()->notices.bind(customer,"different-wechat"));
        assertEquals(1,notices.status(customer).getIntValue("credits"));
        String id=order(true);service.advance(supplier,id,object("status","preparing","checkedIds",JSONArray.of("chicken")),false);
        notices.discover();notices.dispatch();
        verify(gateway).sendSubscription(eq("fulfilment"),eq("customer-fixture"),argThat(d -> "已接单".equals(d.getJSONObject("phrase1").getString("value"))),eq("pages/customer/progress?id="+id));
        assertEquals(0,notices.status(customer).getIntValue("credits"));
        assertFalse(notices.admin(admin).toJSONString().contains("customer:wx-fulfilment"));
    }
    @Test void fastOrderTransitionsRemainDurableButCustomersOnlyReceiveTheLatestApplicableProgress() {
        customerTemplateAndGrant();String id=order(true);
        service.advance(supplier,id,object("status","preparing","checkedIds",JSONArray.of("chicken")),false);
        service.advance(supplier,id,object("status","delivering","checkedIds",JSONArray.of("chicken")),false);
        service.advance(supplier,id,object("status","completed"),false);
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM ktv_notice_event",Integer.class));
        notices.discover();notices.dispatch();
        verify(gateway,times(1)).sendSubscription(eq("fulfilment"),eq("customer-fixture"),argThat(d -> "已送达".equals(d.getJSONObject("phrase1").getString("value"))),anyString());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM ktv_notice_outbox WHERE scope='admin:ktv-admin' AND kind IN ('paid','accepted') AND channel='wechat'",Integer.class));
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM ktv_notice_outbox",Integer.class);
        notices.discover();assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM ktv_notice_outbox",Integer.class));
    }
    @Test void pcAdministratorsBindIndependentlyAndEachReceivesNewOrder() {
        template();KtvPcAdminSessions pc=mock(KtvPcAdminSessions.class);ReflectionTestUtils.setField(notices,"pcAdmins",pc);when(pc.allowed(anyLong())).thenReturn(true);
        for(int id=1;id<=2;id++) {
            JSONObject actor=object("role","admin","subject","pc-"+id);String code="pc-code-"+id;
            when(gateway.login(code)).thenReturn("pc-openid-"+id);JSONObject b=notices.bind(actor,code);
            notices.subscribe(actor,object("nonce",b.get("nonce"),"templateId",b.get("templateId"),"accepted",true));
        }
        String id=order(true);notices.discover();notices.dispatch();
        verify(gateway).sendSubscription(eq("pc-openid-1"),anyString(),any(),eq("pages/admin/order-detail?id="+id));
        verify(gateway).sendSubscription(eq("pc-openid-2"),anyString(),any(),eq("pages/admin/order-detail?id="+id));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM ktv_notice_binding WHERE scope LIKE 'admin:pc-%'",Integer.class));
    }
    @Test void revokedPcPermissionCancelsQueuedNotifications() {
        template();KtvPcAdminSessions pc=mock(KtvPcAdminSessions.class);ReflectionTestUtils.setField(notices,"pcAdmins",pc);when(pc.allowed(1)).thenReturn(true);
        JSONObject actor=object("role","admin","subject","pc-1"),b=notices.bind(actor,"pc-code");
        notices.subscribe(actor,object("nonce",b.get("nonce"),"templateId",b.get("templateId"),"accepted",true));
        order(true);notices.discover();when(pc.allowed(1)).thenReturn(false);notices.dispatch();
        verify(gateway,never()).sendSubscription(anyString(),anyString(),any(),anyString());
        assertEquals("cancelled",jdbc.queryForObject("SELECT status FROM ktv_notice_outbox WHERE scope='admin:pc-1' AND channel='wechat'",String.class));
    }
    @Test void rolledBackOrderTransitionCannotLeaveANotificationEvent() {
        String id=order(true);
        assertThrows(IllegalStateException.class,()->tx.execute(status -> {service.advance(supplier,id,object("status","preparing","checkedIds",JSONArray.of("chicken")),false);throw new IllegalStateException("fixture rollback");}));
        assertEquals("pending",service.order(customer,id).getString("status"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ktv_notice_event WHERE kind='accepted'",Integer.class));
    }
    @Test void refundDoesNotSendStaleAcceptanceToCustomer() {
        customerTemplateAndGrant();String id=order(true);
        service.advance(supplier,id,object("status","preparing","checkedIds",JSONArray.of("chicken")),false);
        JSONObject intent=service.refundIntent(admin,id,body(id));service.confirmRefund(intent.getString("refundNo"),"SUCCESS",6400,6400);
        notices.discover();notices.dispatch();
        verify(gateway,times(1)).sendSubscription(eq("fulfilment"),eq("customer-fixture"),argThat(d -> "已退款".equals(d.getJSONObject("phrase1").getString("value"))),anyString());
    }
    String noticeStatus(String channel){return jdbc.queryForObject("SELECT status FROM ktv_notice_outbox WHERE channel=? ORDER BY id DESC LIMIT 1",String.class,channel);}
    @Test void groupCredentialIsEncryptedAndNeverReturned(){group("supplier:A01");String stored=jdbc.queryForObject("SELECT payload FROM ktv_notice_config WHERE scope='supplier:A01'",String.class);assertFalse(stored.contains(webhook));assertFalse(notices.admin(admin).toJSONString().contains(webhook));assertThrows(Exception.class,()->notices.configure(supplier,object("scope","supplier:A01","webhook",webhook)));assertThrows(Exception.class,()->notices.configure(admin,object("scope","supplier:A01","webhook","http://127.0.0.1/internal")));}
    @Test void durableDiscoveryDoesNotDuplicateAndOldAssignmentsAreCancelled(){String id=order(true);group("supplier:A01");notices.discover();notices.discover();assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM ktv_notice_outbox",Integer.class));notices.initialize();notices.dispatch();notices.dispatch();verify(gateway,times(1)).sendGroupNotice(eq(webhook),contains(id));assertEquals("sent",noticeStatus("group"));}
    @Test void acceptedOrdersAndDisabledAssignmentsNeverSendQueuedAlerts(){String id=order(true);group("supplier:A01");notices.discover();service.advance(supplier,id,object("status","preparing","checkedIds",JSONArray.of("chicken")),false);notices.dispatch();verify(gateway,never()).sendGroupNotice(anyString(),anyString());assertEquals("cancelled",noticeStatus("group"));}
    @Test void oneTimeAuthorizationIsConsumedAndReplayCannotCreateCredits(){template();JSONObject b=notices.bind(supplier,"code");JSONObject ack=object("nonce",b.get("nonce"),"templateId",b.get("templateId"),"accepted",true);notices.subscribe(supplier,ack);assertThrows(Exception.class,()->notices.subscribe(supplier,ack));order(true);notices.discover();notices.dispatch();verify(gateway,times(1)).sendSubscription(eq("staff-openid"),eq("fixture-template"),any(),contains("pages/supplier/detail?id="));assertEquals(0,notices.status(supplier).getIntValue("credits"));}
    @Test void deniedSubscriptionIsShownAsWaitingInsteadOfSent(){template();grant();when(gateway.sendSubscription(anyString(),anyString(),any(),anyString())).thenReturn(43101);order(true);notices.discover();notices.dispatch();assertEquals("waiting",noticeStatus("wechat"));assertEquals(0,notices.status(supplier).getIntValue("credits"));}
    @Test void unknownDeliveryIsNotAutomaticallyRetried(){group("supplier:A01");when(gateway.sendGroupNotice(anyString(),anyString())).thenThrow(new RuntimeException("transport timeout"));order(true);notices.discover();notices.dispatch();notices.dispatch();assertEquals("unknown",noticeStatus("group"));verify(gateway,times(1)).sendGroupNotice(anyString(),anyString());}
    @Test void missingSetupIsVisibleWithoutCallingExternalProvider(){order(true);notices.discover();notices.dispatch();assertEquals("waiting",noticeStatus("wechat"));assertEquals("waiting",noticeStatus("group"));verifyNoInteractions(gateway);}
    @Test void overdueAndShortageNotifyMerchantAndResumeCreatesNewNotice(){String id=order(true);group("admin:ktv-admin");group("supplier:A01");JSONObject o=JSON.parseObject(jdbc.queryForObject("SELECT payload FROM ktv_order WHERE id=?",String.class,id));o.put("dispatchAt",LocalDateTime.now().minusMinutes(10).toString());jdbc.update("UPDATE ktv_order SET payload=? WHERE id=?",o.toJSONString(),id);notices.discover();notices.dispatch();verify(gateway).sendGroupNotice(eq(webhook),contains("长时间未接单"));service.reportStockIssue(supplier,id,body(id));notices.discover();notices.dispatch();verify(gateway).sendGroupNotice(eq(webhook),contains("缺货待处理"));service.resumeStockOrder(admin,id,body(id));notices.discover();notices.dispatch();assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM ktv_notice_outbox",Integer.class));}
    @Test void staffAndCustomerCannotInspectOthersConfiguration(){assertThrows(Exception.class,()->notices.admin(supplier));assertFalse(notices.status(customer).getBooleanValue("bound"));assertThrows(Exception.class,()->notices.admin(customer));assertThrows(Exception.class,()->notices.retry(supplier,1));}
    @Test void reconnectPreservesCreditOnlyForTheSameWechatRecipient(){template();grant();notices.bind(supplier,"same-user");assertEquals(1,notices.status(supplier).getIntValue("credits"));when(gateway.login("other-user")).thenReturn("another-openid");notices.bind(supplier,"other-user");assertEquals(0,notices.status(supplier).getIntValue("credits"));}
    @Test void actualTaskTemplateCarriesOneTaskRoomAndCurrentTime(){JSONObject fields=object("time1","time","number2","taskCount","thing3","notice");JSONObject data=KtvNoticeService.templateData(fields,object("id","1001","room","V01"),"stock_issue");assertEquals("1",data.getJSONObject("number2").getString("value"));assertTrue(data.getJSONObject("thing3").getString("value").contains("V01 订单缺货待处理"));assertTrue(data.getJSONObject("time1").getString("value").matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}"));}
    @Test void reassigningAnOrderInPreparationNotifiesTheNewWorkerWithoutResettingProgress(){String id=order(true);service.advance(supplier,id,object("status","preparing","checkedIds",JSONArray.of("chicken")),false);service.account(admin,null,object("name","备货乙","password","Fixture-second-2026!","enabled",true,"process",true,"deliver",true));JSONObject b=body(id);b.put("supplierId","A03");service.reassign(admin,id,b);group("supplier:A03");notices.discover();notices.dispatch();assertEquals("preparing",service.order(admin,id).getString("status"));verify(gateway).sendGroupNotice(eq(webhook),contains("订单已转派给你"));notices.discover();notices.dispatch();verify(gateway,times(1)).sendGroupNotice(anyString(),anyString());}
}
