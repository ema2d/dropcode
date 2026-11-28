package com.dropcode.backend.security;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class ClientIpResolver {

    static final String CF_CONNECTING_IP = "CF-Connecting-IP";

    private final boolean trustCloudflareHeader;

    ClientIpResolver(AbuseProperties props) {
        this.trustCloudflareHeader = props.trustCloudflareHeader();
    }

    public String resolve(HttpServletRequest request) {
        if (trustCloudflareHeader) {
            String header = request.getHeader(CF_CONNECTING_IP);
            if (header != null && !header.isBlank() && header.length() <= 45) {
                return header.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
