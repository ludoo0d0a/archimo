# Stripe Feign sample

Minimal [Spring Cloud OpenFeign](https://spring.io/projects/spring-cloud-openfeign) client for a handful of [Stripe API](https://docs.stripe.com/api) operations:

- `GET /v1/balance`
- `GET /v1/customers` (with `limit`)
- `GET /v1/customers/{id}` (optional: pass customer id as first program argument)

The `@FeignClient` on `StripeApiClient` is what Archimo classifies as outbound **Feign** HTTP usage.

## Run

Use a secret key from the [Stripe Dashboard](https://dashboard.stripe.com/apikeys).

```bash
export STRIPE_SECRET_KEY=sk_test_...
mvn -pl archimo-sample-stripe-client spring-boot:run
```

With a specific customer:

```bash
mvn -pl archimo-sample-stripe-client spring-boot:run -Dspring-boot.run.arguments=cus_xxx
```

## Build only

```bash
mvn -pl archimo-sample-stripe-client compile
```

Spring Cloud **2025.1.x** is aligned with Spring Boot 4 (see parent `pom.xml`).
