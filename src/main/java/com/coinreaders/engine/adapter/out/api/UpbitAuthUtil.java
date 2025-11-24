package com.coinreaders.engine.adapter.out.api;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import lombok.extern.slf4j.Slf4j;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * 업비트 API 인증을 위한 JWT 토큰 생성 유틸리티
 *
 * 업비트 API 인증 방식:
 * 1. Query String을 생성
 * 2. SHA-512로 해싱
 * 3. JWT 토큰 생성 (access key + query hash)
 * 4. Authorization: Bearer {token} 헤더로 전송
 */
@Slf4j
public class UpbitAuthUtil {

    /**
     * 업비트 API 인증용 JWT 토큰 생성 (Query Parameters 없는 경우)
     *
     * @param accessKey 업비트 API Access Key
     * @param secretKey 업비트 API Secret Key
     * @return JWT 토큰 문자열
     */
    public static String generateToken(String accessKey, String secretKey) {
        return generateToken(accessKey, secretKey, null);
    }

    /**
     * 업비트 API 인증용 JWT 토큰 생성 (Query Parameters 있는 경우)
     *
     * @param accessKey 업비트 API Access Key
     * @param secretKey 업비트 API Secret Key
     * @param queryParams Query Parameters (Map 형태)
     * @return JWT 토큰 문자열
     */
    public static String generateToken(String accessKey, String secretKey, Map<String, String> queryParams) {
        Algorithm algorithm = Algorithm.HMAC256(secretKey);
        String jti = UUID.randomUUID().toString();

        // Query Parameters가 없는 경우
        if (queryParams == null || queryParams.isEmpty()) {
            return JWT.create()
                    .withClaim("access_key", accessKey)
                    .withClaim("nonce", jti)
                    .sign(algorithm);
        }

        // Query Parameters가 있는 경우 - Query Hash 생성
        try {
            ArrayList<String> queryElements = new ArrayList<>();
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                queryElements.add(entry.getKey() + "=" + entry.getValue());
            }

            String queryString = String.join("&", queryElements);
            String queryHash = hashSHA512(queryString);

            return JWT.create()
                    .withClaim("access_key", accessKey)
                    .withClaim("nonce", jti)
                    .withClaim("query_hash", queryHash)
                    .withClaim("query_hash_alg", "SHA512")
                    .sign(algorithm);

        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            log.error("JWT 토큰 생성 실패: {}", e.getMessage());
            throw new RuntimeException("JWT 토큰 생성 중 오류 발생", e);
        }
    }

    /**
     * SHA-512 해시 생성
     *
     * @param input 해시할 문자열
     * @return SHA-512 해시값 (16진수 문자열)
     * @throws NoSuchAlgorithmException
     * @throws UnsupportedEncodingException
     */
    private static String hashSHA512(String input) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        md.update(input.getBytes("UTF-8"));

        // 해시값을 16진수 문자열로 변환
        return String.format("%0128x", new BigInteger(1, md.digest()));
    }
}
