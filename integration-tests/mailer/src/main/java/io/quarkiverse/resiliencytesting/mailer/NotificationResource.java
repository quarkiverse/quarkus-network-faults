package io.quarkiverse.resiliencytesting.mailer;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.mailer.MailerName;

/**
 * Sends mails through the blocking default mailer and a named one; a mailer failure is a 503 with the cause.
 */
@Path("/notifications")
public class NotificationResource {

    private final Mailer mailer;
    private final Mailer alerts;

    public NotificationResource(Mailer mailer, @MailerName("alerts") Mailer alerts) {
        this.mailer = mailer;
        this.alerts = alerts;
    }

    @POST
    @Path("/order")
    public Response order() {
        return send(mailer, Mail.withText("customer@example.com", "Your order", "It ships tomorrow."));
    }

    @POST
    @Path("/alert")
    public Response alert() {
        return send(alerts, Mail.withText("ops@example.com", "Alert", "Stock is low."));
    }

    private static Response send(Mailer mailer, Mail mail) {
        try {
            mailer.send(mail);
            return Response.noContent().build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }
}
