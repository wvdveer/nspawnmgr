package com.nspawnmgr.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerApiControllerBrowserOriginTest {

    private static MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }

    @Test
    void directRequestUsesSchemeHostAndPort() {
        assertThat(ContainerApiController.browserOrigin(request("http", "acer.patersonst", 12345)))
                .isEqualTo("http://acer.patersonst:12345");
    }

    @Test
    void tlsTerminatingProxyYieldsThePublicHttpsOrigin() {
        MockHttpServletRequest request = request("http", "nspawnmgr.vanderveer.id", 80);
        request.addHeader("Host", "nspawnmgr.vanderveer.id");
        request.addHeader("X-Forwarded-Proto", "https");

        assertThat(ContainerApiController.browserOrigin(request)).isEqualTo("https://nspawnmgr.vanderveer.id");
    }

    @Test
    void publicNonDefaultPortIsPreservedFromTheHostHeader() {
        MockHttpServletRequest request = request("http", "example.org", 80);
        request.addHeader("Host", "example.org:8443");
        request.addHeader("X-Forwarded-Proto", "https");

        assertThat(ContainerApiController.browserOrigin(request)).isEqualTo("https://example.org:8443");
    }

    @Test
    void firstValueOfAMultiHopForwardedProtoWins() {
        MockHttpServletRequest request = request("http", "h", 80);
        request.addHeader("Host", "h.example");
        request.addHeader("X-Forwarded-Proto", "https, http");

        assertThat(ContainerApiController.browserOrigin(request)).isEqualTo("https://h.example");
    }
}
