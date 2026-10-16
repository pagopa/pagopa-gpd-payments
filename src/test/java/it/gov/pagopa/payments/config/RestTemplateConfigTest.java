package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.xml.MarshallingHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class RestTemplateConfigTest {
    private Map<String, String> previousMdc;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        restTemplate = new RestTemplateConfig().restTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @AfterEach
    void tearDown() {
        if (previousMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    @Test
    void propagatesCurrentRequestIdOnEachCallWithoutScopeOrChangingPayload() {
        assertInstanceOf(MarshallingHttpMessageConverter.class, restTemplate.getMessageConverters().get(0));
        String payload = "<request>unchanged</request>";
        server.expect(requestTo("/first"))
                .andExpect(header("X-Request-Id", "caller-request"))
                .andExpect(content().string(payload))
                .andRespond(withSuccess("first", MediaType.TEXT_PLAIN));
        server.expect(requestTo("/second"))
                .andExpect(header("X-Request-Id", "next-request"))
                .andRespond(withSuccess("second", MediaType.TEXT_PLAIN));

        MDC.put("requestId", "caller-request");
        assertEquals("first", restTemplate.postForObject("/first", payload, String.class));
        MDC.put("requestId", "next-request");
        assertEquals("second", restTemplate.getForObject("/second", String.class));

        server.verify();
        assertEquals(Map.of("requestId", "next-request"), MDC.getCopyOfContextMap());
    }

    @Test
    void doesNotInventRequestIdWithoutMdcOrForEmptyId() {
        server.expect(requestTo("/missing")).andExpect(headerDoesNotExist("X-Request-Id"))
                .andRespond(withSuccess());
        server.expect(requestTo("/empty")).andExpect(headerDoesNotExist("X-Request-Id"))
                .andRespond(withSuccess());
        restTemplate.getForObject("/missing", String.class);
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("operationId"));

        MDC.put("requestId", "");
        restTemplate.getForObject("/empty", String.class);
        assertEquals("", MDC.get("requestId"));
        assertNull(MDC.get("operationId"));
        server.verify();
    }
}
