package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.ruoyi.web.controller.ktv.KtvService.*;

/** Payment and refund cashbook. Amounts are integer fen, never a provider wallet balance. */
@Service
public class KtvFinanceService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    public KtvFinanceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static void createTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_finance_entry (id BIGINT AUTO_INCREMENT PRIMARY KEY,event_key VARCHAR(100) NOT NULL UNIQUE,kind VARCHAR(16) NOT NULL,order_id VARCHAR(80) NOT NULL,room VARCHAR(12) NOT NULL,supplier_id VARCHAR(40) NOT NULL,amount_cents BIGINT NOT NULL,provider_no VARCHAR(64) NOT NULL,occurred_at DATETIME NOT NULL,time_source VARCHAR(16) NOT NULL,recorded_at DATETIME NOT NULL,INDEX(occurred_at,id),INDEX(order_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    static void record(JdbcTemplate jdbc, JSONObject order, String kind, String providerNo, String time) {
        require(Arrays.asList("receipt", "refund").contains(kind), 500, "账单类型无效");
        long amount = order.getLongValue("totalCents");
        require(amount > 0 && providerNo != null && !providerNo.isEmpty(), 500, "账单凭据不完整");
        LocalDateTime occurred;
        String source;
        try {
            occurred = OffsetDateTime.parse(time).atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
            source = "provider";
        } catch (Exception ignored) {
            try { occurred = LocalDateTime.parse(time).atZone(ZoneId.systemDefault()).withZoneSameInstant(BUSINESS_ZONE).toLocalDateTime(); source = "order"; }
            catch (Exception invalid) { occurred = LocalDateTime.now(BUSINESS_ZONE); source = "recorded"; }
        }
        jdbc.update("INSERT IGNORE INTO ktv_finance_entry(event_key,kind,order_id,room,supplier_id,amount_cents,provider_no,occurred_at,time_source,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                kind + ":" + providerNo, kind, order.getString("id"), order.getString("room"),
                order.getString("supplierId") == null ? "" : order.getString("supplierId"),
                "refund".equals(kind) ? -amount : amount, providerNo, Timestamp.valueOf(occurred), source,
                Timestamp.valueOf(LocalDateTime.now(BUSINESS_ZONE)));
    }

    static void backfill(JdbcTemplate jdbc) {
        long cursor = 0;
        while (true) {
            List<Map<String,Object>> rows = jdbc.queryForList("SELECT o.id,o.payload,p.transaction_id,p.refund_no,p.refund_status FROM ktv_order o JOIN ktv_payment p ON p.order_id=o.id WHERE o.id>? AND o.paid=TRUE AND p.transaction_id IS NOT NULL ORDER BY o.id LIMIT 200", cursor);
            if (rows.isEmpty()) return;
            for (Map<String,Object> row : rows) {
                cursor = ((Number)row.get("id")).longValue();
                JSONObject order = JSON.parseObject((String)row.get("payload"));
                if (!"wechat".equals(order.getString("paymentMode"))) continue;
                record(jdbc, order, "receipt", (String)row.get("transaction_id"), order.getString("paidAt"));
                if ("SUCCESS".equals(row.get("refund_status")) && "refunded".equals(order.getString("status")))
                    record(jdbc, order, "refund", (String)row.get("refund_no"), order.getString("refundedAt"));
            }
        }
    }

    private void allowed(JSONObject who) {
        require(who != null && Arrays.asList("admin","supplier").contains(who.getString("role")),403,"无权查看收款记录");
        if ("supplier".equals(who.getString("role"))) {
            require(who.getString("subject") != null,403,"食堂身份无效");
            JSONObject state=JSON.parseObject(jdbc.queryForObject("SELECT payload FROM ktv_shared_state WHERE id=1",String.class));
            JSONObject account=find(state.getJSONArray("accounts"),who.getString("subject"));
            require(account!=null && account.getBooleanValue("enabled"),403,"食堂账号已停用，请联系管理员");
        }
    }
    private void scope(Filter f, JSONObject who) {
        if ("supplier".equals(who.getString("role"))) {
            require(who.getString("subject") != null,403,"食堂身份无效");
            f.sql += " AND EXISTS (SELECT 1 FROM ktv_order o WHERE o.id=ktv_finance_entry.order_id AND o.supplier_id=?)";
            f.values.add(who.getString("subject"));
        }
    }

    private static class Filter {
        String sql = " WHERE 1=1";
        final List<Object> values = new ArrayList<>();
    }
    private Filter filter(String from, String to, String kind, String search) {
        Filter f = new Filter();
        LocalDate start = date(from), end = date(to);
        require(start == null || end == null || !start.isAfter(end),400,"开始日期不能晚于结束日期");
        if (start != null) { f.sql += " AND occurred_at>=?"; f.values.add(Timestamp.valueOf(start.atStartOfDay())); }
        if (end != null) { f.sql += " AND occurred_at<?"; f.values.add(Timestamp.valueOf(end.plusDays(1).atStartOfDay())); }
        require(kind == null || kind.isEmpty() || Arrays.asList("receipt","refund").contains(kind),400,"账单类型无效");
        if (kind != null && !kind.isEmpty()) { f.sql += " AND kind=?"; f.values.add(kind); }
        if (search != null && !search.trim().isEmpty()) {
            String value = search.trim();
            require(value.length() <= 80,400,"搜索内容过长");
            f.sql += " AND (order_id=? OR room=? OR provider_no=?)";
            f.values.add(value); f.values.add(value); f.values.add(value);
        }
        return f;
    }
    private LocalDate date(String value) {
        if (value == null || value.isEmpty()) return null;
        try { LocalDate date = LocalDate.parse(value); require(date.getYear() >= 2000 && date.getYear() <= 2100,400,"日期范围无效"); return date; }
        catch (DateTimeParseException e) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"日期格式应为 YYYY-MM-DD"); }
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public JSONObject ledger(JSONObject who, String from, String to, String kind, String search, int page) {
        allowed(who); require(page >= 1 && page <= 100000,400,"页码无效");
        Filter f = filter(from,to,kind,search); scope(f,who);
        Map<String,Object> sums = jdbc.queryForMap("SELECT COUNT(*) count,COALESCE(SUM(CASE WHEN amount_cents>0 THEN amount_cents ELSE 0 END),0) receipts,COALESCE(SUM(CASE WHEN amount_cents<0 THEN -amount_cents ELSE 0 END),0) refunds,COALESCE(SUM(amount_cents),0) net FROM ktv_finance_entry" + f.sql, f.values.toArray());
        List<Object> args = new ArrayList<>(f.values); args.add(50); args.add((page-1)*50);
        JSONArray entries = rows(jdbc.queryForList("SELECT *, (SELECT supplier_id FROM ktv_order o WHERE o.id=ktv_finance_entry.order_id) current_supplier_id FROM ktv_finance_entry" + f.sql + " ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?", args.toArray()));
        if ("supplier".equals(who.getString("role"))) for(Object raw:entries) { JSONObject r=(JSONObject)raw; r.remove("providerNo"); }
        return object("entries",entries,"total",((Number)sums.get("count")).longValue(),"page",page,"pageSize",50,
                "receiptCents",((Number)sums.get("receipts")).longValue(),"refundCents",((Number)sums.get("refunds")).longValue(),"netCents",((Number)sums.get("net")).longValue(),"timezone","Asia/Shanghai");
    }
    public JSONArray daily(JSONObject who, String from, String to) {
        allowed(who);
        if (from==null || from.isEmpty()) from=LocalDate.now(BUSINESS_ZONE).withDayOfMonth(1).toString();
        if (to==null || to.isEmpty()) to=LocalDate.now(BUSINESS_ZONE).toString();
        Filter f = filter(from,to,"",""); scope(f,who);
        require(java.time.temporal.ChronoUnit.DAYS.between(date(from),date(to))<=366,400,"按日汇总一次最多查询一年，请缩小日期范围");
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT CAST(occurred_at AS DATE) business_day,COUNT(*) count,SUM(CASE WHEN amount_cents>0 THEN amount_cents ELSE 0 END) receipts,SUM(CASE WHEN amount_cents<0 THEN -amount_cents ELSE 0 END) refunds,SUM(amount_cents) net FROM ktv_finance_entry"+f.sql+" GROUP BY CAST(occurred_at AS DATE) ORDER BY business_day DESC",f.values.toArray());
        JSONArray result = new JSONArray();
        for(Map<String,Object> r:rows) result.add(object("date",r.get("business_day").toString(),"count",r.get("count"),"receiptCents",r.get("receipts"),"refundCents",r.get("refunds"),"netCents",r.get("net")));
        return result;
    }
    private JSONArray rows(List<Map<String,Object>> rows) {
        JSONArray result = new JSONArray();
        for(Map<String,Object> r:rows) result.add(object("id",String.valueOf(r.get("id")),"kind",r.get("kind"),"orderId",r.get("order_id"),"room",r.get("room"),"supplierId",r.containsKey("current_supplier_id")?r.get("current_supplier_id"):r.get("supplier_id"),"amountCents",r.get("amount_cents"),"providerNo",r.get("provider_no"),"occurredAt",r.get("occurred_at").toString(),"timeSource",r.get("time_source")));
        return result;
    }
    public String export(JSONObject who, String from, String to, String kind, String search) {
        allowed(who); require("admin".equals(who.getString("role")),403,"仅管理员可导出全店账单"); Filter f=filter(from,to,kind,search);
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM ktv_finance_entry"+f.sql,Long.class,f.values.toArray());
        require(count!=null && count<=20000,400,"单次最多导出 20000 条，请缩小日期范围");
        JSONArray records=rows(jdbc.queryForList("SELECT *, (SELECT supplier_id FROM ktv_order o WHERE o.id=ktv_finance_entry.order_id) current_supplier_id FROM ktv_finance_entry"+f.sql+" ORDER BY occurred_at,id",f.values.toArray()));
        StringBuilder csv=new StringBuilder("\uFEFF账单号,类型,订单号,包厢,食堂,收支金额（元）,微信交易或退款单号,发生时间（北京时间）,时间来源\r\n");
        for(Object raw:records) {
            JSONObject r=(JSONObject)raw;
            String[] cells={r.getString("id"),"receipt".equals(r.getString("kind"))?"收款":"退款",r.getString("orderId"),r.getString("room"),r.getString("supplierId"),java.math.BigDecimal.valueOf(r.getLongValue("amountCents"),2).toPlainString(),r.getString("providerNo"),r.getString("occurredAt"),r.getString("timeSource")};
            for(int i=0;i<cells.length;i++){if(i>0)csv.append(',');csv.append(csvCell(cells[i],i!=5));}csv.append("\r\n");
        }
        return csv.toString();
    }
    static String csvCell(String value, boolean text) {
        String s=value==null?"":value;
        if(text && (s.matches("(?s)^[\\s]*[=+@-].*") || s.matches("[0-9]{12,}"))) s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
}
