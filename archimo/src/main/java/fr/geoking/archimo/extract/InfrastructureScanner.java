package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.ExternalSystemHint;
import fr.geoking.archimo.extract.model.InfrastructureContainer;
import fr.geoking.archimo.extract.model.InfrastructureFileHit;
import fr.geoking.archimo.extract.model.InfrastructureIngress;
import fr.geoking.archimo.extract.model.InfrastructureK8sService;
import fr.geoking.archimo.extract.model.InfrastructureTopology;

import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers Dockerfiles, Compose files, and Kubernetes manifests, then infers containers and
 * external systems (databases, brokers, gateways, cloud endpoints).
 */
public final class InfrastructureScanner {

    private static final long MAX_FILE_BYTES = 512_000;
    private static final int MAX_WALK_DEPTH = 10;

    private final List<InfrastructureFileHit> files = new ArrayList<>();
    private final List<InfrastructureContainer> containers = new ArrayList<>();
    private final List<InfrastructureK8sService> k8sServices = new ArrayList<>();
    private final List<InfrastructureIngress> ingresses = new ArrayList<>();
    private final Set<String> hintKeys = new LinkedHashSet<>();
    private final List<ExternalSystemHint> externalSystems = new ArrayList<>();

    public InfrastructureTopology scan(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return InfrastructureTopology.empty();
        }
        projectRoot = projectRoot.toAbsolutePath().normalize();
        files.clear();
        containers.clear();
        k8sServices.clear();
        ingresses.clear();
        hintKeys.clear();
        externalSystems.clear();
        try {
            walk(projectRoot, projectRoot, 0);
        } catch (IOException ignored) {
            // best-effort scan
        }
        return new InfrastructureTopology(
                List.copyOf(files),
                List.copyOf(containers),
                List.copyOf(k8sServices),
                List.copyOf(ingresses),
                List.copyOf(externalSystems));
    }

    private void walk(Path root, Path dir, int depth) throws IOException {
        if (depth > MAX_WALK_DEPTH) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream.sorted().toList();
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    if (skipDir(name)) {
                        continue;
                    }
                    walk(root, p, depth + 1);
                } else {
                    processFile(root, p);
                }
            }
        }
    }

    private static boolean skipDir(String name) {
        return switch (name) {
            case ".git", ".svn", ".hg", "node_modules", "target", "build", ".idea", ".gradle",
                 "dist", "out", "vendor", "__pycache__", ".venv" -> true;
            default -> name.startsWith(".");
        };
    }

    private void processFile(Path root, Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            return;
        }
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        String rel = relativize(root, file);

        if (name.equals("Dockerfile") || lower.startsWith("dockerfile.")) {
            files.add(new InfrastructureFileHit(rel, "DOCKERFILE"));
            parseDockerfile(file, rel);
            return;
        }
        if (isComposeFile(lower)) {
            files.add(new InfrastructureFileHit(rel, "DOCKER_COMPOSE"));
            parseCompose(file, rel);
            return;
        }
        if (isKubernetesCandidate(root, file, lower)) {
            parseKubernetesManifest(file, rel);
        }
    }

    private static boolean isComposeFile(String lowerName) {
        return lowerName.equals("docker-compose.yml")
                || lowerName.equals("docker-compose.yaml")
                || lowerName.equals("docker-compose.override.yml")
                || lowerName.equals("docker-compose.override.yaml")
                || lowerName.equals("compose.yml")
                || lowerName.equals("compose.yaml")
                || lowerName.equals("compose.override.yml")
                || lowerName.equals("compose.override.yaml");
    }

    /**
     * True when the file looks like a Kubernetes manifest (directory convention or root-level apiVersion/kind).
     */
    private static boolean isKubernetesCandidate(Path root, Path file, String lowerName) {
        if (!lowerName.endsWith(".yml") && !lowerName.endsWith(".yaml")) {
            return false;
        }
        Path parent = file.getParent();
        if (parent == null) {
            return false;
        }
        if (parent.normalize().equals(root) && kubernetesYamlQuickSniff(file)) {
            return true;
        }
        String dir = parent.getFileName().toString().toLowerCase(Locale.ROOT);
        Path full = file.toAbsolutePath().normalize();
        String fullStr = full.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return dir.equals("k8s")
                || dir.equals("kubernetes")
                || dir.equals("kube")
                || dir.equals("deploy")
                || dir.equals("deployment")
                || dir.equals("manifests")
                || dir.equals("helm")
                || fullStr.contains("/charts/")
                || fullStr.contains("/k8s/")
                || fullStr.contains("/kubernetes/")
                || fullStr.contains("/deploy/")
                || fullStr.contains("/helm/");
    }

    /**
     * Reads the start of a file to see if it declares {@code apiVersion} and {@code kind} (avoids parsing
     * unrelated root YAML such as CI configs). Skips obvious Helm templates.
     */
    private static boolean kubernetesYamlQuickSniff(Path file) {
        try {
            long len = Math.min(Files.size(file), 48_000);
            if (len <= 0) {
                return false;
            }
            byte[] buf = new byte[(int) len];
            try (var in = Files.newInputStream(file)) {
                int read = in.readNBytes(buf, 0, buf.length);
                if (read <= 0) {
                    return false;
                }
                String head = new String(buf, 0, read, StandardCharsets.UTF_8);
                if (head.contains("{{") && head.contains("}}")) {
                    return false;
                }
                return head.contains("apiVersion:") && head.contains("kind:");
            }
        } catch (IOException e) {
            return false;
        }
    }

    private void parseDockerfile(Path file, String rel) throws IOException {
        String image = null;
        List<String> ports = new ArrayList<>();
        List<String> envLines = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.startsWith("FROM ") && image == null) {
                    String rest = line.substring(4).trim();
                    int sp = rest.indexOf(' ');
                    image = sp > 0 ? rest.substring(0, sp) : rest;
                    image = stripDockerStage(image);
                } else if (upper.startsWith("EXPOSE ")) {
                    String rest = line.substring(6).trim();
                    for (String p : rest.split("\\s+")) {
                        if (!p.isEmpty()) {
                            ports.add(p);
                        }
                    }
                } else if (upper.startsWith("ENV ")) {
                    envLines.add(line.substring(3).trim());
                }
            }
        }
        if (image != null && !image.equalsIgnoreCase("scratch")) {
            containers.add(new InfrastructureContainer(
                    "dockerfile",
                    image,
                    rel,
                    "dockerfile",
                    List.copyOf(ports)));
            collectHints(image, envLines, rel);
        }
    }

    private static String stripDockerStage(String image) {
        int asIdx = image.toLowerCase(Locale.ROOT).lastIndexOf(" as ");
        if (asIdx > 0) {
            return image.substring(0, asIdx).trim();
        }
        return image;
    }

    @SuppressWarnings("unchecked")
    private void parseCompose(Path file, String rel) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.contains("{{") && text.contains("}}")) {
                return;
            }
            Object loaded = new Yaml().load(text);
            if (!(loaded instanceof Map<?, ?> rootMap)) {
                return;
            }
            Object servicesObj = rootMap.get("services");
            if (!(servicesObj instanceof Map<?, ?> svcMap)) {
                return;
            }
            for (Map.Entry<?, ?> e : svcMap.entrySet()) {
                String svcName = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> svc)) {
                    continue;
                }
                String image = stringVal(svc.get("image"));
                List<String> envText = flattenEnv(svc.get("environment"));
                if (image == null || image.isBlank()) {
                    Object build = svc.get("build");
                    if (build != null) {
                        image = "(build)";
                    }
                }
                List<String> ports = parseComposePorts(svc.get("ports"));
                if (image != null) {
                    containers.add(new InfrastructureContainer(
                            svcName,
                            image,
                            rel,
                            "compose:" + svcName,
                            List.copyOf(ports)));
                    collectHints(image, envText, rel);
                }
            }
        } catch (Exception ignored) {
            // skip invalid compose
        }
    }

    private static List<String> parseComposePorts(Object portsObj) {
        List<String> out = new ArrayList<>();
        if (portsObj instanceof Collection<?> c) {
            for (Object o : c) {
                if (o instanceof Map<?, ?>) {
                    out.add(String.valueOf(o));
                } else if (o != null) {
                    String s = String.valueOf(o).trim();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void parseKubernetesManifest(Path file, String rel) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.contains("{{") && text.contains("}}")) {
                return;
            }
            boolean any = false;
            Yaml yaml = new Yaml();
            for (Object doc : yaml.loadAll(text)) {
                if (!(doc instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) map;
                if (!m.containsKey("kind") || !m.containsKey("apiVersion")) {
                    continue;
                }
                any = true;
                String kind = String.valueOf(m.get("kind"));
                switch (kind) {
                    case "Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "CronJob" -> parseWorkload(m, kind, rel);
                    case "Pod" -> parsePod(m, rel);
                    case "Service" -> parseService(m, rel);
                    case "Ingress" -> parseIngress(m, rel);
                    case "ConfigMap" -> parseConfigMap(m, rel);
                    case "Secret" -> parseSecret(m, rel);
                    default -> { }
                }
            }
            if (any) {
                files.add(new InfrastructureFileHit(rel, "KUBERNETES"));
            }
        } catch (Exception ignored) {
            // skip
        }
    }

    @SuppressWarnings("unchecked")
    private void parseWorkload(Map<String, Object> doc, String kind, String rel) {
        Map<String, Object> spec = asMap(doc.get("spec"));
        if (spec == null) {
            return;
        }
        Map<String, Object> template = asMap(spec.get("template"));
        if (template != null) {
            Map<String, Object> podSpec = asMap(template.get("spec"));
            if (podSpec != null) {
                String metaName = metadataName(doc);
                extractPodContainers(podSpec, "k8s:" + kind + "/" + (metaName != null ? metaName : "?"), rel);
            }
        }
        if ("CronJob".equals(kind)) {
            Map<String, Object> jobSpec = asMap(spec.get("jobTemplate"));
            Map<String, Object> job = jobSpec != null ? asMap(jobSpec.get("spec")) : null;
            Map<String, Object> jobPodTemplate = job != null ? asMap(job.get("template")) : null;
            Map<String, Object> podSpec = jobPodTemplate != null ? asMap(jobPodTemplate.get("spec")) : null;
            if (podSpec != null) {
                extractPodContainers(podSpec, "k8s:CronJob", rel);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void parsePod(Map<String, Object> doc, String rel) {
        Map<String, Object> spec = asMap(doc.get("spec"));
        if (spec != null) {
            extractPodContainers(spec, "k8s:Pod", rel);
        }
    }

    @SuppressWarnings("unchecked")
    private void extractPodContainers(Map<String, Object> podSpec, String context, String rel) {
        Object ct = podSpec.get("containers");
        if (ct instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> c) {
                    addK8sContainer((Map<String, Object>) c, context, rel);
                }
            }
        }
        Object init = podSpec.get("initContainers");
        if (init instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> c) {
                    addK8sContainer((Map<String, Object>) c, context + "/init", rel);
                }
            }
        }
    }

    private void addK8sContainer(Map<String, Object> c, String context, String rel) {
        String name = stringVal(c.get("name"));
        String image = stringVal(c.get("image"));
        if (image == null) {
            return;
        }
        List<String> ports = new ArrayList<>();
        Object portsObj = c.get("ports");
        if (portsObj instanceof List<?> pl) {
            for (Object p : pl) {
                if (p instanceof Map<?, ?> pm) {
                    Object cp = pm.get("containerPort");
                    if (cp != null) {
                        ports.add(String.valueOf(cp));
                    }
                }
            }
        }
        containers.add(new InfrastructureContainer(
                name != null ? name : image,
                image,
                rel,
                context,
                List.copyOf(ports)));
        List<String> envText = envVarsToLines(c.get("env"));
        collectHints(image, envText, rel);
    }

    @SuppressWarnings("unchecked")
    private void parseService(Map<String, Object> doc, String rel) {
        Map<String, Object> meta = asMap(doc.get("metadata"));
        String ns = meta != null ? stringVal(meta.get("namespace")) : null;
        String name = meta != null ? stringVal(meta.get("name")) : null;
        Map<String, Object> spec = asMap(doc.get("spec"));
        String type = spec != null ? stringVal(spec.get("type")) : "ClusterIP";
        List<String> portStrs = new ArrayList<>();
        Object ports = spec != null ? spec.get("ports") : null;
        if (ports instanceof List<?> pl) {
            for (Object o : pl) {
                if (o instanceof Map<?, ?> pm) {
                    Object p = pm.get("port");
                    Object tgt = pm.get("targetPort");
                    if (p != null) {
                        portStrs.add(tgt != null ? p + "->" + tgt : String.valueOf(p));
                    }
                }
            }
        }
        if (name != null) {
            k8sServices.add(new InfrastructureK8sService(name, ns, type != null ? type : "ClusterIP", List.copyOf(portStrs), rel));
        }
    }

    @SuppressWarnings("unchecked")
    private void parseIngress(Map<String, Object> doc, String rel) {
        Map<String, Object> meta = asMap(doc.get("metadata"));
        String ns = meta != null ? stringVal(meta.get("namespace")) : null;
        String name = meta != null ? stringVal(meta.get("name")) : null;
        Map<String, Object> metaAnn = meta != null ? asMap(meta.get("annotations")) : null;
        Map<String, Object> spec = asMap(doc.get("spec"));
        String className = spec != null ? stringVal(spec.get("ingressClassName")) : null;
        if (className == null && metaAnn != null) {
            className = stringVal(metaAnn.get("kubernetes.io/ingress.class"));
        }
        List<String> hosts = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        Object rules = spec != null ? spec.get("rules") : null;
        if (rules instanceof List<?> rl) {
            for (Object r : rl) {
                if (r instanceof Map<?, ?> rm) {
                    String host = stringVal(rm.get("host"));
                    if (host != null) {
                        hosts.add(host);
                    }
                    Object http = rm.get("http");
                    if (http instanceof Map<?, ?> hm) {
                        Object pathsObj = hm.get("paths");
                        if (pathsObj instanceof List<?> pl) {
                            for (Object p : pl) {
                                if (p instanceof Map<?, ?> pm) {
                                    String path = stringVal(pm.get("path"));
                                    if (path != null) {
                                        paths.add(path);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (name != null) {
            ingresses.add(new InfrastructureIngress(name, ns, className, List.copyOf(hosts), List.copyOf(paths), rel));
        }
        String evidence = "Ingress controller: " + (className != null ? className : "default");
        if (className != null && className.toLowerCase(Locale.ROOT).contains("nginx")) {
            addHint("REVERSE_PROXY", "NGINX Ingress", evidence, rel);
        } else if (className != null && className.toLowerCase(Locale.ROOT).contains("traefik")) {
            addHint("REVERSE_PROXY", "Traefik", evidence, rel);
        } else if (!hosts.isEmpty()) {
            addHint("HTTP_GATEWAY", "Ingress", evidence + "; hosts: " + String.join(", ", hosts), rel);
        }
    }

    @SuppressWarnings("unchecked")
    private void parseConfigMap(Map<String, Object> doc, String rel) {
        scanKubernetesDataEntries(asMap(doc.get("data")), rel, false);
        scanKubernetesDataEntries(decodeBinaryDataEntries(asMap(doc.get("binaryData"))), rel, false);
    }

    @SuppressWarnings("unchecked")
    private void parseSecret(Map<String, Object> doc, String rel) {
        scanKubernetesDataEntries(asMap(doc.get("stringData")), rel, false);
        scanKubernetesDataEntries(asMap(doc.get("data")), rel, true);
    }

    /**
     * @param base64Values when true (Secret {@code data}), values are Base64-decoded when they look like UTF-8 text.
     */
    private void scanKubernetesDataEntries(Map<String, Object> data, String rel, boolean base64Values) {
        if (data == null || data.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> e : data.entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof String s)) {
                continue;
            }
            String payload = base64Values ? tryDecodeBase64Utf8(s) : s;
            if (payload != null && !payload.isBlank()) {
                scanTextBlobForHints(payload, rel);
            }
        }
    }

    private static String tryDecodeBase64Utf8(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded.replaceAll("\\s", ""));
            if (raw.length > 256_000) {
                return null;
            }
            String decoded = new String(raw, StandardCharsets.UTF_8);
            // Heuristic: skip binary blobs (too many control chars)
            int controls = 0;
            for (int i = 0; i < Math.min(decoded.length(), 512); i++) {
                char c = decoded.charAt(i);
                if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                    controls++;
                }
            }
            if (controls > 8) {
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Map<String, Object> decodeBinaryDataEntries(Map<String, Object> binaryData) {
        if (binaryData == null || binaryData.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : binaryData.entrySet()) {
            if (e.getValue() instanceof String s) {
                String decoded = tryDecodeBase64Utf8(s);
                if (decoded != null) {
                    out.put(e.getKey(), decoded);
                }
            }
        }
        return out;
    }

    /**
     * Scans multi-line config (properties, YAML snippets, shell) for the same patterns as container env.
     */
    private void scanTextBlobForHints(String text, String rel) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                scanEnvLineForHints(t, rel);
            }
        }
    }

    private void collectHints(String image, List<String> envLines, String rel) {
        String imgLower = image.toLowerCase(Locale.ROOT);
        if (imgLower.contains("postgres")) {
            addHint("DATABASE", "PostgreSQL", "image: " + image, rel);
        } else if (imgLower.contains("mysql") || imgLower.contains("mariadb")) {
            addHint("DATABASE", "MySQL/MariaDB", "image: " + image, rel);
        } else if (imgLower.contains("mongo")) {
            addHint("DATABASE", "MongoDB", "image: " + image, rel);
        } else if (imgLower.contains("cassandra")) {
            addHint("DATABASE", "Cassandra", "image: " + image, rel);
        } else if (imgLower.contains("clickhouse")) {
            addHint("DATABASE", "ClickHouse", "image: " + image, rel);
        } else if (imgLower.contains("oracle")) {
            addHint("DATABASE", "Oracle DB", "image: " + image, rel);
        } else if (imgLower.contains("mssql") || imgLower.contains("azure-sql")) {
            addHint("DATABASE", "SQL Server", "image: " + image, rel);
        } else if (imgLower.contains("redis") || imgLower.contains("valkey")) {
            addHint("CACHE", "Redis/Valkey", "image: " + image, rel);
        } else if (imgLower.contains("memcached")) {
            addHint("CACHE", "Memcached", "image: " + image, rel);
        } else if (imgLower.contains("elasticsearch")) {
            addHint("SEARCH", "Elasticsearch", "image: " + image, rel);
        } else if (imgLower.contains("opensearch")) {
            addHint("SEARCH", "OpenSearch", "image: " + image, rel);
        } else if (imgLower.contains("kafka") || imgLower.contains("strimzi") || imgLower.contains("redpanda")) {
            addHint("MESSAGE_BUS_KAFKA", "Apache Kafka", "image: " + image, rel);
        } else if (imgLower.contains("rabbitmq")) {
            addHint("MESSAGE_BUS_JMS", "RabbitMQ (AMQP)", "image: " + image, rel);
        } else if (imgLower.contains("activemq") || imgLower.contains("artemis")) {
            addHint("MESSAGE_BUS_JMS", "JMS broker (ActiveMQ/Artemis)", "image: " + image, rel);
        } else if (imgLower.contains("minio") || imgLower.contains("/s3") || imgLower.contains("ceph")) {
            addHint("OBJECT_STORAGE", "S3-compatible object storage", "image: " + image, rel);
        } else if (imgLower.contains("nginx")) {
            addHint("REVERSE_PROXY", "NGINX", "image: " + image, rel);
        } else if (imgLower.contains("traefik")) {
            addHint("REVERSE_PROXY", "Traefik", "image: " + image, rel);
        } else if (imgLower.contains("haproxy")) {
            addHint("REVERSE_PROXY", "HAProxy", "image: " + image, rel);
        } else if (imgLower.contains("caddy")) {
            addHint("REVERSE_PROXY", "Caddy", "image: " + image, rel);
        } else if (imgLower.contains("envoy")) {
            addHint("REVERSE_PROXY", "Envoy proxy", "image: " + image, rel);
        } else if (imgLower.contains("kong")) {
            addHint("HTTP_GATEWAY", "Kong", "image: " + image, rel);
        } else if (imgLower.contains("spring-cloud-gateway")) {
            addHint("HTTP_GATEWAY", "Spring Cloud Gateway", "image: " + image, rel);
        }

        for (String line : envLines) {
            scanEnvLineForHints(line, rel);
        }
    }

    private void scanEnvLineForHints(String line, String rel) {
        if (line == null) {
            return;
        }
        String l = line.toLowerCase(Locale.ROOT);
        int eq = line.indexOf('=');
        String key = eq > 0 ? line.substring(0, eq).trim() : line.trim();
        String val = eq > 0 ? line.substring(eq + 1).trim() : "";

        if (l.contains("jdbc:postgresql") || l.contains("postgres://")) {
            addHint("DATABASE", "PostgreSQL", "env: " + key, rel);
        } else if (l.contains("jdbc:mysql") || l.contains("jdbc:mariadb")) {
            addHint("DATABASE", "MySQL/MariaDB", "env: " + key, rel);
        } else if (l.contains("jdbc:oracle") || l.contains("mongodb://") || l.contains("jdbc:sqlserver")) {
            addHint("DATABASE", "Database URL", "env: " + key, rel);
        } else if (l.contains("kafka") && (l.contains("bootstrap") || l.contains("brokers"))) {
            addHint("MESSAGE_BUS_KAFKA", "Kafka bootstrap", line.length() > 120 ? key : line, rel);
        } else if (key.toUpperCase(Locale.ROOT).contains("RABBITMQ") || l.contains("amqp://")) {
            addHint("MESSAGE_BUS_JMS", "RabbitMQ/AMQP", "env: " + key, rel);
        } else if (l.contains("activemq") || key.toUpperCase(Locale.ROOT).contains("ARTEMIS")) {
            addHint("MESSAGE_BUS_JMS", "JMS / ActiveMQ / Artemis", "env: " + key, rel);
        } else if (l.contains("amazonaws.com") || l.contains("s3.amazonaws") || key.contains("AWS_S3") || l.contains("://s3.")) {
            addHint("OBJECT_STORAGE", "AWS S3", val.length() > 80 ? key : line, rel);
        } else if (l.contains("storage.googleapis.com") || l.contains("gs://")) {
            addHint("OBJECT_STORAGE", "Google Cloud Storage", key, rel);
        } else if (l.contains("blob.core.windows.net")) {
            addHint("OBJECT_STORAGE", "Azure Blob Storage", key, rel);
        } else if (l.contains("stripe.com") || l.contains("twilio.com") || l.contains("sendgrid")) {
            addHint("SAAS_HTTP", "Third-party SaaS API", line.length() > 120 ? key : line, rel);
        } else if (key.startsWith("AWS_") || key.startsWith("AZURE_") || key.startsWith("GCP_")
                || key.startsWith("GOOGLE_") || l.contains("azure.com") || l.contains("googleapis.com")) {
            addHint("CLOUD_PROVIDER", "Cloud provider SDK/config", key, rel);
        }
    }

    private void addHint(String category, String label, String evidence, String rel) {
        String k = category + "|" + label + "|" + evidence + "|" + rel;
        if (hintKeys.add(k)) {
            externalSystems.add(new ExternalSystemHint(category, label, evidence, rel));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> flattenEnv(Object env) {
        List<String> lines = new ArrayList<>();
        if (env instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                lines.add(e.getKey() + "=" + e.getValue());
            }
        } else if (env instanceof List<?> list) {
            for (Object o : list) {
                lines.add(String.valueOf(o));
            }
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static List<String> envVarsToLines(Object env) {
        List<String> lines = new ArrayList<>();
        if (!(env instanceof List<?> list)) {
            return lines;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object name = m.get("name");
                Object value = m.get("value");
                if (name != null) {
                    lines.add(name + "=" + (value != null ? value : ""));
                }
            } else {
                lines.add(String.valueOf(o));
            }
        }
        return lines;
    }

    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    private static String stringVal(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    private static String metadataName(Map<String, Object> doc) {
        Map<String, Object> meta = asMap(doc.get("metadata"));
        return meta != null ? stringVal(meta.get("name")) : null;
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.toString();
        }
    }
}
