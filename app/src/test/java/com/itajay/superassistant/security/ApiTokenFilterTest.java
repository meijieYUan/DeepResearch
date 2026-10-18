package com.itajay.superassistant.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenFilterTest {

    @Test
    void emptyTokenLeavesLocalUsageOpen() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ApiTokenFilter("").doFilter(new MockHttpServletRequest("GET", "/api/todos"),
                response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void configuredTokenRequiresTheBearerHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/todos");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        new ApiTokenFilter("secret-token").doFilter(request, denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(401);

        MockHttpServletRequest authorized = new MockHttpServletRequest("GET", "/api/todos");
        authorized.addHeader("Authorization", "Bearer secret-token");
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        new ApiTokenFilter("secret-token").doFilter(authorized, allowed, new MockFilterChain());
        assertThat(allowed.getStatus()).isEqualTo(200);
    }

    @Test
    void healthProbesStayPublic() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ApiTokenFilter("secret-token").doFilter(
                new MockHttpServletRequest("GET", "/api/health"),
                response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
