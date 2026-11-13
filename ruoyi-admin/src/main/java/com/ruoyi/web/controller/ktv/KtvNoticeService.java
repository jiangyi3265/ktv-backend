package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.PostConstruct;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.ruoyi.web.controller.ktv.KtvService.*;

/** Durable notifications, independent of payment transactions and frontend polling. */
@Service
public class KtvNoticeService {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final KtvService service;private final KtvWechat wechat;
    @Value("${ktv.notifications.encryption-key:}") private String encryptionKey;
    @Value("${ktv.notifications.enabled:true}") private boolean enabled;
    @Value("${ktv.notifications.overdue-minutes:5}") private int overdueMinutes;
    private long cursor;
    public KtvNoticeService(JdbcTemplate jdbc,TransactionTemplate tx,KtvService service,KtvWechat wechat){this.jdbc=jdbc;this.tx=tx;this.service=service;this.wechat=wechat;}
    @PostConstruct public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_notice_config (scope VARCHAR(100) PRIMARY KEY,payload LONGTEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_notice_binding (scope VARCHAR(100) PRIMARY KEY,openid VARCHAR(100) NOT NULL,template_id VARCHAR(100) NOT NULL,credits INT NOT NULL DEFAULT 0,nonce_hash VARCHAR(64),nonce_expires DATETIME,revision VARCHAR(40) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_notice_outbox (id BIGINT AUTO_INCREMENT PRIMARY KEY,event_key VARCHAR(64) NOT NULL UNIQUE,order_id VARCHAR(80) NOT NULL,scope VARCHAR(100) NOT NULL,channel VARCHAR(16) NOT NULL,kind VARCHAR(24) NOT NULL,round_no INT NOT NULL,status VARCHAR(24) NOT NULL,attempts INT NOT NULL DEFAULT 0,next_at DATETIME NOT NULL,lease_until DATETIME,error_code VARCHAR(64),created_at DATETIME NOT NULL,updated_at DATETIME NOT NULL,INDEX(status,next_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
    private String scope(JSONObject who){service.requireStaff(who);return "admin".equals(who.getString("role"))?"admin:ktv-admin":"supplier:"+who.getString("subject");}
    private JSONObject config(String scope){List<String> rows=jdbc.queryForList("SELECT payload FROM ktv_notice_config WHERE scope=?",String.class,scope);return rows.isEmpty()?new JSONObject():JSON.parseObject(rows.get(0));}
    private void save(String scope,JSONObject value){jdbc.update("INSERT INTO ktv_notice_config(scope,payload) VALUES(?,?) ON DUPLICATE KEY UPDATE payload=VALUES(payload)",scope,value.toJSONString());}
    private Map<String,Object> binding(String scope){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM ktv_notice_binding WHERE scope=?",scope);return rows.isEmpty()?null:rows.get(0);}
    public JSONObject status(JSONObject who){
        String scope=scope(who);JSONObject cfg=config("template");Map<String,Object> b=binding(scope);
        boolean bound=b!=null && Objects.equals(cfg.getString("id"),b.get("template_id"));
        return object("templateId",cfg.getString("id"),"templateReady",cfg.containsKey("id"),"longTerm",cfg.getIntValue("type")==3,"bound",bound,"credits",bound?b.get("credits"):0,"groupConfigured",config(scope).containsKey("webhook"),"overdueMinutes",Math.max(1,overdueMinutes),"enabled",enabled);
    }
    public JSONObject bind(JSONObject who,String code){
        String scope=scope(who);JSONObject template=config("template");require(template.containsKey("id"),409,"商家尚未配置微信通知模板，请联系商家");
        String openid=wechat.login(code),nonce=token();
        jdbc.update("INSERT INTO ktv_notice_binding(scope,openid,template_id,credits,nonce_hash,nonce_expires,revision) VALUES(?,?,?,0,?,?,?) ON DUPLICATE KEY UPDATE credits=CASE WHEN openid=VALUES(openid) AND template_id=VALUES(template_id) THEN credits ELSE 0 END,openid=VALUES(openid),template_id=VALUES(template_id),nonce_hash=VALUES(nonce_hash),nonce_expires=VALUES(nonce_expires),revision=VALUES(revision)",scope,openid,template.getString("id"),hash(nonce),LocalDateTime.now().plusMinutes(10),UUID.randomUUID().toString());
        return object("nonce",nonce,"templateId",template.getString("id"));
    }
    public JSONObject subscribe(JSONObject who,JSONObject body){
        String scope=scope(who),nonce=text(body,"nonce",100),template=text(body,"templateId",100);
        require(template.equals(config("template").getString("id")),409,"通知模板已变更，请重新开启提醒");
        int changed=jdbc.update("UPDATE ktv_notice_binding SET credits=LEAST(100,credits+?),nonce_hash=NULL,nonce_expires=NULL WHERE scope=? AND template_id=? AND nonce_hash=? AND nonce_expires>NOW()",body.getBooleanValue("accepted")?1:0,scope,template,hash(nonce));
        require(changed==1,409,"授权已提交或已过期，请重新开启提醒");return status(who);
    }
    public void unbind(JSONObject who){jdbc.update("DELETE FROM ktv_notice_binding WHERE scope=?",scope(who));}
    public JSONObject admin(JSONObject who){require("admin".equals(who.getString("role")),403,"仅管理员可管理通知");JSONObject result=object("template",config("template"),"encryptionReady",KtvNoticeCrypto.ready(encryptionKey),"overdueMinutes",Math.max(1,overdueMinutes),"enabled",enabled);JSONArray targets=new JSONArray();targets.add(target("admin:ktv-admin","商家管理员"));
        for(Object raw:service.snapshot(who).getJSONArray("accounts")){JSONObject a=(JSONObject)raw;targets.add(target("supplier:"+a.getString("id"),a.getString("name")));}
        result.put("targets",targets);result.put("deliveries",jdbc.queryForList("SELECT id,order_id,scope,channel,kind,status,attempts,error_code,created_at,updated_at FROM ktv_notice_outbox ORDER BY id DESC LIMIT 100"));return result;
    }
    private JSONObject target(String scope,String name){Map<String,Object>b=binding(scope);return object("scope",scope,"name",name,"groupConfigured",config(scope).containsKey("webhook"),"bound",b!=null && Objects.equals(config("template").getString("id"),b.get("template_id")),"credits",b==null || !Objects.equals(config("template").getString("id"),b.get("template_id"))?0:b.get("credits"));}
    public void configure(JSONObject who,JSONObject body){
        require("admin".equals(who.getString("role")),403,"仅管理员可管理通知");String target=text(body,"scope",100);
        if("template".equals(target)){
            if(body.getBooleanValue("remove")){save(target,new JSONObject());return;}
            String id=text(body,"templateId",100);JSONObject selected=null;
            for(Object raw:wechat.notificationTemplates().getJSONArray("data")){JSONObject t=(JSONObject)raw;if(id.equals(t.getString("priTmplId")))selected=t;}
            require(selected!=null,400,"请选择本小程序已添加的通知模板");JSONObject fields=body.getJSONObject("fields");require(fields!=null && !fields.isEmpty() && fields.size()<=5,400,"请配置模板字段");
            java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("\\{\\{(\\w+)\\.DATA}}").matcher(selected.getString("content"));Set<String> expected=new HashSet<>();while(matcher.find())expected.add(matcher.group(1));require(expected.equals(fields.keySet()),400,"模板字段与微信配置不一致");
            for(String field:fields.keySet())require(field.matches("(thing|character_string|time|phrase|number)[0-9]+") && Arrays.asList("room","order","summary","time","status","count","taskCount","notice").contains(fields.getString(field)),400,"此模板字段不适用于订单提醒，请更换模板");
            for(String field:fields.keySet()) {String value=fields.getString(field);require(field.startsWith("thing") || (field.startsWith("time") && value.equals("time")) || (field.startsWith("phrase") && value.equals("status")) || (field.startsWith("number") && Arrays.asList("count","taskCount","order").contains(value)) || (field.startsWith("character_string") && value.equals("order")),400,"模板字段类型与所选内容不匹配");}
            save(target,object("id",id,"title",selected.getString("title"),"type",selected.getIntValue("type"),"fields",fields));
        }else{
            boolean valid="admin:ktv-admin".equals(target);for(Object raw:service.snapshot(who).getJSONArray("accounts"))if(target.equals("supplier:"+((JSONObject)raw).getString("id")))valid=true;
            require(valid,400,"通知接收账号不存在");
            if(body.getBooleanValue("removeBinding")){jdbc.update("DELETE FROM ktv_notice_binding WHERE scope=?",target);}
            else if(body.getBooleanValue("remove"))save(target,new JSONObject());
            else {String webhook=text(body,"webhook",300);require(webhook.matches("https://qyapi\\.weixin\\.qq\\.com/cgi-bin/webhook/send\\?key=[a-zA-Z0-9-]{16,100}"),400,"请使用企业微信群机器人的 HTTPS 地址");save(target,object("webhook",KtvNoticeCrypto.encrypt(webhook,encryptionKey)));}
        }
        service.audit(who,"notification.configure",target,object("removed",body.getBooleanValue("remove")));
    }
    public void retry(JSONObject who,long id){require("admin".equals(who.getString("role")),403,"仅管理员可管理通知");int changed=jdbc.update("UPDATE ktv_notice_outbox SET status='queued',attempts=0,next_at=NOW(),error_code=NULL,updated_at=NOW() WHERE id=? AND status IN ('failed','unknown','waiting')",id);require(changed==1,409,"此通知无需重试或正在发送");service.audit(who,"notification.retry",String.valueOf(id),new JSONObject());}
    public JSONObject templates(JSONObject who){require("admin".equals(who.getString("role")),403,"仅管理员可管理通知");return wechat.notificationTemplates();}
    @Scheduled(initialDelay=15000,fixedDelay=10000)
    public synchronized void tick(){if(!enabled)return;try{discover();dispatch();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Notification scan deferred ({})",e.getClass().getSimpleName());}}
    void discover(){
        List<String> rows=jdbc.queryForList("SELECT payload FROM ktv_order WHERE paid=TRUE AND status IN ('pending','stock_issue','preparing','delivering') AND id>? ORDER BY id LIMIT 100",String.class,cursor);
        if(rows.isEmpty()){cursor=0;return;}
        for(String raw:rows){JSONObject o=JSON.parseObject(raw);cursor=Long.parseLong(o.getString("id"));int round=o.getIntValue("dispatchRound");
            if("stock_issue".equals(o.getString("status")))enqueue(o,"admin:ktv-admin","stock_issue",o.getIntValue("version"));
            else if(!"pending".equals(o.getString("status"))) {if(round>1 && Objects.equals(o.getString("status"),o.getString("dispatchStatus")))enqueue(o,"supplier:"+o.getString("supplierId"),"reassigned",round);}
            else {String supplier=o.getString("supplierId");if(supplier!=null && !supplier.isEmpty())enqueue(o,"supplier:"+supplier,"new_order",round);
                if(overdue(o))enqueue(o,"admin:ktv-admin","overdue",round);}
        }
        if(rows.size()<100)cursor=0;
    }
    private boolean overdue(JSONObject o){String time=o.getString("dispatchAt");if(time==null)time=o.getString("createdAt");try{return LocalDateTime.parse(time).plusMinutes(Math.max(1,overdueMinutes)).isBefore(LocalDateTime.now());}catch(Exception e){return false;}}
    private void enqueue(JSONObject o,String scope,String kind,int round){for(String channel:Arrays.asList("wechat","group"))jdbc.update("INSERT IGNORE INTO ktv_notice_outbox(event_key,order_id,scope,channel,kind,round_no,status,next_at,created_at,updated_at) VALUES(?,?,?,?,?,?,'queued',NOW(),NOW(),NOW())",hash(o.getString("id")+":"+scope+":"+kind+":"+round+":"+channel),o.getString("id"),scope,channel,kind,round);}
    void dispatch(){
        // A timed-out send may already have reached WeChat. Do not resend it automatically.
        jdbc.update("UPDATE ktv_notice_outbox SET status='unknown',error_code='INTERRUPTED',updated_at=NOW() WHERE status='sending' AND lease_until<NOW()");
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM ktv_notice_outbox WHERE status IN ('queued','waiting','retry') AND next_at<=NOW() ORDER BY next_at,id LIMIT 30")){
            long id=((Number)row.get("id")).longValue();if(jdbc.update("UPDATE ktv_notice_outbox SET status='sending',lease_until=?,updated_at=NOW() WHERE id=? AND status IN ('queued','waiting','retry') AND next_at<=NOW()",LocalDateTime.now().plusMinutes(3),id)!=1)continue;
            try{send(row);}catch(Exception e){finish(id,"unknown","DELIVERY_UNCONFIRMED",0);}
        }
    }
    private void finish(long id,String status,String code,int delay){jdbc.update("UPDATE ktv_notice_outbox SET status=?,error_code=?,next_at=?,updated_at=NOW(),lease_until=NULL WHERE id=?",status,code,LocalDateTime.now().plusSeconds(delay),id);}
    private JSONObject eligible(Map<String,Object> job){
        List<String> rows=jdbc.queryForList("SELECT payload FROM ktv_order WHERE id=?",String.class,job.get("order_id"));if(rows.isEmpty())return null;JSONObject o=JSON.parseObject(rows.get(0));String scope=(String)job.get("scope"),kind=(String)job.get("kind");int round=((Number)job.get("round_no")).intValue();
        if(!o.getBooleanValue("paid"))return null;
        if("stock_issue".equals(kind)){if(!"stock_issue".equals(o.getString("status"))||o.getIntValue("version")!=round)return null;}
        else if("reassigned".equals(kind)){if(!Arrays.asList("preparing","delivering").contains(o.getString("status")) || !Objects.equals(o.getString("status"),o.getString("dispatchStatus")) || o.getIntValue("dispatchRound")!=round)return null;}
        else if(!"pending".equals(o.getString("status"))||o.getIntValue("dispatchRound")!=round)return null;
        if(scope.startsWith("supplier:")){
            if(!scope.equals("supplier:"+o.getString("supplierId")))return null;
            try{service.requireStaff(object("role","supplier","subject",o.getString("supplierId")));}catch(Exception e){return null;}
        }
        return o;
    }
    private void send(Map<String,Object> job){
        long id=((Number)job.get("id")).longValue();JSONObject o=eligible(job);if(o==null){finish(id,"cancelled","ORDER_CHANGED",0);return;}
        String scope=(String)job.get("scope"),channel=(String)job.get("channel"),kind=(String)job.get("kind");int code;
        if("group".equals(channel)){
            String encrypted=config(scope).getString("webhook");if(encrypted==null){finish(id,"waiting","GROUP_NOT_CONFIGURED",60);return;}
            if(!KtvNoticeCrypto.ready(encryptionKey)){finish(id,"failed","ENCRYPTION_NOT_READY",0);return;}
            String message=heading(kind)+"\n订单："+o.getString("id")+"\n包厢："+o.getString("room")+"\n餐品："+summary(o)+"\n请打开 KTV 点餐小程序"+(scope.startsWith("supplier:")?"的接单工作台，核对订单当前进度并及时处理。":"的管理端处理订单。");
            jdbc.update("UPDATE ktv_notice_outbox SET attempts=attempts+1 WHERE id=?",id);code=wechat.sendGroupNotice(KtvNoticeCrypto.decrypt(encrypted,encryptionKey),message);
        }else{
            JSONObject cfg=config("template");if(!cfg.containsKey("id")){finish(id,"waiting","TEMPLATE_NOT_CONFIGURED",60);return;}
            Map<String,Object>b=binding(scope);if(b==null || !cfg.getString("id").equals(b.get("template_id"))){finish(id,"waiting","NOT_BOUND",60);return;}
            boolean permanent=cfg.getIntValue("type")==3;
            int claimed=jdbc.update("UPDATE ktv_notice_binding SET credits=credits-? WHERE scope=? AND revision=? AND credits>0",permanent?0:1,scope,b.get("revision"));
            if(claimed!=1){finish(id,"waiting","SUBSCRIPTION_REQUIRED",60);return;}
            JSONObject data=templateData(cfg.getJSONObject("fields"),o,kind);
            jdbc.update("UPDATE ktv_notice_outbox SET attempts=attempts+1 WHERE id=?",id);
            code=wechat.sendSubscription((String)b.get("openid"),cfg.getString("id"),data,(scope.startsWith("supplier:")?"pages/supplier/detail":"pages/admin/order-detail")+"?id="+o.getString("id"));
            if(code==43101)jdbc.update("UPDATE ktv_notice_binding SET credits=0 WHERE scope=? AND revision=?",scope,b.get("revision"));
            else if(code!=0 && !permanent)jdbc.update("UPDATE ktv_notice_binding SET credits=LEAST(100,credits+1) WHERE scope=? AND revision=?",scope,b.get("revision"));
        }
        if(code==0){finish(id,"sent",null,0);return;}
        int attempt=((Number)job.get("attempts")).intValue()+1;
        if(code==43101)finish(id,"waiting","SUBSCRIPTION_REQUIRED",60);
        else if((code==-1 || code==45009) && attempt<3)finish(id,"retry","WECHAT_"+code,60*attempt);
        else finish(id,"failed","WECHAT_"+code,0);
    }
    static String heading(String kind){return "reassigned".equals(kind)?"订单已转派给你":"stock_issue".equals(kind)?"订单缺货待处理":"overdue".equals(kind)?"订单长时间未接单":"订单待接单";}
    static String summary(JSONObject o){StringJoiner text=new StringJoiner("、");for(Object raw:o.getJSONArray("items")){JSONObject item=(JSONObject)raw;text.add(item.getString("name")+"×"+item.getIntValue("quantity"));}return trim(text.toString(),500);}
    static String trim(String value,int max){return value.codePointCount(0,value.length())<=max?value:value.substring(0,value.offsetByCodePoints(0,max));}
    static JSONObject templateData(JSONObject fields,JSONObject o,String kind){JSONObject data=new JSONObject();for(String field:fields.keySet()){
        String value;switch(fields.getString(field)){case "room":value=o.getString("room")+"包厢";break;case "order":value=o.getString("id");break;case "time":value=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));break;case "status":value="reassigned".equals(kind)?"订单转派":"stock_issue".equals(kind)?"缺货待处理":"overdue".equals(kind)?"超时未接单":"待接单";break;case "taskCount":value="1";break;case "notice":value=o.getString("room")+" "+heading(kind)+" #"+o.getString("id");break;case "count":int count=0;for(Object raw:o.getJSONArray("items"))count+=((JSONObject)raw).getIntValue("quantity");value=String.valueOf(count);break;default:value=summary(o);}
        if(field.startsWith("thing"))value=trim(value,20);else if(field.startsWith("phrase"))value=trim(value,5);else if(field.startsWith("character_string"))value=trim(value,32);
        data.put(field,object("value",value));}return data;}
}
