package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Shared KTV business state. Every write takes a database row lock, including payment and assignment. */
@Service
public class KtvService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();
    @Value("${ktv.bootstrap-admin-password:}") private String adminPassword;
    @Value("${ktv.bootstrap-supplier-password:}") private String supplierPassword;
    @Value("${ktv.demo-payment-enabled:false}") private boolean demoPayment;

    public KtvService(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }
    public static JSONObject object(Object... pairs) {
        JSONObject result=new JSONObject();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    static void require(boolean condition, int status, String message) {
        if(!condition) throw new ResponseStatusException(HttpStatus.valueOf(status),message);
    }
    static String text(JSONObject value,String key,int max) {
        String s=value.getString(key);
        require(s!=null && !s.trim().isEmpty() && s.length()<=max,400,"无效字段："+key);
        return s.trim();
    }
    static String hash(String value) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    static String token() {
        byte[] data=new byte[32]; new SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
    static JSONObject copy(JSONObject value) { return JSON.parseObject(value.toJSONString()); }
    static JSONObject find(JSONArray array,String id) {
        for(int i=0;i<array.size();i++) if(id.equals(array.getJSONObject(i).getString("id"))) return array.getJSONObject(i);
        return null;
    }
    private JSONObject read() { return JSON.parseObject(jdbc.queryForObject("SELECT payload FROM ktv_shared_state WHERE id=1",String.class)); }
    private <T> T change(Function<JSONObject,T> action) {
        return tx.execute(status -> {
            JSONObject state=JSON.parseObject(jdbc.queryForObject("SELECT payload FROM ktv_shared_state WHERE id=1 FOR UPDATE",String.class));
            T result=action.apply(state);
            state.put("revision",state.getLongValue("revision")+1);
            jdbc.update("UPDATE ktv_shared_state SET payload=? WHERE id=1",state.toJSONString());
            return result;
        });
    }
    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_shared_state (id INT PRIMARY KEY,payload LONGTEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_session (token_hash VARCHAR(64) PRIMARY KEY,role VARCHAR(16) NOT NULL,subject VARCHAR(80) NOT NULL,expires_at DATETIME NOT NULL,INDEX(expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ktv_shared_state",Integer.class)>0) return;
        require(adminPassword.length()>=12 && supplierPassword.length()>=12,503,"首次启动请配置 KTV 管理员及食堂初始密码（至少 12 位）");
        JSONArray products=new JSONArray();
        String[][] rows={{"chicken","宫保鸡丁饭","主食","32","经典川味，香辣过瘾"},{"beef","黑椒牛柳饭","主食","38","黑椒浓郁，牛肉鲜嫩"},{"bites","香酥鸡米花","小吃","22","外酥里嫩，香气四溢"},{"noodle","番茄牛腩面","主食","36","浓郁番茄，软烂牛腩"}};
        for(String[] row:rows) products.add(object("id",row[0],"name",row[1],"category",row[2],"price",Integer.valueOf(row[3]),"image",row[0],"description",row[4],"onSale",true,"version",1));
        JSONArray rooms=new JSONArray();
        for(String id:Arrays.asList("A08","A09","B03","B05")) rooms.add(object("id",id,"bound",!id.equals("B05"),"version",1));
        JSONArray accounts=new JSONArray();
        accounts.add(object("id","A01","name","合作食堂 A","enabled",true,"process",true,"deliver",true,"passwordHash",passwords.encode(supplierPassword),"version",1));
        accounts.add(object("id","A02","name","备用账号","enabled",false,"process",true,"deliver",true,"passwordHash",passwords.encode(token()),"version",1));
        JSONObject state=object("revision",1,"nextOrder",1000,"products",products,"rooms",rooms,"accounts",accounts,"orders",new JSONArray(),"adminHash",passwords.encode(adminPassword));
        jdbc.update("INSERT IGNORE INTO ktv_shared_state(id,payload) VALUES(1,?)",state.toJSONString());
    }
    public JSONObject customerSession() { return session("customer","customer-"+UUID.randomUUID()); }
    public synchronized JSONObject staffSession(JSONObject body,String remote) {
        String username=text(body,"username",64),password=text(body,"password",100);
        String key=remote+":"+username;
        long now=System.currentTimeMillis();
        long[] attempts=failures.get(key);
        require(attempts==null || attempts[0]<5 || now-attempts[1]>600000,429,"登录失败次数过多，请十分钟后重试");
        JSONObject state=read(), account=find(state.getJSONArray("accounts"),username);
        String encoded=username.equals("ktv-admin")?state.getString("adminHash"):account==null?"":account.getString("passwordHash");
        if(encoded==null || !passwords.matches(password,encoded) || (account!=null && !account.getBooleanValue("enabled"))) {
            if(attempts==null || now-attempts[1]>600000) attempts=new long[]{0,now};
            attempts[0]++; failures.put(key,attempts);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"账号、密码错误或账号已停用");
        }
        failures.remove(key);
        return session(username.equals("ktv-admin")?"admin":"supplier",username);
    }
    private JSONObject session(String role,String subject) {
        String token=token();
        jdbc.update("DELETE FROM ktv_session WHERE expires_at<NOW()");
        jdbc.update("INSERT INTO ktv_session(token_hash,role,subject,expires_at) VALUES(?,?,?,?)",hash(token),role,subject,LocalDateTime.now().plusHours(role.equals("customer")?720:8));
        return object("token",token,"role",role,"subject",subject);
    }
    public JSONObject identity(String token) {
        require(token!=null && token.length()>=32,401,"请登录后继续");
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT role,subject FROM ktv_session WHERE token_hash=? AND expires_at>NOW()",hash(token));
        require(!rows.isEmpty(),401,"登录已过期，请重新登录");
        return object("role",rows.get(0).get("role"),"subject",rows.get(0).get("subject"));
    }
    public void logout(String token) { if(token!=null) jdbc.update("DELETE FROM ktv_session WHERE token_hash=?",hash(token)); }
    private void role(JSONObject who,String role) { require(role.equals(who.getString("role")),403,"当前账号无权执行此操作"); }
    private JSONObject supplier(JSONObject state,JSONObject who) {
        JSONObject account=find(state.getJSONArray("accounts"),who.getString("subject"));
        require(account!=null && account.getBooleanValue("enabled"),403,"食堂账号已停用，请联系管理员");
        return account;
    }
    public JSONObject snapshot(JSONObject who) {
        JSONObject state=read(); String role=who.getString("role");
        JSONArray orders=new JSONArray(),products=new JSONArray(),rooms=new JSONArray(),accounts=new JSONArray();
        if(role.equals("supplier")) { JSONObject account=copy(supplier(state,who)); account.remove("passwordHash"); accounts.add(account); }
        else {
            for(Object raw:state.getJSONArray("products")) { JSONObject p=(JSONObject)raw; if(role.equals("admin") || p.getBooleanValue("onSale")) products.add(p); }
            for(Object raw:state.getJSONArray("rooms")) { JSONObject room=(JSONObject)raw; if(role.equals("admin") || room.getBooleanValue("bound")) rooms.add(room); }
        }
        if(role.equals("admin")) for(Object raw:state.getJSONArray("accounts")) { JSONObject a=copy((JSONObject)raw); a.remove("passwordHash"); accounts.add(a); }
        for(Object raw:state.getJSONArray("orders")) {
            JSONObject order=(JSONObject)raw;
            if(role.equals("admin") || (role.equals("customer") && who.getString("subject").equals(order.getString("customerId")))) {
                JSONObject safe=copy(order); safe.remove("requestKey"); safe.remove("requestHash"); orders.add(safe);
            } else if(role.equals("supplier") && order.getBooleanValue("paid") && who.getString("subject").equals(order.getString("supplierId"))) {
                JSONArray items=new JSONArray();
                for(Object itemRaw:order.getJSONArray("items")) { JSONObject item=(JSONObject)itemRaw; items.add(object("id",item.get("id"),"name",item.get("name"),"quantity",item.get("quantity"),"image",item.get("image"),"customImage",item.get("customImage"))); }
                orders.add(object("id",order.get("id"),"room",order.get("room"),"status",order.get("status"),"time",order.get("time"),"items",items,"events",order.get("events"),"version",order.get("version")));
            }
        }
        return object("revision",state.get("revision"),"role",role,"subject",who.get("subject"),"products",products,"rooms",rooms,"accounts",accounts,"orders",orders,"demoPayment",demoPayment);
    }
    public JSONObject createOrder(JSONObject who,JSONObject body) {
        role(who,"customer");
        String room=text(body,"room",12),key=text(body,"requestKey",80);
        JSONArray requested=body.getJSONArray("items");
        require(requested!=null && requested.size()>0 && requested.size()<=50,400,"请选择餐品");
        String fingerprint=hash(room+requested.toJSONString());
        return change(state -> {
            for(Object raw:state.getJSONArray("orders")) { JSONObject existing=(JSONObject)raw;
                if(who.getString("subject").equals(existing.getString("customerId")) && key.equals(existing.getString("requestKey"))) {
                    require(fingerprint.equals(existing.getString("requestHash")),409,"请求编号已用于另一张订单"); return object("id",existing.getString("id"));
                }
            }
            JSONObject selectedRoom=find(state.getJSONArray("rooms"),room);
            require(selectedRoom!=null && selectedRoom.getBooleanValue("bound"),409,"当前包厢未启用，请重新扫码");
            JSONObject assigned=null;
            for(Object raw:state.getJSONArray("accounts")) { JSONObject a=(JSONObject)raw; if(a.getBooleanValue("enabled")&&a.getBooleanValue("process")&&a.getBooleanValue("deliver")){assigned=a;break;} }
            require(assigned!=null,409,"暂时没有可接单食堂，请联系 KTV");
            JSONArray items=new JSONArray(); Set<String> seen=new HashSet<>(); long cents=0;
            for(int i=0;i<requested.size();i++) {
                JSONObject line=requested.getJSONObject(i); String id=text(line,"id",64); JSONObject product=find(state.getJSONArray("products"),id);
                Number q=line.getObject("quantity",Number.class);
                require(q!=null && q.doubleValue()==q.intValue() && q.intValue()>0 && q.intValue()<=99 && seen.add(id),400,"餐品数量无效");
                require(product!=null && product.getBooleanValue("onSale"),409,"餐品已下架，请重新选择");
                JSONObject item=copy(product); item.put("quantity",q.intValue()); items.add(item);
                cents+=Math.round(product.getDoubleValue("price")*100)*q.intValue();
            }
            int number=state.getIntValue("nextOrder"); state.put("nextOrder",number+1);
            String id=String.valueOf(number);
            JSONObject order=object("id",id,"room",room,"customerId",who.get("subject"),"supplierId",assigned.get("id"),"status","unpaid","paid",false,"items",items,"totalCents",cents,"time","今天 "+LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),"createdAt",LocalDateTime.now().toString(),"requestKey",key,"requestHash",fingerprint,"version",1,"events",new JSONArray());
            event(order,"订单已创建，等待支付"); state.getJSONArray("orders").add(0,order);
            return object("id",id);
        });
    }
    private void event(JSONObject order,String label) {
        LocalDateTime now=LocalDateTime.now();
        order.getJSONArray("events").add(object("time",now.format(DateTimeFormatter.ofPattern("HH:mm")),"at",now.toString(),"label",label));
    }
    public JSONObject advance(JSONObject who,String id,JSONObject body,boolean payment) {
        if(payment) { role(who,"customer"); require(demoPayment,403,"模拟支付已关闭，请接入微信支付"); }
        else role(who,"supplier");
        return change(state -> {
            JSONObject order=find(state.getJSONArray("orders"),id); require(order!=null,404,"订单不存在");
            String next=payment?"pending":text(body,"status",20),current=order.getString("status");
            if(payment) require(who.getString("subject").equals(order.getString("customerId")),403,"无权支付该订单");
            else {
                JSONObject account=supplier(state,who);
                require(who.getString("subject").equals(order.getString("supplierId")) && order.getBooleanValue("paid"),403,"无权处理该订单");
                require(account.getBooleanValue(next.equals("completed")?"deliver":"process"),403,"当前账号暂无此操作权限");
            }
            if((payment && order.getBooleanValue("paid")) || current.equals(next)) return object("id",id);
            Map<String,String> flow=new HashMap<>(); flow.put("unpaid","pending"); flow.put("pending","preparing"); flow.put("preparing","delivering"); flow.put("delivering","completed");
            require(next.equals(flow.get(current)),409,"订单状态已变化，请刷新后重试");
            if(payment) {
                JSONObject assigned=find(state.getJSONArray("accounts"),order.getString("supplierId"));
                if(assigned==null || !assigned.getBooleanValue("enabled") || !assigned.getBooleanValue("process") || !assigned.getBooleanValue("deliver")) {
                    assigned=null;
                    for(Object raw:state.getJSONArray("accounts")) { JSONObject a=(JSONObject)raw; if(a.getBooleanValue("enabled")&&a.getBooleanValue("process")&&a.getBooleanValue("deliver")){assigned=a;break;} }
                    require(assigned!=null,409,"暂时没有可接单食堂，请联系 KTV");
                    order.put("supplierId",assigned.getString("id"));
                }
            }
            if(next.equals("delivering")) {
                JSONArray checked=body.getJSONArray("checkedIds");
                require(checked!=null,400,"请核对餐品数量");
                for(Object raw:order.getJSONArray("items")) require(checked.contains(((JSONObject)raw).getString("id")),400,"请核对全部餐品数量");
            }
            order.put("status",next); order.put("paid",true); order.put("version",order.getIntValue("version")+1);
            String label=next.equals("pending")?"模拟支付成功":next.equals("preparing")?"食堂接单":next.equals("delivering")?"开始配送":"配送方确认送达";
            event(order,label); if(payment) order.put("paymentMode","demo");
            return object("id",id);
        });
    }
    private void version(JSONObject existing,JSONObject body) {
        require(existing!=null,404,"记录不存在");
        require(existing.getIntValue("version")==body.getIntValue("version"),409,"记录已被其他端修改，请刷新后重试");
    }
    public JSONObject product(JSONObject who,String id,JSONObject body) {
        role(who,"admin");
        return change(state -> {
            JSONObject existing=find(state.getJSONArray("products"),id);
            if(existing!=null) version(existing,body); else require(id.matches("food-[a-zA-Z0-9-]{1,50}"),400,"餐品编号无效");
            String name=text(body,"name",60),category=text(body,"category",10); double price=body.getDoubleValue("price");
            require(Arrays.asList("主食","小吃").contains(category) && Double.isFinite(price) && price>0 && price<=9999 && Math.abs(price*100-Math.rint(price*100))<0.00001,400,"餐品价格或分类无效");
            String custom=body.getString("customImage");
            require(custom==null || custom.isEmpty() || custom.matches("/ktv-api/assets/[a-f0-9-]+\\.(png|jpg)"),400,"请先上传餐品图片");
            JSONObject p=object("id",id,"name",name,"category",category,"price",price,"image",body.getString("image"),"customImage",custom,"description",body.getString("description"),"onSale",body.getBooleanValue("onSale"),"version",existing==null?1:existing.getIntValue("version")+1);
            if(existing==null) state.getJSONArray("products").add(p); else { existing.clear();existing.putAll(p); }
            return object("id",id);
        });
    }
    public JSONObject room(JSONObject who,String id,JSONObject body) {
        role(who,"admin");
        return change(state -> {
            JSONObject existing=find(state.getJSONArray("rooms"),id); String name=text(body,"id",12);
            require(name.matches("[A-Z0-9\u4e00-\u9fa5-]{1,12}"),400,"包厢编号无效");
            if(existing!=null) version(existing,body);
            require(id.equals(name) || find(state.getJSONArray("rooms"),name)==null,409,"包厢编号已存在");
            JSONObject room=object("id",name,"bound",body.getBooleanValue("bound"),"version",existing==null?1:existing.getIntValue("version")+1);
            if(existing==null) state.getJSONArray("rooms").add(room); else {existing.clear();existing.putAll(room);}
            return object("id",name);
        });
    }
    public JSONObject account(JSONObject who,String id,JSONObject body) {
        role(who,"admin");
        return change(state -> {
            JSONObject existing=id==null?null:find(state.getJSONArray("accounts"),id);
            if(id!=null) version(existing,body);
            String name=text(body,"name",60);
            for(Object raw:state.getJSONArray("accounts")) { JSONObject a=(JSONObject)raw; require(a==existing || !name.equals(a.getString("name")),409,"账号名称已存在"); }
            String password=body.getString("password");
            if(existing==null || (password!=null&&!password.isEmpty())) require(password!=null && password.length()>=12 && password.length()<=72,400,"登录密码需为 12–72 位");
            String accountId=id==null?"A"+String.format("%02d",state.getJSONArray("accounts").size()+1):id;
            JSONObject a=object("id",accountId,"name",name,"enabled",body.getBooleanValue("enabled"),"process",body.getBooleanValue("process"),"deliver",body.getBooleanValue("deliver"),"version",existing==null?1:existing.getIntValue("version")+1,"passwordHash",password!=null&&!password.isEmpty()?passwords.encode(password):existing.getString("passwordHash"));
            if(existing==null) state.getJSONArray("accounts").add(a); else {existing.clear();existing.putAll(a);}
            if(password!=null && !password.isEmpty()) jdbc.update("DELETE FROM ktv_session WHERE role='supplier' AND subject=?",accountId);
            return object("id",accountId);
        });
    }
}
