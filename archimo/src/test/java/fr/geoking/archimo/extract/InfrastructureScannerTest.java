package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.ExternalSystemHint;
import fr.geoking.archimo.extract.model.InfrastructureTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class InfrastructureScannerTest {

    @TempDir
    Path projectDir;

    @Test
    void scan_detectsComposeDatabaseKafkaNginxAndS3Env() throws Exception {
        Files.writeString(projectDir.resolve("docker-compose.yml"), """
                services:
                  api:
                    image: eclipse-temurin:21-jre
                    environment:
                      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
                      AWS_S3_BUCKET: my-bucket
                  db:
                    image: postgres:16-alpine
                  broker:
                    image: bitnami/kafka:latest
                  edge:
                    image: nginx:alpine
                    ports:
                      - "80:80"
                """);

        InfrastructureTopology topo = new InfrastructureScanner().scan(projectDir);

        assertThat(topo.files()).anyMatch(f -> "DOCKER_COMPOSE".equals(f.kind()));
        assertThat(topo.containers()).extracting(c -> c.name())
                .contains("api", "db", "broker", "edge");

        Set<String> categories = topo.externalSystems().stream()
                .map(ExternalSystemHint::category)
                .collect(Collectors.toSet());
        assertThat(categories).contains("DATABASE", "MESSAGE_BUS_KAFKA", "OBJECT_STORAGE", "REVERSE_PROXY");
    }

    @Test
    void scan_detectsKubernetesIngressAndService() throws Exception {
        Path k8s = projectDir.resolve("k8s");
        Files.createDirectories(k8s);
        Files.writeString(k8s.resolve("app.yaml"), """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: shop
                spec:
                  template:
                    spec:
                      containers:
                        - name: app
                          image: myregistry/shop:1.0
                          env:
                            - name: SPRING_DATASOURCE_URL
                              value: jdbc:postgresql://postgres:5432/db
                ---
                apiVersion: v1
                kind: Service
                metadata:
                  name: shop
                spec:
                  type: ClusterIP
                  ports:
                    - port: 8080
                      targetPort: 8080
                ---
                apiVersion: networking.k8s.io/v1
                kind: Ingress
                metadata:
                  name: shop
                  annotations:
                    kubernetes.io/ingress.class: nginx
                spec:
                  rules:
                    - host: shop.example.com
                      http:
                        paths:
                          - path: /
                            pathType: Prefix
                            backend:
                              service:
                                name: shop
                                port:
                                  number: 8080
                """);

        InfrastructureTopology topo = new InfrastructureScanner().scan(projectDir);

        assertThat(topo.files()).anyMatch(f -> "KUBERNETES".equals(f.kind()));
        assertThat(topo.kubernetesServices()).anyMatch(s -> "shop".equals(s.name()));
        assertThat(topo.ingresses()).anyMatch(i -> i.hosts().contains("shop.example.com"));
        assertThat(topo.externalSystems()).anyMatch(h ->
                "DATABASE".equals(h.category())
                        && (h.evidence().contains("jdbc:postgresql") || h.evidence().contains("SPRING_DATASOURCE")));
        assertThat(topo.externalSystems()).anyMatch(h ->
                "REVERSE_PROXY".equals(h.category()) && "NGINX Ingress".equals(h.label()));
    }

    @Test
    void scan_dockerfileFromImageHints() throws Exception {
        Files.writeString(projectDir.resolve("Dockerfile"), """
                FROM amazoncorretto:21-alpine
                EXPOSE 8080
                ENV SPRING_RABBITMQ_HOST=rabbit
                """);

        InfrastructureTopology topo = new InfrastructureScanner().scan(projectDir);

        assertThat(topo.files()).anyMatch(f -> "DOCKERFILE".equals(f.kind()));
        assertThat(topo.containers()).anyMatch(c -> c.image().contains("amazoncorretto"));
        assertThat(topo.externalSystems()).anyMatch(h ->
                "MESSAGE_BUS_JMS".equals(h.category()) && h.evidence().contains("SPRING_RABBITMQ_HOST"));
    }
}
