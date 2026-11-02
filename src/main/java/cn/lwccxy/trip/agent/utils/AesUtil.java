package cn.lwccxy.trip.agent.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** 使用 application.yml 中配置的密钥执行 AES-GCM 加密和解密。 */
@Component
public class AesUtil {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();
    private final String encryptionKey;

    /**
     * 创建 AES 工具并读取应用配置中的密钥。
     *
     * @param encryptionKey AES 密钥材料，不能为空
     */
    public AesUtil(@Value("${corp.trip.api-key-encryption-key:}") String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    /**
     * 加密明文并返回 Base64 编码的 IV 与密文。
     *
     * @param plainText 待加密的明文
     * @return 可保存或传输的 Base64 密文
     * @throws IllegalArgumentException 明文为空白时抛出
     * @throws IllegalStateException 密钥未配置或加密失败时抛出
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException("明文不能为空");
        }

        try {
            // 每次加密生成独立随机 IV，避免相同明文产生相同密文。
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 将 IV 放在密文前，解密时可从同一字符串中还原所需参数。
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES 加密失败", exception);
        }
    }

    /**
     * 解密由本工具生成的 Base64 密文。
     *
     * @param cipherText Base64 编码的 IV 与密文
     * @return 解密后的明文
     * @throws IllegalArgumentException 密文为空或格式无效时抛出
     * @throws IllegalStateException 密钥未配置、认证失败或解密失败时抛出
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            throw new IllegalArgumentException("密文不能为空");
        }

        try {
            byte[] payload = Base64.getDecoder().decode(cipherText);
            if (payload.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("AES 密文格式无效");
            }

            // 解析前置 IV，并将剩余数据作为包含 GCM 认证标签的密文处理。
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES 解密失败或密文校验未通过", exception);
        }
    }

    /**
     * 从配置密钥派生 AES-256 密钥。
     *
     * @return AES-256 密钥
     * @throws IllegalStateException 密钥缺失或派生失败时抛出
     */
    private SecretKeySpec secretKey() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("corp.trip.api-key-encryption-key 未配置");
        }

        try {
            // 固定派生为 256 位 AES 密钥，保证任意长度配置值都映射为合法密钥长度。
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "AES");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES 密钥初始化失败", exception);
        }
    }
}
