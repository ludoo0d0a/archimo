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

## Running extraction

From the Archimo project root (with this sample on the test classpath):

- In tests: `ApplicationModules.of(EcommerceApplication.class)` then `ModulithExtractor(modules, outputDir).extract()`.
- From CLI against a real project: use `--project-dir` or `--app-class=fr.geoking.archimo.sample.ecommerce.EcommerceApplication` (and the right classpath).

## Purpose

This sample is only for **testing** Archimo: it is not a full e‑commerce app. It demonstrates:

- Flat modules (order, catalog, customer).
- Nested module (order::invoice).
- At least 12 events (cross-module and internal) and multiple event flows.
