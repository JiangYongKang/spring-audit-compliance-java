package com.github.highcumontoa.springauditcompliancejava.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.github.highcumontoa.springauditcompliancejava.error.ApiException;
import com.github.highcumontoa.springauditcompliancejava.error.ErrorCode;

/**
 * 键集分页游标的不透明编解码：游标内容为 epochMillis + ":" + rowId，
 * Base64 URL 安全编码。调用方无法猜测/构造，损坏游标按可区分原因拒绝。
 */
@Component
public class CursorCodec {

    public String encode(Instant time, long id) {
        String raw = time.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public Cursor decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(
                    Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            if (sep <= 0) {
                throw new IllegalArgumentException("bad cursor");
            }
            long millis = Long.parseLong(raw.substring(0, sep));
            long id = Long.parseLong(raw.substring(sep + 1));
            if (id < 0) {
                throw new IllegalArgumentException("bad id");
            }
            return new Cursor(Instant.ofEpochMilli(millis), id);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID_CURSOR,
                    "分页游标无法解析");
        }
    }

    public record Cursor(Instant time, long id) {
    }
}
