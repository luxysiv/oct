package com.termux.app.opencode;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OpenCodeHashTest {

    @Test
    public void sha512Base64MatchesKnownVector() throws Exception {
        byte[] data = "abc".getBytes("UTF-8");
        assertEquals(
            "3a81oZNherrMQXNJriBBMRLm+k6JqX6iCp7u5ktV05ohkpkqJ0/BqDa6PCOj/uu9RU1EI2Q86A4qmslPpUyknw==",
            OpenCodeHash.sha512Base64(new java.io.ByteArrayInputStream(data)));
    }

    @Test
    public void base64PadsCorrectly() {
        assertEquals("", OpenCodeHash.base64(new byte[0]));
        assertEquals("YQ==", OpenCodeHash.base64("a".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("YWI=", OpenCodeHash.base64("ab".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("YWJj", OpenCodeHash.base64("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
