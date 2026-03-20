# Sample E‑commerce (Spring Modulith)

Minimal Spring Modulith application used to test **Archimo** extraction: modules, events, flows, and diagrams.

## Layout

- **Root**: `fr.geoking.archimo.sample.ecommerce` — `EcommerceApplication` (`@SpringBootApplication`).
- **Top-level modules** (direct subpackages of the root):
  - **order** — Order placement and `OrderCreated` event.
  - **catalog** — Product catalog; listens to `OrderCreated`.
  - **customer** — Customer data; publishes and listens to events.

## Nested module: invoice (inside order)

The **order** module contains a nested application module **invoice**:

- Package: `fr.geoking.archimo.sample.ecommerce.order.invoice`
- Declared via `package-info.java` with `@ApplicationModule(displayName = "Invoice")`.
- Contains `InvoiceService` (e.g. create invoice for an order).

Nested modules are only visible to their parent and siblings; other top-level modules (catalog, customer) do not depend on invoice.

## Events (14 total: cross-module and internal)

**Order module (publisher)**  
- `OrderCreated` → catalog (reserve stock)  
- `OrderPaid` → catalog (confirm reservation)  
- `OrderShipped` → customer (notify)  
- `OrderCancelled` → catalog (release stock)  
- `OrderValidated` → internal (invoice submodule)  
- `OrderLineAdded` → internal (totals handler)  

**Catalog module (publisher)**  
- `StockReserved` → order (confirm reservation)  
- `ProductCreated` → order (index for search)  
- `StockLow` → internal (alerts)  
- `PriceUpdated` → internal (cache invalidation)  

**Customer module (publisher)**  
- `CustomerRegistered` → catalog (e.g. wishlist)  
- `AddressUpdated` → order (shipping address)  
- `LoyaltyPointsEarned` → internal (loyalty handler)  
- `CustomerPreferred` → internal (analytics)  

Cross-module events are between different top-level modules; internal events are published and consumed within the same module.

## Outbound HTTP (sample client modules)

The app depends on **`archimo-sample-stripe-client`** and **`archimo-sample-gravatar-client`**:

- **Payments**: `OrderService.payOrder` calls `StripePaymentBridge`, which uses the Feign `StripeApiClient` to `GET /v1/balance` when `stripe.secret-key` / `STRIPE_SECRET_KEY` is set (probe only; failures are logged).
- **Avatars**: `CustomerService.register` calls `CustomerGravatarBridge`, which uses the OpenAPI-generated OkHttp `ProfilesApi` when `gravatar.api-key` is non-empty (logs the avatar URL at DEBUG when found).

See `src/main/resources/application.properties` for property names.

## Kubernetes sample

Under **`k8s/`**: illustrative manifests (MariaDB, Redis, Kafka + ZooKeeper, NGINX front, Spring Cloud Gateway, app `Deployment`, Ingress, ConfigMap/Secret for AWS S3, Google OAuth/GCP WI, Stripe, Visa, Mailchimp). See `k8s/README.md`. Build the app JAR image with **`Dockerfile`** and the static storefront image with **`Dockerfile.nginx`** (content under **`nginx/html/`**).

## Running extraction

From the Archimo project root (with this sample on the classpath):

- In tests: `ApplicationModules.of(EcommerceApplication.class)` then `ModulithExtractor(modules, outputDir).extract()`.
- From CLI against a real project: use `--project-dir` or `--app-class=fr.geoking.archimo.sample.ecommerce.EcommerceApplication` (and the right classpath).

## Purpose

This sample is only for **testing** Archimo: it is not a full e‑commerce app. It demonstrates:

- Flat modules (order, catalog, customer).
- Nested module (order::invoice).
- At least 12 events (cross-module and internal) and multiple event flows.
