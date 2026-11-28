package com.dropcode.backend.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dropcode.backend.TestcontainersConfiguration;
import com.dropcode.backend.storage.PresignedUpload;
import com.dropcode.backend.storage.StorageService;
import com.jayway.jsonpath.JsonPath;

/** Rate limits, captcha, password limit, CORS and security headers — through the real HTTP stack. */
@SpringBootTest(properties = {
        "dropcode.abuse.creates-per-hour=2",
        "dropcode.abuse.lookups-per-minute=6",
        "dropcode.abuse.wrong-passwords-per15-minutes=2"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AbuseProtectionTest {

    private static final String UNPROTECTED = """
            {"size":1234,"protected":false,"encMeta":"bWV0YQ==","fileKey":"a2V5"}""";
    private static final String PROTECTED = """
            {"size":1234,"downloads":10,"protected":true,"encMeta":"bWV0YQ==",
             "authToken":"Z29vZA==","salt":"c2FsdA=="}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoBean
    private StorageService storage;

    @MockitoBean
    private TurnstileVerifier turnstile;

    @BeforeEach
    void setUp() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
        when(storage.presignUpload(anyString(), anyLong(), any()))
                .thenReturn(new PresignedUpload(URI.create("https://r2.example/put"), Map.of()));
        when(storage.presignDownload(anyString(), any())).thenReturn(URI.create("https://r2.example/get"));
        when(storage.objectSize(anyString())).thenReturn(Optional.of(1234L));
        when(turnstile.verify(eq("good"), anyString())).thenReturn(true);
    }

    @Test
    void createsAreLimitedPerIp() throws Exception {
        create(UNPROTECTED, "10.0.0.1");
        create(UNPROTECTED, "10.0.0.1");

        mvc.perform(jsonPost("/api/v1/shares", UNPROTECTED).with(ip("10.0.0.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", notNullValue()))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // Another IP is not affected
        create(UNPROTECTED, "10.0.0.2");
    }

    @Test
    void lookupsAreLimitedPerMinute() throws Exception {
        String code = readyShare(UNPROTECTED, "10.0.1.1");

        for (int i = 0; i < 6; i++) {
            mvc.perform(get("/api/v1/shares/{code}", code).with(ip("10.0.1.2"))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/shares/{code}", code).with(ip("10.0.1.2")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void captchaIsRequiredAfterThreeUnknownCodes() throws Exception {
        String ip = "10.0.2.1";
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/shares/ZZZZZ").with(ip(ip))).andExpect(status().isNotFound());
        }

        mvc.perform(get("/api/v1/shares/ZZZZZ").with(ip(ip)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPTCHA_REQUIRED"));
        mvc.perform(get("/api/v1/shares/ZZZZZ").with(ip(ip)).header("X-Turnstile-Token", "bad"))
                .andExpect(status().isForbidden());

        // Solved: the request goes through (the code is still unknown, so 404)
        mvc.perform(get("/api/v1/shares/ZZZZZ").with(ip(ip)).header("X-Turnstile-Token", "good"))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongPasswordsBlockTheIp_butNeverLockTheShareForOthers() throws Exception {
        String code = readyShare(PROTECTED, "10.0.3.1");
        String attacker = "10.0.3.2";

        for (int i = 0; i < 2; i++) {
            mvc.perform(jsonPost("/api/v1/shares/" + code + "/download", "{\"authToken\":\"YmFk\"}").with(ip(attacker)))
                    .andExpect(status().isUnauthorized());
        }
        // This IP must wait now, even with the right password
        mvc.perform(jsonPost("/api/v1/shares/" + code + "/download", "{\"authToken\":\"Z29vZA==\"}").with(ip(attacker)))
                .andExpect(status().isTooManyRequests());

        // Everyone else can still download
        mvc.perform(jsonPost("/api/v1/shares/" + code + "/download", "{\"authToken\":\"Z29vZA==\"}").with(ip("10.0.3.3")))
                .andExpect(status().isOk());
    }

    @Test
    void securityHeadersAreSet() throws Exception {
        mvc.perform(get("/api/v1/shares/ZZZZZ").secure(true))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }

    @Test
    void corsAllowsOnlyTheFrontendOrigin() throws Exception {
        mvc.perform(options("/api/v1/shares")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        mvc.perform(options("/api/v1/shares")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void everythingOutsideTheApiIsDenied() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        // OpenAPI spec is public (used to generate frontend types)
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/shares']").exists());
    }

    private String readyShare(String json, String ip) throws Exception {
        String code = create(json, ip);
        mvc.perform(post("/api/v1/shares/{code}/complete", code).with(ip(ip))).andExpect(status().isOk());
        return code;
    }

    private String create(String json, String ip) throws Exception {
        String body = mvc.perform(jsonPost("/api/v1/shares", json).with(ip(ip)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    private static MockHttpServletRequestBuilder jsonPost(String url, String json) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
