package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import fr.geoking.archimo.extract.model.MessagingFlow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MessagingScanner {

    public List<MessagingFlow> scan(JavaClasses classes) {
        Map<String, List<String>> subscribers = new HashMap<>();
        Map<String, String> publishers = new HashMap<>();
        Map<String, String> technologies = new HashMap<>();

        for (JavaClass clazz : classes) {
            for (JavaMethod method : clazz.getMethods()) {
                // Detect Listeners
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

                // Detect Producers (simplified: looking for calls to Templates)
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    String targetType = call.getTargetOwner().getFullName();
                    if (targetType.equals("org.springframework.kafka.core.KafkaTemplate")) {
                        publishers.put("unknown-kafka-topic", clazz.getSimpleName());
                        technologies.put("unknown-kafka-topic", "Kafka");
                    } else if (targetType.equals("org.springframework.jms.core.JmsTemplate")) {
                        publishers.put("unknown-jms-destination", clazz.getSimpleName());
                        technologies.put("unknown-jms-destination", "JMS");
                    }
                }
            }
        }

        List<MessagingFlow> flows = new ArrayList<>();
        for (String destination : subscribers.keySet()) {
            flows.add(new MessagingFlow(
                technologies.get(destination),
                destination,
                publishers.getOrDefault(destination, "External / Unknown"),
                subscribers.get(destination)
            ));
        }
        return flows;
    }

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
