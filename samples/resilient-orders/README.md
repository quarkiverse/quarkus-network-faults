# Resilient orders

The application and the tests of the
[Introducing Quarkus Resiliency Testing](https://quarkus.io/blog/quarkus-resiliency-testing/) blog post:
classic problems of distributed systems, one test each. It needs Docker for the PostgreSQL, Kafka
and Redis Dev Services, and the extension installed in the local Maven repository (`./mvnw install`
at the root of this repository).

The application takes orders. Placing one reserves stock with an inventory service through a REST
Client, saves the order and publishes an event to Kafka in one transaction. A Kafka consumer ships
every order through a carrier, which also gives shipping quotes. Orders are paid through a payment
service, prices are cached in Redis, and a Quarkus LangChain4j AI service summarizes text with an
OpenAI model. The inventory, payment and shipping services are served by the application itself
(`InventoryStub`, `PaymentStub`, `ShippingStub`), so nothing else has to run, but the REST Clients
reach them through the Resiliency Testing proxy exactly as they would reach real services. In tests,
`LlmStub` stands in for the OpenAI API, and the Kafka companion of Quarkus' test tooling reads the
dead-letter topic.

## Run the tests

From the root of the repository:

```bash
./mvnw -f samples/resilient-orders/pom.xml verify
```

Each test pins one problem, and fails when the protection it pins is removed:

| Test | Problem | Fails when |
|---|---|---|
| `InventoryFailuresTest.inventoryServiceHangs` | A dependency does not answer | the `@Fallback` does not defer the order |
| `InventoryFailuresTest.inventoryServiceFailsTwiceThenRecovers` | Transient failures | `@Retry` has fewer than two retries |
| `InventoryFailuresTest.circuitBreakerStopsTheCalls` | A circuit breaker that does not break | the `@CircuitBreaker` is removed: five calls instead of two |
| `InventoryFailuresTest.streamIsCutHalfway` | A stream cut halfway | the stream has no recovery: the caller's stream is cut too |
| `ConnectionFailuresTest.slowIsWorseThanDown` | Slow is worse than down | there is no `@Bulkhead`: the third order waits for the full timeout |
| `ConnectionFailuresTest.inventoryServiceIsBlackHoled` | A black-holed connection (Linux only) | there is no connect timeout: the lookup takes as long as the client's other timeouts |
| `ShippingFailuresTest.carrierSaysComeBackLater` | Coming back when asked to | the `Retry-After` header is ignored: the retry comes at once |
| `PaymentFailuresTest.responseLostAfterThePaymentWentThrough` | Retrying a payment | the idempotency key is generated per attempt: the customer is charged twice; or there is no retry: the card is charged but the payment reported as failed |
| `DatabaseFailuresTest.databaseHangs` | The database hangs | there is no JDBC `socketTimeout`: the request never comes back |
| `DatabaseFailuresTest.databaseRestarts` | The database restarts | Agroal does not validate pooled connections |
| `DatabaseFailuresTest.databaseDiesMidTransaction` | The database dies mid-transaction | the failure is not mapped to a `503` |
| `KafkaFailuresTest.kafkaIsDown` | Dual write to the database and Kafka | the event is sent fire-and-forget: the order is saved without it |
| `ShippingFailuresTest.poisonMessage` | A message that cannot be processed | the failure strategy is the default `fail`: the consumer stops, and no order behind the failing one is shipped |
| `CacheFailuresTest.cacheHangs` | The cache is down | the Redis client keeps its 10 second default timeout |
| `LlmFailuresTest.providerRateLimitsOnce` | A single rate limit | the client does not retry a `429` |
| `LlmFailuresTest.providerKeepsRateLimiting` | Retry amplification | a `@Retry` is stacked on the client's own retry: eight calls instead of two |
