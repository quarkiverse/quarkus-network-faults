package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RequestScopeTest {

    private static boolean covers(RequestScope scope, String method, String target) {
        return scope.covers(new HttpExchange(method, target));
    }

    @Test
    void aPathMatchesTheEndOfTheRequestPathOnASegmentBoundary() {
        RequestScope reserve = RequestScope.ALL.and("POST", "/inventory/reserve");
        assertTrue(covers(reserve, "POST", "/inventory/reserve"));
        assertTrue(covers(reserve, "POST", "/api/v2/inventory/reserve"));
        assertTrue(covers(reserve, "POST", "/inventory/reserve/"));
        assertTrue(covers(reserve, "POST", "/inventory/reserve?item=widget"));
        assertFalse(covers(reserve, "POST", "/myinventory/reserve"));
        assertFalse(covers(reserve, "POST", "/inventory/reserved"));
        assertFalse(covers(reserve, "POST", "/inventory/reserve/all"));
        assertFalse(covers(reserve, "GET", "/inventory/reserve"));
    }

    @Test
    void theMethodIsComparedWhateverItsCaseAndThePathMayLackItsLeadingSlash() {
        RequestScope reserve = RequestScope.ALL.and("post", "inventory/reserve/");
        assertTrue(covers(reserve, "POST", "/inventory/reserve"));
        assertEquals("POST /inventory/reserve/", reserve.toString());
    }

    @Test
    void aParameterIsOneSegmentOrWhatItsRegularExpressionMatches() {
        RequestScope item = RequestScope.ALL.and("GET", "/items/{id}");
        assertTrue(covers(item, "GET", "/items/42"));
        assertFalse(covers(item, "GET", "/items/42/stock"));
        assertFalse(covers(item, "GET", "/items/"));

        // the expression has braces of its own
        RequestScope numbered = RequestScope.ALL.and("GET", "/items/{id: [0-9]{2}}/stock");
        assertTrue(covers(numbered, "GET", "/items/42/stock"));
        assertFalse(covers(numbered, "GET", "/items/421/stock"));
        assertFalse(covers(numbered, "GET", "/items/ab/stock"));

        // a parameter in the middle of a segment, and characters a regular expression would read
        RequestScope versioned = RequestScope.ALL.and("GET", "/v{n}.json");
        assertTrue(covers(versioned, "GET", "/v2.json"));
        assertFalse(covers(versioned, "GET", "/v2xjson"));
    }

    @Test
    void anotherSelectionNarrowsTheScope() {
        RequestScope both = RequestScope.ALL.and("POST", "/inventory/{action}").and("POST", "/reserve");
        assertTrue(covers(both, "POST", "/inventory/reserve"));
        assertFalse(covers(both, "POST", "/inventory/release"));
        assertEquals("POST /inventory/{action} and POST /reserve", both.toString());
    }

    @Test
    void anUnclosedParameterIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> RequestScope.ALL.and("GET", "/items/{id"));
        assertThrows(IllegalArgumentException.class, () -> RequestScope.ALL.and(" ", "/items"));
    }

    @Test
    void allCoversEveryRequestAndWhatIsNotOne() {
        assertTrue(covers(RequestScope.ALL, "DELETE", "/anything?at=all"));
        assertTrue(RequestScope.ALL.covers(null));
        assertFalse(RequestScope.ALL.and("GET", "/").covers(null));
    }
}
