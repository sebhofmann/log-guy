package de.gbv.logguy.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class TextFunctionsTest {

    @Test
    void findsIpv4AndIpv6() {
        String text = "from 203.0.113.12 via 2001:db8:85a3::8a2e:370:7334, ::1 and ::ffff:10.0.0.1 not 1.2.3.4.5 "
            + "nor 999.1.1.1 nor 12:30:45 nor version 1.2.3";
        assertEquals(List.of("2001:db8:85a3::8a2e:370:7334", "::1", "::ffff:10.0.0.1", "203.0.113.12"),
            TextFunctions.findIps(text));
    }

    @Test
    void replacesIps() {
        assertEquals("ip=<1> and <2>.", TextFunctions.replaceIps("ip=10.1.2.3 and fe80::1.", ip -> ip.contains(":")
            ? "<2>" : "<1>"));
        assertEquals("MCRServlet: 12:00:01", TextFunctions.replaceIps("MCRServlet: 12:00:01", ip -> "X"));
    }

    @Test
    void masksIps() {
        assertEquals("203.0.113.0", TextFunctions.maskIp("203.0.113.12"));
        assertEquals("2001:db8::", TextFunctions.maskIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
        assertEquals("2001:638::", TextFunctions.maskIp("2001:638:1:2::17"));
        assertEquals("::ffff:10.0.0.0", TextFunctions.maskIp("::ffff:10.0.0.1"));
        assertEquals("64:ff9b::192.0.2.0", TextFunctions.maskIp("64:ff9b::192.0.2.33"));
        assertEquals("kein", TextFunctions.maskIp("kein"));
    }

    @Test
    void findsAndReplacesEmails() {
        String text = "mail to anna.mueller@uni-example.de, <bob+x@example.org> not user@localhost";
        assertEquals(List.of("anna.mueller@uni-example.de", "bob+x@example.org"), TextFunctions.findEmails(text));
        assertEquals("mail to M, <M> not user@localhost", TextFunctions.replaceEmails(text, m -> "M"));
    }
}
