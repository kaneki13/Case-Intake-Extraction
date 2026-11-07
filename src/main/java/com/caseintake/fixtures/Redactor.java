package com.caseintake.fixtures;

import com.caseintake.llm.LlmCredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Removes the model credential from recorded text: every literal occurrence of the secret, any {@code key=} query
 * parameter, and, in JSON, any field named by one of the credential's header names (case-insensitive) or a
 * {@code key} field holding a plain value.
 */
public final class Redactor {
    public static final String REDACTED = "REDACTED";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern QUERY_KEY = Pattern.compile("([?&]key=)[^&\"\\s\\\\]*");

    private Redactor() {}

    public static String redact(String json, LlmCredential credential) {
        if (json == null) {
            return null;
        }
        String out = json;
        if (credential.isPresent()) {
            out = out.replace(credential.secret(), REDACTED);
        }
        out = QUERY_KEY.matcher(out).replaceAll("$1" + REDACTED);
        Set<String> headers = credential.headerNames().stream()
                .map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        try {
            JsonNode tree = MAPPER.readTree(out);
            if (tree != null && (tree.isObject() || tree.isArray())) {
                scrub(tree, headers);
                out = MAPPER.writeValueAsString(tree);
            }
        } catch (Exception notJson) {
            // plain text: literal and query-parameter redaction above is all that applies
        }
        return out;
    }

    private static void scrub(JsonNode node, Set<String> headers) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = e.getKey();
                if (headers.contains(name.toLowerCase(Locale.ROOT))
                        || (name.equalsIgnoreCase("key") && e.getValue().isValueNode())) {
                    it.remove();
                } else {
                    scrub(e.getValue(), headers);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> scrub(child, headers));
        }
    }
}
