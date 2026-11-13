package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import static com.ruoyi.web.controller.ktv.KtvService.*;

@Anonymous
@RestController
@RequestMapping("/ktv-api/notifications")
public class KtvNoticeController {
    private final KtvNoticeService notices;private final KtvAccess access;private final KtvService service;
    public KtvNoticeController(KtvNoticeService notices,KtvAccess access,KtvService service){this.notices=notices;this.access=access;this.service=service;}
    @ModelAttribute public void headers(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @GetMapping("/status") public Object status(HttpServletRequest request){return notices.status(access.who(request));}
    @PostMapping("/bind") public Object bind(@RequestBody JSONObject body,HttpServletRequest request){JSONObject who=access.who(request);service.requireStaff(who);service.limit("notice-bind:"+who.getString("subject"),10,60);return notices.bind(who,text(body,"code",256));}
    @PostMapping("/subscribe") public Object subscribe(@RequestBody JSONObject body,HttpServletRequest request){return notices.subscribe(access.who(request),body);}
    @DeleteMapping("/bind") public Object unbind(HttpServletRequest request){notices.unbind(access.who(request));return object("ok",true);}
    @GetMapping("/admin") public Object admin(HttpServletRequest request){return notices.admin(access.who(request));}
    @GetMapping("/templates") public Object templates(HttpServletRequest request){return notices.templates(access.who(request));}
    @PostMapping("/config") public Object configure(@RequestBody JSONObject body,HttpServletRequest request){notices.configure(access.who(request),body);return object("ok",true);}
    @PostMapping("/{id}/retry") public Object retry(@PathVariable long id,HttpServletRequest request){notices.retry(access.who(request),id);return object("ok",true);}
}
