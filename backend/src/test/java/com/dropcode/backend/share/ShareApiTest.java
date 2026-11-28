package com.dropcode.backend.share;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.web.servlet.MvcResult;

import com.dropcode.backend.TestcontainersConfiguration;
import com.dropcode.backend.storage.PresignedUpload;
import com.dropcode.backend.storage.StorageService;
import com.jayway.jsonpath.JsonPath;

/**
 * Full HTTP flow through the real controller, service, Argon2 and Redis (Testcontainers).
 * Only R2 is mocked — its real behaviour is covered by R2StorageServiceIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShareApiTest {

    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoBean
    private StorageService storage;

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
    }

    @Test
    void unprotectedShare_fullLifecycle() throws Exception {
        String code = create("""
                {"size":1234,"downloads":1,"expiry":"1h","protected":false,
                 "encMeta":"ZW5jTWV0YQ==","fileKey":"ZmlsZUtleQ=="}""");

        // Not visible before the upload is completed
        mvc.perform(get("/api/v1/shares/{code}", code)).andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/shares/{code}/complete", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadsLeft").value(1))
                // ISO-8601 string, not a number
                .andExpect(jsonPath("$.expiresAt").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*Z")));

        // Lowercase input is normalized
        mvc.perform(get("/api/v1/shares/{code}", code.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protected").value(false))
                .andExpect(jsonPath("$.size").value(1234));

        mvc.perform(post("/api/v1/shares/{code}/download", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://r2.example/get"))
                .andExpect(jsonPath("$.fileKey").value("ZmlsZUtleQ=="))
                .andExpect(jsonPath("$.encMeta").value("ZW5jTWV0YQ=="))
                .andExpect(jsonPath("$.downloadsLeft").value(0));

        // One download only: the code is gone now
        mvc.perform(post("/api/v1/shares/{code}/download", code))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void protectedShare_needsCorrectToken() throws Exception {
        String code = create("""
                {"size":1234,"downloads":3,"protected":true,"encMeta":"bWV0YQ==",
                 "authToken":"Y29ycmVjdC10b2tlbg==","salt":"c2FsdA=="}""");
        mvc.perform(post("/api/v1/shares/{code}/complete", code)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/shares/{code}", code))
                .andExpect(jsonPath("$.protected").value(true))
                .andExpect(jsonPath("$.salt").value("c2FsdA=="));

        mvc.perform(post("/api/v1/shares/{code}/download", code))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
        mvc.perform(post("/api/v1/shares/{code}/download", code)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"authToken\":\"d3Jvbmc=\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/shares/{code}/download", code)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"authToken\":\"Y29ycmVjdC10b2tlbg==\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadsLeft").value(2))
                .andExpect(jsonPath("$.fileKey").doesNotExist());

        // The plain token is never stored, only its Argon2id hash
        String authHash = (String) redis.opsForHash().get(ShareRepository.key(code), "authHash");
        org.assertj.core.api.Assertions.assertThat(authHash).startsWith("$argon2id$")
                .doesNotContain("Y29ycmVjdC10b2tlbg==");
    }

    @Test
    void completeBeforeUpload_isConflict() throws Exception {
        when(storage.objectSize(anyString())).thenReturn(Optional.empty());
        String code = create("""
                {"size":1234,"protected":false,"encMeta":"bWV0YQ==","fileKey":"a2V5"}""");

        mvc.perform(post("/api/v1/shares/{code}/complete", code))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void invalidRequests_returnProblemDetail() throws Exception {
        // too big (30 MB + 1)
        mvc.perform(post("/api/v1/shares").contentType(MediaType.APPLICATION_JSON).content("""
                {"size":31457281,"protected":false,"encMeta":"bWV0YQ==","fileKey":"a2V5"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
        // not an allowed download count
        mvc.perform(post("/api/v1/shares").contentType(MediaType.APPLICATION_JSON).content("""
                {"size":10,"downloads":7,"protected":false,"encMeta":"bWV0YQ==","fileKey":"a2V5"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("downloads must be one of 1, 3, 5, 10"));
        // malformed code
        mvc.perform(get("/api/v1/shares/{code}", "not-a-code"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Share not found"));
    }

    private String create(String json) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/shares").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.upload.url").value(startsWith("https://r2.example/")))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }
}
