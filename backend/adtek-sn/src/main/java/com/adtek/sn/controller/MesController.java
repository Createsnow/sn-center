package com.adtek.sn.controller;

import com.adtek.framework.web.ClientAddress;
import com.adtek.sn.service.MesService;
import com.adtek.sn.vo.MesReplyVO;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MES 对外接口：入口把原系统的 /api/mes/v1/* 改写到 /api/v1/sn/mes/*，应答体与原系统逐字段相同（不套统一信封）。认证在用例内（安全链对本路径放行、 免
 * CSRF）。
 */
@RestController
@RequestMapping("/api/v1/sn/mes")
public class MesController {
    private final MesService service;

    public MesController(MesService service) {
        this.service = service;
    }

    private ResponseEntity<byte[]> call(
            MesService.Op op,
            String auth,
            String encoding,
            byte[] body,
            HttpServletRequest request) {
        MesReplyVO r = service.handle(op, auth, encoding, body, ClientAddress.of(request));
        ResponseEntity.BodyBuilder b =
                ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON);
        if (r.retryAfter() != null) {
            b.header(HttpHeaders.RETRY_AFTER, String.valueOf(r.retryAfter()));
        }
        return b.body(r.body());
    }

    /** ② 取下一批；带 batchNo 时只读取回该批次。 */
    @PostMapping("/batches/next")
    public ResponseEntity<byte[]> next(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = HttpHeaders.CONTENT_ENCODING, required = false) String encoding,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        return call(MesService.Op.NEXT, auth, encoding, body, request);
    }

    /** ③ 提交回执（或整批拒收）：按批次号幂等；不支持 gzip。 */
    @PostMapping("/receipts")
    public ResponseEntity<byte[]> receipts(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = HttpHeaders.CONTENT_ENCODING, required = false) String encoding,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        return call(MesService.Op.RECEIPT, auth, encoding, body, request);
    }

    /** ④ 查询已交付给本组织的号（只读）。 */
    @PostMapping("/sn/delivered")
    public ResponseEntity<byte[]> delivered(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = HttpHeaders.CONTENT_ENCODING, required = false) String encoding,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        return call(MesService.Op.DELIVERED, auth, encoding, body, request);
    }

    /** ⑤ 查询某个批次里仍在该批次中的号（只读）。 */
    @PostMapping("/batches/items")
    public ResponseEntity<byte[]> items(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = HttpHeaders.CONTENT_ENCODING, required = false) String encoding,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        return call(MesService.Op.ITEMS, auth, encoding, body, request);
    }
}
