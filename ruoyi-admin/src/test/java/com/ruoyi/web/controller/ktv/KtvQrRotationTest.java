package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static com.ruoyi.web.controller.ktv.KtvService.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KtvQrRotationTest {
    private KtvService service;
    private KtvWechat wechat;
    private KtvOperationsController controller;
    private final JSONObject admin = object("role", "admin", "subject", "ktv-admin");
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private String original;
    private final byte[] image = {(byte)0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0};

    @BeforeEach void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        service = new KtvService(new JdbcTemplate(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)));
        ReflectionTestUtils.setField(service, "adminPassword", "Fixture-qr-admin-2026!");
        ReflectionTestUtils.setField(service, "supplierPassword", "Fixture-qr-worker-2026!");
        service.initialize();
        original = service.roomCode(admin, "A08", false).getString("scene");
        wechat = mock(KtvWechat.class);
        KtvAccess access = mock(KtvAccess.class); when(access.who(any())).thenReturn(admin);
        controller = new KtvOperationsController(service, wechat, access);
        ReflectionTestUtils.setField(controller, "customerUrl", "http://127.0.0.1:5189/");
    }

    @Test void failedWechatRenderingKeepsPrintedCodeUsable() {
        when(wechat.miniCode(anyString())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "测试微信暂不可用"));
        assertThrows(ResponseStatusException.class, () -> controller.qr("A08", true, "mini", request));
        assertEquals("A08", service.resolveRoom(original).getString("room"));
        assertEquals(original, service.roomCode(admin, "A08", false).getString("scene"));
    }

    @Test void invalidTypeDoesNotRotateTheExistingCode() {
        assertThrows(ResponseStatusException.class, () -> controller.qr("A08", true, "invalid", request));
        assertEquals("A08", service.resolveRoom(original).getString("room"));
        verifyNoInteractions(wechat);
    }

    @Test void successfulImageActivatesOnlyItsCorrespondingScene() throws Exception {
        when(wechat.miniCode(anyString())).thenAnswer(invocation -> {
            assertEquals("A08", service.resolveRoom(original).getString("room"));
            return image;
        });
        JSONObject result = (JSONObject) controller.qr("A08", true, "mini", request);
        assertNotEquals(original, result.getString("scene"));
        verify(wechat).miniCode(result.getString("scene"));
        assertEquals("A08", service.resolveRoom(result.getString("scene")).getString("room"));
        assertThrows(ResponseStatusException.class, () -> service.resolveRoom(original));
    }

    @Test void laterCompletedRenderingCannotReplaceAnotherRotation() {
        String[] winningScene = {null};
        when(wechat.miniCode(anyString())).thenAnswer(invocation -> {
            winningScene[0] = service.roomCode(admin, "A08", true).getString("scene");
            return image;
        });
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () -> controller.qr("A08", true, "mini", request));
        assertEquals(409, conflict.getRawStatusCode());
        assertEquals(winningScene[0], service.roomCode(admin, "A08", false).getString("scene"));
        assertEquals("A08", service.resolveRoom(winningScene[0]).getString("room"));
    }

    @Test void roomDisabledDuringRenderingCannotReturnAnActivatedCode() {
        when(wechat.miniCode(anyString())).thenAnswer(invocation -> {
            JSONObject room = service.snapshot(admin).getJSONArray("rooms").getJSONObject(0);
            room.put("bound", false); service.room(admin, "A08", room); return image;
        });
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () -> controller.qr("A08", true, "mini", request));
        assertEquals(409, conflict.getRawStatusCode());
    }
}
