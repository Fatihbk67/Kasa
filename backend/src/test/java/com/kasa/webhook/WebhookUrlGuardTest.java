package com.kasa.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookUrlGuardTest {

    private final WebhookUrlGuard guard = new WebhookUrlGuard(false, InetAddress::getAllByName);

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hook",
            "https://localhost/hook",
            "https://0.0.0.0/hook",
            "https://10.0.0.5/hook",
            "https://172.16.0.1/hook",
            "https://192.168.1.1/hook",
            "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/hook",
            "https://[::1]/hook",
            "https://[fc00::1]/hook",
            "https://[fe80::1]/hook",
            "https://[::ffff:127.0.0.1]/hook"
    })
    void blocksInternalAddresses(String url) {
        assertThatThrownBy(() -> guard.check(url)).isInstanceOf(WebhookUrlGuard.UnsafeUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://93.184.216.34/hook",
            "ftp://93.184.216.34/hook",
            "file:///etc/passwd",
            "https://user:pass@93.184.216.34/hook",
            "not a url",
            "/relative/path"
    })
    void blocksUnsafeOrMalformedUrls(String url) {
        assertThatThrownBy(() -> guard.check(url)).isInstanceOf(WebhookUrlGuard.UnsafeUrlException.class);
    }

    @Test
    void allowsAPublicHttpsAddress() {
        assertThat(guard.check("https://93.184.216.34/hook").getHost()).isEqualTo("93.184.216.34");
    }

    @Test
    void blocksAHostnameThatResolvesToAnInternalAddress() throws Exception {
        InetAddress internal = InetAddress.getByAddress("evil.example.com", new byte[] {10, 0, 0, 1});
        InetAddress external = InetAddress.getByAddress("evil.example.com", new byte[] {93, (byte) 184, (byte) 216, 34});
        WebhookUrlGuard rebinding = new WebhookUrlGuard(false, host -> new InetAddress[] {external, internal});

        assertThatThrownBy(() -> rebinding.check("https://evil.example.com/hook"))
                .isInstanceOf(WebhookUrlGuard.UnsafeUrlException.class);
    }

    @Test
    void blocksAHostnameThatDoesNotResolve() {
        WebhookUrlGuard unresolved = new WebhookUrlGuard(false, host -> {
            throw new UnknownHostException(host);
        });

        assertThatThrownBy(() -> unresolved.check("https://missing.example.com/hook"))
                .isInstanceOf(WebhookUrlGuard.UnsafeUrlException.class);
    }

    @Test
    void localDevelopmentModePermitsLocalhostButStillRejectsCredentials() {
        WebhookUrlGuard insecure = new WebhookUrlGuard(true, InetAddress::getAllByName);

        assertThat(insecure.check("http://localhost:8080/hook").getPort()).isEqualTo(8080);
        assertThatThrownBy(() -> insecure.check("http://user:pass@localhost/hook"))
                .isInstanceOf(WebhookUrlGuard.UnsafeUrlException.class);
    }
}
