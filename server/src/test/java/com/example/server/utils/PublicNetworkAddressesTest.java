package com.example.server.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class PublicNetworkAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "0.1.2.3", "198.18.0.1", "240.0.0.1", "224.0.0.1",
            "::1", "fe80::1", "fd00::1", "::ffff:127.0.0.1",
            "::", "0.0.0.0", "ff02::1", "198.19.255.255", "::127.0.0.1", "64:ff9b::7f00:1", "64:ff9b::a00:1"})
    void rejectsLoopbackPrivateAndReservedAddresses(String ip) throws Exception {
        assertTrue(PublicNetworkAddresses.isDisallowed(InetAddress.getByName(ip)), ip);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "93.184.215.14", "2606:4700:4700::1111",
            "100.63.255.255", "100.128.0.0", "198.17.255.255", "198.20.0.0", "172.15.0.1", "172.32.0.1",
            "64:ff9b::808:808"})
    void acceptsPublicAddresses(String ip) throws Exception {
        assertFalse(PublicNetworkAddresses.isDisallowed(InetAddress.getByName(ip)), ip);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd", "ftp://8.8.8.8/v.mp4", "http://127.0.0.1:8080/v.mp4",
            "https://[::1]/v.mp4", "https://169.254.169.254/latest", "not a url"})
    void rejectsNonHttpOrNonPublicUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> PublicNetworkAddresses.requirePublicHttpUrl(url));
    }

    @Test
    void acceptsPublicHttpsLiteral() {
        assertDoesNotThrow(() -> PublicNetworkAddresses.requirePublicHttpUrl("https://8.8.8.8/v.mp4"));
    }

    @Test
    void rejectsNullAndBlankUrls() {
        assertThrows(IllegalArgumentException.class, () -> PublicNetworkAddresses.requirePublicHttpUrl(null));
        assertThrows(IllegalArgumentException.class, () -> PublicNetworkAddresses.requirePublicHttpUrl("  "));
    }

    @Test
    void unresolvableHostIsBadInputNotUnknownHostException() {
        assertThrows(IllegalArgumentException.class,
                () -> PublicNetworkAddresses.requirePublicHttpUrl("https://does-not-exist.invalid/v.mp4"));
    }
}
