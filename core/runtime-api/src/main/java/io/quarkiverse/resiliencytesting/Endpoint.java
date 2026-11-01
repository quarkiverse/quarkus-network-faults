package io.quarkiverse.resiliencytesting;

import java.time.Duration;

/**
 * What a fault acts on: everything that goes through a {@link Target}, or only the requests an endpoint covers, such
 * as the calls to one method of a REST Client, {@code faults.target(InventoryClient::reserve)}, or the requests
 * {@link Http#requests(String, String)} selects. Faults end when the returned {@link ActiveFault} is closed, or at the
 * end of the test.
 * <p>
 * A fault applied to an endpoint that is not a whole target acts on each request it covers and on that request's
 * response, and relays every other request untouched, even on the same connection. Telling requests apart needs a
 * target reached over plaintext HTTP/1.x: applying such a fault to any other target throws
 * {@link UnsupportedOperationException}.
 * <p>
 * Faults on different endpoints of one target are active together, each acting on its own requests: a call can hang
 * while another fails. A fault on a whole target has it to itself, and two faults on exactly the same requests are
 * refused: applying one then throws {@link IllegalStateException}. Where the requests of two endpoints overlap, the
 * fault applied first acts on the requests they share.
 */
public interface Endpoint {

    /**
     * Requests are held: nothing more of them is relayed, and they stay unanswered until the client's read timeout.
     * What was held follows when the fault ends.
     */
    ActiveFault hangs();

    /**
     * Relayed up to the moment the {@link Moment} names, then the connection hangs: a response that stalls after
     * its headers or after some of its stream. What was held follows when the fault ends.
     *
     * @throws UnsupportedOperationException for a moment under {@link Moment.Http} on a target that is not reached
     *         over plaintext HTTP
     */
    ActiveFault hangs(Moment moment);

    /**
     * Every byte is delayed by the latency, in both directions.
     */
    ActiveFault slow(Duration latency);

    /**
     * The connection is reset as soon as the upstream starts to respond: {@code diesMidRequest(afterBytes(0))}.
     */
    default ActiveFault diesMidRequest() {
        return diesMidRequest(Moment.afterBytes(0));
    }

    /**
     * The connection is reset mid-request, at the moment the {@link Moment} names.
     *
     * @throws UnsupportedOperationException for a moment under {@link Moment.Http} on a target that is not reached
     *         over plaintext HTTP
     */
    ActiveFault diesMidRequest(Moment moment);

    /**
     * The connection is closed cleanly, a FIN rather than a reset, as soon as the upstream starts to respond:
     * {@code closes(afterBytes(0))}. The request reached the upstream and nothing came back, which a client cannot
     * tell from a server that hung up after processing it.
     */
    default ActiveFault closes() {
        return closes(Moment.afterBytes(0));
    }

    /**
     * Relayed up to the moment the {@link Moment} names, then the connection is closed cleanly, a FIN rather than a
     * reset: a response that ends short of its {@code Content-Length}, or a chunked stream whose last chunk never
     * comes, which a client reports as a closed connection rather than a reset; a response whose length is given
     * neither way is complete when the connection closes, so a client takes a truncated one for whole.
     *
     * @throws UnsupportedOperationException for a moment under {@link Moment.Http} on a target that is not reached
     *         over plaintext HTTP
     */
    ActiveFault closes(Moment moment);

    /**
     * Latency with jitter, bandwidth and slicing, each per direction.
     */
    Advanced advanced();

    /**
     * Faults on the HTTP exchange, such as answering requests with a chosen status, and the selection of the
     * requests a fault acts on. Plaintext HTTP/1.x only.
     */
    Http http();

    /**
     * The requests the endpoint covers that went through the proxy.
     */
    Traffic traffic();
}
