package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

import static com.ruoyi.web.controller.ktv.KtvService.*;

/** Kitchen settlement is based on delivered portions and the price captured at checkout. */
final class KtvSettlement {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    static void applyPrice(JSONObject product, JSONObject previous, JSONObject input) {
        Object value = input.containsKey("settlementPrice") ? input.get("settlementPrice")
                : previous == null ? null : previous.get("settlementPrice");
        if (value == null || "".equals(value)) return;
        String amount = value.toString();
        require(amount.matches("[0-9]{1,4}(\\.[0-9]{1,2})?") && new BigDecimal(amount).compareTo(BigDecimal.valueOf(9999)) <= 0,
                400, "请输入 0 至 9999 元的食堂结算单价，最多两位小数");
        product.put("settlementPrice",new BigDecimal(amount));
    }

    static void createTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_settlement_line (order_id BIGINT NOT NULL, item_id VARCHAR(64) NOT NULL," +
                " kind VARCHAR(12) NOT NULL, bill_month CHAR(7) NOT NULL, supplier_id VARCHAR(40) NOT NULL," +
                " dish_name VARCHAR(100) NOT NULL, unit_cents INT NULL, quantity INT NOT NULL," +
                " PRIMARY KEY(order_id,item_id,kind), INDEX(bill_month,supplier_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    static void record(JdbcTemplate jdbc, JSONObject order) {
        if (!order.getBooleanValue("paid") || !"wechat".equals(order.getString("paymentMode"))) return;
        JSONArray events = order.getJSONArray("events");
        if (events == null) return;
        for (int i=0; i<events.size(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (!"配送方确认送达".equals(event.getString("label"))) continue;
            String deliveredAt = event.getString("at");
            if (deliveredAt == null) {
                // Older events stored only the time of day. The transition audit retains the full date.
                List<java.sql.Timestamp> dates = jdbc.queryForList("SELECT created_at FROM ktv_audit WHERE action='order.transition' AND target=? AND detail LIKE ? ORDER BY id LIMIT 1",
                        java.sql.Timestamp.class, order.getString("id"), "%\"to\":\"completed\"%");
                if (dates.isEmpty()) return;
                deliveredAt = dates.get(0).toLocalDateTime().toString();
            }
            insert(jdbc,order,"delivery",deliveredAt);
            if ("refunded".equals(order.getString("status")) && order.getString("refundedAt") != null)
                insert(jdbc,order,"refund",order.getString("refundedAt"));
            return;
        }
    }

    private static void insert(JdbcTemplate jdbc, JSONObject order, String kind, String at) {
        YearMonth month = YearMonth.from(LocalDateTime.parse(at).atZone(ZoneId.systemDefault()).withZoneSameInstant(SHANGHAI));
        for (Object raw : order.getJSONArray("items")) {
            JSONObject item = (JSONObject)raw;
            BigDecimal price = item.getBigDecimal("settlementPrice");
            jdbc.update("INSERT INTO ktv_settlement_line(order_id,item_id,kind,bill_month,supplier_id,dish_name,unit_cents,quantity)" +
                            " VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE order_id=VALUES(order_id)",
                    order.getLongValue("id"),item.getString("id"),kind,month.toString(),order.getString("supplierId"),
                    item.getString("name"),price == null ? null : price.movePointRight(2).intValueExact(),item.getIntValue("quantity"));
        }
    }

    static JSONObject month(JdbcTemplate jdbc, JSONObject who, String value) {
        YearMonth month;
        try { month = value == null || value.isEmpty() ? YearMonth.now(SHANGHAI) : YearMonth.parse(value); }
        catch (DateTimeParseException e) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"月份格式应为 YYYY-MM"); }
        require(month.getYear()>=2000 && month.getYear()<=2100,400,"月份超出范围");
        List<Object> args = new ArrayList<>();
        args.add(month.toString());
        String scope = "";
        if ("supplier".equals(who.getString("role"))) {
            scope = " AND supplier_id=?";
            args.add(who.getString("subject"));
        }
        String sql = "SELECT supplier_id,item_id,dish_name,unit_cents," +
                " SUM(CASE WHEN kind='delivery' THEN quantity ELSE 0 END) AS delivered," +
                " SUM(CASE WHEN kind='refund' THEN quantity ELSE 0 END) AS refunded" +
                " FROM ktv_settlement_line WHERE bill_month=?" + scope +
                " GROUP BY supplier_id,item_id,dish_name,unit_cents ORDER BY supplier_id,dish_name,item_id,unit_cents";
        Map<String,JSONObject> kitchens = new LinkedHashMap<>();
        for (Map<String,Object> row : jdbc.queryForList(sql,args.toArray())) {
            String supplier = (String)row.get("supplier_id");
            JSONObject kitchen = kitchens.computeIfAbsent(supplier,id -> object("supplierId",id,"items",new JSONArray(),
                    "quantity",0L,"refundQuantity",0L,"unpricedQuantity",0L,"amountCents",0L));
            Number unit = (Number)row.get("unit_cents");
            long delivered = ((Number)row.get("delivered")).longValue();
            long refunded = ((Number)row.get("refunded")).longValue();
            long amount = unit == null ? 0 : delivered * unit.longValue();
            kitchen.put("quantity",kitchen.getLongValue("quantity")+delivered);
            kitchen.put("refundQuantity",kitchen.getLongValue("refundQuantity")+refunded);
            kitchen.put("amountCents",kitchen.getLongValue("amountCents")+amount);
            if (unit == null) kitchen.put("unpricedQuantity",kitchen.getLongValue("unpricedQuantity")+delivered);
            kitchen.getJSONArray("items").add(object("itemId",row.get("item_id"),"name",row.get("dish_name"),"unitCents",unit,
                    "quantity",delivered,"refundQuantity",refunded,"amountCents",unit == null ? null : amount));
        }
        return object("month",month.toString(),"kitchens",kitchens.values());
    }
}
