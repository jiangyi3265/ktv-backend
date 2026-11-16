package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;
import static com.ruoyi.web.controller.ktv.KtvService.hash;

/** Written in the same transaction as the order, so fast transitions cannot lose notices. */
final class KtvOrderNoticeEvents {
    static void initialize(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_notice_event (id BIGINT AUTO_INCREMENT PRIMARY KEY,event_key VARCHAR(64) NOT NULL UNIQUE,kind VARCHAR(24) NOT NULL,payload LONGTEXT NOT NULL,processed BOOLEAN NOT NULL DEFAULT FALSE,created_at DATETIME NOT NULL,INDEX(processed,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
    static void record(JdbcTemplate jdbc, JSONObject before, JSONObject order) {
        if(!order.getBooleanValue("paid")) return;
        String kind=null;
        if(before==null || !before.getBooleanValue("paid")) kind="paid";
        else if(!Objects.equals(before.getString("status"),order.getString("status"))) {
            switch(order.getString("status")) {
                case "preparing": kind="accepted"; break;
                case "delivering": kind="delivering"; break;
                case "completed": kind="completed"; break;
                case "refunded": kind="refunded"; break;
            }
        }
        if(kind!=null) jdbc.update("INSERT IGNORE INTO ktv_notice_event(event_key,kind,payload,created_at) VALUES(?,?,?,NOW())",hash(order.getString("id")+":"+kind+":"+order.getIntValue("version")),kind,order.toJSONString());
    }
}
