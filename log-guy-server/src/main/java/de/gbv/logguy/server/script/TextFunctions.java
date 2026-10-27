package de.gbv.logguy.server.script;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and replaces IP and mail addresses in text. Offered to scripts as built-in functions, because a regular
 * expression in a script recognizes IPv6 addresses neither reliably nor fast.
 */
public final class TextFunctions {

    private static final Pattern IPV4 = Pattern.compile(
        "(?<![\\w.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?![\\w]|\\.\\d)");

    /** Candidates for IPv6, checked with {@link #isIpv6(String)}. An embedded IPv4 address may end the address. */
    private static final Pattern IPV6_CANDIDATE = Pattern.compile(
        "(?<![\\w:.])(?:[0-9A-Fa-f]{0,4}:){2,7}(?:[0-9A-Fa-f]{1,4}|(?:\\d{1,3}\\.){3}\\d{1,3})?(?![\\w:]|\\.\\w)");

    private static final Pattern EMAIL = Pattern.compile(
        "(?<![\\w.%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*"
            + "[A-Za-z0-9])?)*\\.[A-Za-z]{2,}(?![\\w])");

    private static final String MARK_START = "\uE000";

    private static final String MARK_END = "\uE001";

    private static final Pattern MARK = Pattern.compile("\uE000\\d+\uE001");

    private TextFunctions() {
    }

    /**
     * Replaces every IPv4 and IPv6 address.
     *
     * @param text the text, may be {@code null}
     * @param replacement maps an address to its replacement
     * @return the new text
     */
    public static String replaceIps(String text, UnaryOperator<String> replacement) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // IPv6 addresses become placeholders first, so the IPv4 pass neither sees embedded IPv4 parts nor what the
        // replacement returned.
        List<String> v6 = new ArrayList<>();
        String marked = replaceIpv6(text, ip -> {
            v6.add(replacement.apply(ip));
            return MARK_START + (v6.size() - 1) + MARK_END;
        });
        String result = replace(IPV4, marked, replacement);
        return v6.isEmpty() ? result : replace(MARK, result, mark -> v6.get(Integer.parseInt(mark.substring(1,
            mark.length() - 1))));
    }

    /**
     * Finds every IPv4 and IPv6 address.
     *
     * @param text the text, may be {@code null}
     * @return the addresses in order of appearance, IPv6 first
     */
    public static List<String> findIps(String text) {
        List<String> found = new ArrayList<>();
        replaceIps(text, ip -> {
            found.add(ip);
            return ip;
        });
        return found;
    }

    /**
     * Shortens every IP address: IPv4 to the first three octets with a zero, IPv6 to the first 32 bits, the block of a
     * provider. An IPv4 address embedded in IPv6 is shortened like IPv4.
     *
     * @param text the text, may be {@code null}
     * @return the new text
     */
    public static String maskIp(String text) {
        return replaceIps(text, TextFunctions::mask);
    }

    /**
     * Replaces every mail address.
     *
     * @param text the text, may be {@code null}
     * @param replacement maps an address to its replacement
     * @return the new text
     */
    public static String replaceEmails(String text, UnaryOperator<String> replacement) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return replace(EMAIL, text, replacement);
    }

    /**
     * Finds every mail address.
     *
     * @param text the text, may be {@code null}
     * @return the addresses in order of appearance
     */
    public static List<String> findEmails(String text) {
        List<String> found = new ArrayList<>();
        if (text != null) {
            Matcher matcher = EMAIL.matcher(text);
            while (matcher.find()) {
                found.add(matcher.group());
            }
        }
        return found;
    }

    private static String replace(Pattern pattern, String text, UnaryOperator<String> replacement) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        do {
            out.append(text, last, matcher.start()).append(replacement.apply(matcher.group()));
            last = matcher.end();
        } while (matcher.find());
        return out.append(text, last, text.length()).toString();
    }

    private static String replaceIpv6(String text, UnaryOperator<String> replacement) {
        if (text.indexOf(':') < 0) {
            return text;
        }
        Matcher matcher = IPV6_CANDIDATE.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!isIpv6(candidate)) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, last, matcher.start()).append(replacement.apply(candidate));
            last = matcher.end();
        }
        return out == null ? text : out.append(text, last, text.length()).toString();
    }

    /**
     * Checks the syntax of an IPv6 address, optionally with an embedded IPv4 address at the end.
     *
     * @param candidate the text
     * @return true for an IPv6 address
     */
    static boolean isIpv6(String candidate) {
        return expand(candidate) != null;
    }

    /**
     * Expands an IPv6 address into its eight 16 bit words.
     *
     * @param candidate the text
     * @return the words, {@code null} if the text is no IPv6 address
     */
    private static int[] expand(String candidate) {
        String address = candidate;
        List<Integer> ipv4 = new ArrayList<>();
        if (address.indexOf('.') >= 0) {
            int colon = address.lastIndexOf(':');
            String last = address.substring(colon + 1);
            if (!IPV4.matcher(last).matches()) {
                return null;
            }
            String[] octets = last.split("\\.");
            ipv4.add(Integer.parseInt(octets[0]) << 8 | Integer.parseInt(octets[1]));
            ipv4.add(Integer.parseInt(octets[2]) << 8 | Integer.parseInt(octets[3]));
            address = address.substring(0, colon + 1);
            if (!address.endsWith("::")) {
                address = address.substring(0, address.length() - 1);
            }
        }
        int gap = address.indexOf("::");
        if (gap >= 0 && (gap != address.lastIndexOf("::") || address.contains(":::"))) {
            return null;
        }
        List<Integer> head = words(gap < 0 ? address : address.substring(0, gap));
        List<Integer> tail = gap < 0 ? new ArrayList<>() : words(address.substring(gap + 2));
        if (head == null || tail == null) {
            return null;
        }
        tail.addAll(ipv4);
        int count = head.size() + tail.size();
        if (count == 0 || (gap < 0 ? count != 8 : count > 7)) {
            return null;
        }
        int[] result = new int[8];
        for (int i = 0; i < head.size(); i++) {
            result[i] = head.get(i);
        }
        for (int i = 0; i < tail.size(); i++) {
            result[8 - tail.size() + i] = tail.get(i);
        }
        return result;
    }

    private static List<Integer> words(String part) {
        List<Integer> words = new ArrayList<>();
        if (part.isEmpty()) {
            return words;
        }
        for (String word : part.split(":", -1)) {
            if (word.isEmpty() || word.length() > 4) {
                return null;
            }
            words.add(Integer.parseInt(word, 16));
        }
        return words;
    }

    private static String mask(String ip) {
        if (ip.indexOf('.') >= 0) {
            return ip.substring(0, ip.lastIndexOf('.')) + ".0";
        }
        int[] words = expand(ip);
        return Integer.toHexString(words[0]) + ":" + Integer.toHexString(words[1]) + "::";
    }
}
