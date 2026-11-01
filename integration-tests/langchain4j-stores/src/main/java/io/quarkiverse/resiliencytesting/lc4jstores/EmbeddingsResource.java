package io.quarkiverse.resiliencytesting.lc4jstores;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.quarkiverse.langchain4j.EmbeddingStoreName;

/**
 * Stores one fixed embedding in the default store, pgvector, or in the Chroma one; a failure of the store is a 503
 * with the cause.
 */
@Path("/embeddings")
public class EmbeddingsResource {

    private final EmbeddingStore<TextSegment> pgvector;
    private final EmbeddingStore<TextSegment> chroma;

    public EmbeddingsResource(EmbeddingStore<TextSegment> pgvector,
            @EmbeddingStoreName("c1") EmbeddingStore<TextSegment> chroma) {
        this.pgvector = pgvector;
        this.chroma = chroma;
    }

    @POST
    @Path("/{store}")
    public Response add(@PathParam("store") String store) {
        EmbeddingStore<TextSegment> target = switch (store) {
            case "pgvector" -> pgvector;
            case "chroma" -> chroma;
            default -> throw new IllegalArgumentException("unknown store " + store);
        };
        try {
            return Response.ok(target.add(Embedding.from(new float[] { 0.1f, 0.2f, 0.3f }), TextSegment.from("text")))
                    .build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }
}
