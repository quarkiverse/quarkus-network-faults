package io.quarkiverse.resiliencytesting.graphql;

import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;

/**
 * The GraphQL service the clients call, served by this same application.
 */
@GraphQLApi
public class LuckyNumbers {

    @Query
    public int lucky() {
        return 12;
    }
}
