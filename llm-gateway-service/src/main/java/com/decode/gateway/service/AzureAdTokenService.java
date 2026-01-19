package com.decode.gateway.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class AzureAdTokenService {

    private final RestTemplate restTemplate;

    @Value("${azure.ad.tenant-id}")
    private String tenantId;

    @Value("${azure.ad.client-id}")
    private String clientId;

    @Value("${azure.ad.client-secret}")
    private String clientSecret;

    @Value("${azure.ad.scope:https://graph.microsoft.com/.default}")
    private String scope;

    private String cachedAccessToken;
    private Instant tokenExpiryTime;
    private final ReentrantLock tokenLock = new ReentrantLock();

    /**
     * Get access token from Azure AD using OAuth2 Client Credentials flow.
     * Implements token caching to avoid unnecessary requests.
     */
    public String getAccessToken() {
        tokenLock.lock();
        try {
            // Check if cached token is still valid (with 5-minute buffer)
            if (cachedAccessToken != null && tokenExpiryTime != null 
                    && Instant.now().isBefore(tokenExpiryTime.minusSeconds(300))) {
                log.debug("Using cached Azure AD access token");
                // DEBUG: Print cached bearer token for manual testing
                log.info("=== USING CACHED BEARER TOKEN ===");
                log.info("Bearer Token: {}", cachedAccessToken);
                log.info("==================================");
                return cachedAccessToken;
            }

            // Request new token
            log.info("Requesting new access token from Azure AD");
            String tokenEndpoint = String.format(
                "https://login.microsoftonline.com/%s/oauth2/v2.0/token", 
                tenantId
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);
            body.add("scope", scope);
            body.add("grant_type", "client_credentials");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
                tokenEndpoint,
                request,
                TokenResponse.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                TokenResponse tokenResponse = response.getBody();
                cachedAccessToken = tokenResponse.getAccessToken();
                
                // Calculate expiry time (default to 3600 seconds if expires_in not provided)
                int expiresIn = tokenResponse.getExpiresIn() != null ? tokenResponse.getExpiresIn() : 3600;
                tokenExpiryTime = Instant.now().plusSeconds(expiresIn);
                
                log.info("Successfully obtained Azure AD access token (expires in {} seconds)", expiresIn);
                // DEBUG: Print bearer token for manual testing
                log.info("=== BEARER TOKEN FOR MANUAL TESTING ===");
                log.info("Bearer Token: {}", cachedAccessToken);
                log.info("========================================");
                return cachedAccessToken;
            } else {
                throw new RuntimeException("Failed to obtain Azure AD access token: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Error obtaining Azure AD access token", e);
            throw new RuntimeException("Failed to obtain Azure AD access token", e);
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Clear cached token (useful for testing or forced refresh).
     */
    public void clearCachedToken() {
        tokenLock.lock();
        try {
            cachedAccessToken = null;
            tokenExpiryTime = null;
            log.debug("Cleared cached Azure AD access token");
        } finally {
            tokenLock.unlock();
        }
    }

    @Data
    private static class TokenResponse {
        @JsonProperty("access_token")
        private String accessToken;

        @JsonProperty("token_type")
        private String tokenType;

        @JsonProperty("expires_in")
        private Integer expiresIn;

        @JsonProperty("scope")
        private String scope;
    }
}