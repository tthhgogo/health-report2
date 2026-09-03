package com.example.healthreport.support;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 小写十六进制摘要；上传落库与渲染前校验必须用同一实现，避免口径漂移。
 */
public final class Sha256Hex {

    private Sha256Hex() {
    }

    public static String of(byte[] contentBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(contentBytes);
            StringBuilder result = new StringBuilder(hashBytes.length * 2);
            for (byte hashByte : hashBytes) {
                result.append(String.format("%02x", hashByte & 0xFF));
            }
            return result.toString();
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少SHA-256", exception);
        }
    }
}
