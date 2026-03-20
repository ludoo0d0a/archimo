package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import fr.geoking.archimo.extract.model.MessagingFlow;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;

public class MessagingScanner {

    public List<MessagingFlow> scan(JavaClasses classes) {
        return scan(classes, MessagingScanConcurrency.AUTO);
    }

    public List<MessagingFlow> scan(JavaClasses classes, MessagingScanConcurrency concurrency) {
        List<JavaClass> classList = StreamSupport.stream(classes.spliterator(), false).toList();
        ExecutorService executor = createPerTaskExecutor(concurrency);
        Map<String, List<String>> subscribers = new HashMap<>();
        Map<String, String> publishers = new HashMap<>();
        Map<String, String> technologies = new HashMap<>();

        try {
            List<Future<ClassMessagingData>> futures = classList.stream()
                    .map(clazz -> executor.submit(() -> scanClass(clazz)))
                    .toList();
            for (Future<ClassMessagingData> future : futures) {
                ClassMessagingData data = future.get();
                data.subscribers().forEach((destination, listenerList) ->
                        subscribers.computeIfAbsent(destination, k -> new ArrayList<>()).addAll(listenerList));
                publishers.putAll(data.publishers());
                technologies.putAll(data.technologies());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException e) {
            return List.of();
        } finally {
            executor.shutdownNow();
        }

        return subscribers.keySet().stream()
                .map(destination -> new MessagingFlow(
                        technologies.get(destination),
                        destination,
                        publishers.getOrDefault(destination, "External / Unknown"),
                        subscribers.get(destination)
                ))
                .toList();
    }

    private ClassMessagingData scanClass(JavaClass clazz) {
        Map<String, List<String>> subscribers = new HashMap<>();
        Map<String, String> publishers = new HashMap<>();
        Map<String, String> technologies = new HashMap<>();

        clazz.getMethods().forEach(method -> {
            if (method.isAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")) {
                String topic = extractAnnotationValue(method, "org.springframework.kafka.annotation.KafkaListener", "topics");
                addSubscriber(subscribers, technologies, topic, clazz.getSimpleName(), "Kafka");
            }
            if (method.isAnnotatedWith("org.springframework.jms.annotation.JmsListener")) {
                String destination = extractAnnotationValue(method, "org.springframework.jms.annotation.JmsListener", "destination");
                addSubscriber(subscribers, technologies, destination, clazz.getSimpleName(), "JMS");
            }
            if (method.isAnnotatedWith("org.springframework.amqp.rabbit.annotation.RabbitListener")) {
                String queue = extractAnnotationValue(method, "org.springframework.amqp.rabbit.annotation.RabbitListener", "queues");
                addSubscriber(subscribers, technologies, queue, clazz.getSimpleName(), "RabbitMQ");
            }

            method.getMethodCallsFromSelf().forEach(call -> {
                String targetType = call.getTargetOwner().getFullName();
                if (targetType.equals("org.springframework.kafka.core.KafkaTemplate")) {
                    publishers.put("unknown-kafka-topic", clazz.getSimpleName());
                    technologies.put("unknown-kafka-topic", "Kafka");
                } else if (targetType.equals("org.springframework.jms.core.JmsTemplate")) {
                    publishers.put("unknown-jms-destination", clazz.getSimpleName());
                    technologies.put("unknown-jms-destination", "JMS");
                }
            });
        });

        return new ClassMessagingData(subscribers, publishers, technologies);
    }

    private ExecutorService createPerTaskExecutor(MessagingScanConcurrency concurrency) {
        if (concurrency != MessagingScanConcurrency.PLATFORM) {
            try {
                Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
                return (ExecutorService) method.invoke(null);
            } catch (Exception ignored) {
                // JVM without virtual-thread executors (e.g. Java 20 or older): fall through to platform pool
            }
        }
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(threads);
    }

    private record ClassMessagingData(
            Map<String, List<String>> subscribers,
            Map<String, String> publishers,
            Map<String, String> technologies) {}

    private void addSubscriber(Map<String, List<String>> subscribers, Map<String, String> technologies, String destination, String subscriber, String tech) {
        if (destination == null || destination.isEmpty()) destination = "unknown-" + tech.toLowerCase();
        subscribers.computeIfAbsent(destination, k -> new ArrayList<>()).add(subscriber);
        technologies.put(destination, tech);
    }

    private String extractAnnotationValue(JavaMethod method, String annotationType, String property) {
        try {
            JavaAnnotation<?> annotation = method.getAnnotationOfType(annotationType);
            Object value = annotation.get(property).orElse(null);
            if (value instanceof String[]) {
                String[] arr = (String[]) value;
                return arr.length > 0 ? arr[0] : "";
            }
            if (value instanceof Object[]) {
                Object[] arr = (Object[]) value;
                return arr.length > 0 ? arr[0].toString() : "";
            }
            return value != null ? value.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
