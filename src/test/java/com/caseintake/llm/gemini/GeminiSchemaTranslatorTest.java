package com.caseintake.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GeminiSchemaTranslatorTest {
    private final ObjectMapper om = new ObjectMapper();

    private JsonNode j(String s) throws Exception {
        return om.readTree(s);
    }

    @Test
    void nullableUnionBecomesNullableFlag() throws Exception {
        JsonNode out = GeminiSchemaTranslator.translate(j("{\"type\":[\"string\",\"null\"]}"));
        assertThat(out).isEqualTo(j("{\"type\":\"STRING\",\"nullable\":true}"));
    }

    @Test
    void typesUpperCased() throws Exception {
        JsonNode out = GeminiSchemaTranslator.translate(j("""
            {"$schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":false,
             "required":["a","b"],
             "properties":{"b":{"type":"integer","minimum":1,"maximum":9,"description":"d"},
                           "a":{"type":"array","uniqueItems":true,"items":{"type":"number"}},
                           "c":{"type":"boolean"},
                           "d":{"type":"string","format":"date","pattern":"^x$"}}}"""));
        assertThat(out.get("type").asText()).isEqualTo("OBJECT");
        assertThat(out.get("required")).isEqualTo(j("[\"a\",\"b\"]"));
        assertThat(out.get("propertyOrdering")).isEqualTo(j("[\"b\",\"a\",\"c\",\"d\"]"));
        JsonNode p = out.get("properties");
        assertThat(p.get("b")).isEqualTo(j("{\"type\":\"INTEGER\",\"description\":\"d\"}"));
        assertThat(p.get("a")).isEqualTo(j("{\"type\":\"ARRAY\",\"items\":{\"type\":\"NUMBER\"}}"));
        assertThat(p.get("c")).isEqualTo(j("{\"type\":\"BOOLEAN\"}"));
        assertThat(p.get("d")).isEqualTo(j("{\"type\":\"STRING\"}"));
        assertThat(out.has("$schema")).isFalse();
        assertThat(out.has("additionalProperties")).isFalse();
    }

    @Test
    void enumPreserved() throws Exception {
        assertThat(GeminiSchemaTranslator.translate(j("{\"type\":\"string\",\"enum\":[\"a\",\"b\"]}")))
                .isEqualTo(j("{\"type\":\"STRING\",\"enum\":[\"a\",\"b\"]}"));
        assertThat(GeminiSchemaTranslator.translate(j("{\"enum\":[\"a\",\"b\",null]}")))
                .isEqualTo(j("{\"type\":\"STRING\",\"enum\":[\"a\",\"b\"],\"nullable\":true}"));
        assertThat(GeminiSchemaTranslator.translate(j("{\"enum\":[\"a\"]}")))
                .isEqualTo(j("{\"type\":\"STRING\",\"enum\":[\"a\"]}"));
    }

    @Test
    void unknownKeywordsDroppedAndInputNotMutated() throws Exception {
        JsonNode in = j("{\"type\":\"object\",\"weird\":1,\"properties\":{\"x\":{\"type\":[\"integer\",\"null\"],\"minimum\":0}}}");
        JsonNode copy = in.deepCopy();
        JsonNode out = GeminiSchemaTranslator.translate(in);
        assertThat(in).isEqualTo(copy);
        assertThat(out.has("weird")).isFalse();
        assertThat(out.get("properties").get("x")).isEqualTo(j("{\"type\":\"INTEGER\",\"nullable\":true}"));
    }
}
