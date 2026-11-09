package com.ruoyi.web.controller.ktv;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KtvMiniCodeTest {
    private void verifyVersion(String version,boolean checkPath) {
        KtvWechat gateway=new KtvWechat();
        ReflectionTestUtils.setField(gateway,"appId","wx-test");
        ReflectionTestUtils.setField(gateway,"appSecret","test-secret");
        ReflectionTestUtils.setField(gateway,"accessToken","test-access");
        ReflectionTestUtils.setField(gateway,"tokenExpires",Long.MAX_VALUE);
        ReflectionTestUtils.setField(gateway,"envVersion",version);
        ReflectionTestUtils.setField(gateway,"codePage","pages/index/index");
        MockRestServiceServer http=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(gateway,"http")).build();
        byte[] image={(byte)0x89,0x50,0x4e,0x47,13,10,26,10,0};
        http.expect(requestTo("https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=test-access"))
            .andExpect(jsonPath("$.env_version").value(version))
            .andExpect(jsonPath("$.check_path").value(checkPath))
            .andExpect(jsonPath("$.page").value("pages/index/index"))
            .andExpect(jsonPath("$.scene").value("room-scene"))
            .andRespond(withSuccess(image,MediaType.IMAGE_PNG));
        assertArrayEquals(image,gateway.miniCode("room-scene"));http.verify();
    }
    @Test void trialCodeDoesNotRequireAnAlreadyPublishedPage(){verifyVersion("trial",false);}
    @Test void releaseCodeStillValidatesPublishedPage(){verifyVersion("release",true);}
    @Test void rejectsInvalidEnvironmentBeforeCallingWechat(){KtvWechat gateway=new KtvWechat();ReflectionTestUtils.setField(gateway,"envVersion","invalid");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->gateway.miniCode("scene"));}
}
