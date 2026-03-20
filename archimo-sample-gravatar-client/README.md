# Gravatar OpenAPI + OkHttp sample

This module uses [OpenAPI Generator](https://openapi-generator.tech/) (`java` generator, **`okhttp-gson`** library) against the official [Gravatar v3 OpenAPI document](https://api.gravatar.com/v3/openapi), vendored as `src/main/resources/gravatar-v3-openapi.json`.

Generated code lives under `org.openapitools.client` (OkHttp + Gson), which Archimo can detect as an OpenAPI-generated client.

## What the sample does

`GravatarAvatarSample` calls `GET /profiles/{sha256}` via `ProfilesApi`, reads `avatar_url`, downloads the image with the same `OkHttpClient`, writes `gravatar-avatar.image`, and emits `gravatar-preview.html` (img tag pointing at the remote URL for easy viewing).

## Prereqs

1. Create a **Bearer** API key in the [Gravatar developer dashboard](https://gravatar.com/developers).
2. Use an email that has a Gravatar profile (the hash must match Gravatar’s rules: trim, lowercase, then SHA-256).

## Run

```bash
export GRAVATAR_API_KEY='your-bearer-token'
mvn -pl archimo-sample-gravatar-client compile exec:java -Dexec.args='you@example.com'
```

Then open `gravatar-preview.html` from the current working directory in a browser.

## Build client only

```bash
mvn -pl archimo-sample-gravatar-client compile
```
