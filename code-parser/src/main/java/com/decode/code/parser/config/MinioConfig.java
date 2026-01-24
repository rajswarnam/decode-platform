package com.decode.code.parser.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

@Configuration
public class MinioConfig {

    @Value("${minio.endpoint:http://minio:9000}")
    private String endpoint;

    @Value("${minio.access-key:minioadmin}")
    private String accessKey;

    @Value("${minio.secret-key:minioadmin}")
    private String secretKey;
    
    @Value("${parser.http.connect-timeout:30000}")
    private int connectTimeout;
    
    @Value("${parser.http.read-timeout:60000}")
    private int readTimeout;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
    
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout); // 30 seconds
        factory.setReadTimeout(readTimeout); // 60 seconds
        return new RestTemplate(factory);
    }
}
