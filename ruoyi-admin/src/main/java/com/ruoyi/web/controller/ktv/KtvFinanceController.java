package com.ruoyi.web.controller.ktv;

import com.ruoyi.common.annotation.Anonymous;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import java.nio.charset.StandardCharsets;

@Anonymous
@RestController
@RequestMapping("/ktv-api/finance")
public class KtvFinanceController {
    private final KtvFinanceService finance;
    private final KtvAccess access;
    public KtvFinanceController(KtvFinanceService finance,KtvAccess access){this.finance=finance;this.access=access;}
    @ModelAttribute public void headers(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @GetMapping("/ledger") public Object ledger(@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,@RequestParam(defaultValue="") String kind,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="1") int page,HttpServletRequest request){return finance.ledger(access.who(request),from,to,kind,search,page);}
    @GetMapping("/daily") public Object daily(@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,HttpServletRequest request){return finance.daily(access.who(request),from,to);}
    @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,@RequestParam(defaultValue="") String kind,@RequestParam(defaultValue="") String search,HttpServletRequest request){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=ktv-ledger.csv").contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(finance.export(access.who(request),from,to,kind,search).getBytes(StandardCharsets.UTF_8));}
}
