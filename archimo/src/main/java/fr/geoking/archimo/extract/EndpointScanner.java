package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import fr.geoking.archimo.extract.model.EndpointFlow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * Extracts HTTP endpoint mappings from Spring MVC controllers.
 */
public class EndpointScanner {

    public List<EndpointFlow> scan(JavaClasses classes) {
        return StreamSupport.stream(classes.spliterator(), false)
                .filter(this::isController)
                .flatMap(clazz -> {
                    String basePath = extractPathFromClass(clazz);
                    return clazz.getMethods().stream()
                            .filter(this::isEndpointMethod)
                            .map(method -> new EndpointFlow(
                                    detectHttpMethod(method),
                                    normalizePath(basePath, extractPathFromMethod(method)),
                                    clazz.getFullName(),
                                    method.getName()
                            ));
                })
                .distinct()
                .toList();
    }

    private boolean isController(JavaClass clazz) {
        return clazz.isAnnotatedWith("org.springframework.stereotype.Controller")
                || clazz.isAnnotatedWith("org.springframework.web.bind.annotation.RestController");
    }

    private boolean isEndpointMethod(JavaMethod method) {
        return method.isAnnotatedWith("org.springframework.web.bind.annotation.GetMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.PostMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.PutMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.DeleteMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.PatchMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping");
    }

    private String detectHttpMethod(JavaMethod method) {
        if (method.isAnnotatedWith("org.springframework.web.bind.annotation.GetMapping")) return "GET";
        if (method.isAnnotatedWith("org.springframework.web.bind.annotation.PostMapping")) return "POST";
        if (method.isAnnotatedWith("org.springframework.web.bind.annotation.PutMapping")) return "PUT";
        if (method.isAnnotatedWith("org.springframework.web.bind.annotation.DeleteMapping")) return "DELETE";
        if (method.isAnnotatedWith("org.springframework.web.bind.annotation.PatchMapping")) return "PATCH";
        return "REQUEST";
    }

    private String extractPathFromClass(JavaClass clazz) {
        String fromRequestMapping = extractPath(clazz, "org.springframework.web.bind.annotation.RequestMapping");
        return fromRequestMapping == null ? "" : fromRequestMapping;
    }

    private String extractPathFromMethod(JavaMethod method) {
        String[] mappingAnnotations = {
                "org.springframework.web.bind.annotation.GetMapping",
                "org.springframework.web.bind.annotation.PostMapping",
                "org.springframework.web.bind.annotation.PutMapping",
                "org.springframework.web.bind.annotation.DeleteMapping",
                "org.springframework.web.bind.annotation.PatchMapping",
                "org.springframework.web.bind.annotation.RequestMapping"
        };
        for (String annotation : mappingAnnotations) {
            String value = extractPath(method, annotation);
            if (value != null) return value;
        }
        return "";
    }

    private String extractPath(JavaClass clazz, String annotationType) {
        try {
            JavaAnnotation<?> annotation = clazz.getAnnotationOfType(annotationType);
            return getPathValue(annotation);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractPath(JavaMethod method, String annotationType) {
        try {
            JavaAnnotation<?> annotation = method.getAnnotationOfType(annotationType);
            return getPathValue(annotation);
        } catch (Exception e) {
            return null;
        }
    }

    private String getPathValue(JavaAnnotation<?> annotation) {
        Object value = annotation.get("path").orElse(null);
        String resolved = toFirstString(value);
        if (resolved != null && !resolved.isBlank()) return resolved;
        value = annotation.get("value").orElse(null);
        resolved = toFirstString(value);
        return resolved == null ? "" : resolved;
    }

    private String toFirstString(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        if (value instanceof String[] arr) return arr.length > 0 ? arr[0] : null;
        if (value instanceof Object[] arr) return arr.length > 0 ? String.valueOf(arr[0]) : null;
        return String.valueOf(value);
    }

    private String normalizePath(String basePath, String methodPath) {
        String base = basePath == null ? "" : basePath.trim();
        String method = methodPath == null ? "" : methodPath.trim();
        if (base.isBlank() && method.isBlank()) return "/";

        String combined = (trimSlashes(base) + "/" + trimSlashes(method)).replaceAll("/+", "/");
        if (!combined.startsWith("/")) combined = "/" + combined;
        return combined;
    }

    private String trimSlashes(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
