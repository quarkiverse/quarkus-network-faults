package io.quarkiverse.resiliencytesting.mongodb;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import org.bson.Document;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

/**
 * Books in a MongoDB collection through the blocking client; a MongoDB failure is a 503 with the cause.
 */
@Path("/books")
public class BookResource {

    private final MongoClient mongo;

    public BookResource(MongoClient mongo) {
        this.mongo = mongo;
    }

    private MongoCollection<Document> books() {
        return mongo.getDatabase("shop").getCollection("books");
    }

    @POST
    public Response add(@QueryParam("title") String title) {
        try {
            books().insertOne(new Document("title", title));
            return Response.ok("added").build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }

    @GET
    @Path("/count")
    public Response count() {
        try {
            return Response.ok(String.valueOf(books().countDocuments())).build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }
}
