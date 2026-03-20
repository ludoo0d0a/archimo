package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import fr.geoking.archimo.extract.model.ExternalHttpClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * Detects outbound HTTP usage: OpenFeign, WebClient, RestTemplate, OpenAPI Generator clients,
 * Retrofit APIs, OkHttp, Apache HttpClient, {@code java.net.http.HttpClient}, JAX-RS client.
 */
public class ExternalHttpClientScanner {

    private static final String FEIGN = "org.springframework.cloud.openfeign.FeignClient";

    public List<ExternalHttpClient> scan(JavaClasses classes) {
        Set<String> seen = new LinkedHashSet<>();
        List<ExternalHttpClient> out = new ArrayList<>();
        StreamSupport.stream(classes.spliterator(), false).forEach(clazz -> {
            scanFeign(clazz).ifPresent(r -> add(out, seen, r));
            if (isOpenApiToolsApiInterface(clazz)) {
                add(out, seen, new ExternalHttpClient("OPENAPI_GENERATED", clazz.getFullName(), "API interface"));
            }
            scanRetrofitApi(clazz).ifPresent(r -> add(out, seen, r));
            for (JavaField field : clazz.getFields()) {
                fieldUsage(clazz, field).ifPresent(r -> add(out, seen, r));
            }
            for (JavaMethod method : clazz.getMethods()) {
                method.getMethodCallsFromSelf().forEach(call -> methodCallUsage(clazz, method, call).ifPresent(r -> add(out, seen, r)));
            }
        });
        return out;
    }

    private void add(List<ExternalHttpClient> out, Set<String> seen, ExternalHttpClient r) {
        String key = r.clientKind() + "|" + r.declaringClass() + "|" + r.detail();
        if (seen.add(key)) {
            out.add(r);
        }
    }

    private Optional<ExternalHttpClient> scanFeign(JavaClass clazz) {
        if (!clazz.isAnnotatedWith(FEIGN)) {
            return Optional.empty();
        }
        return Optional.of(new ExternalHttpClient("FEIGN", clazz.getFullName(), feignDetail(clazz)));
    }

    private String feignDetail(JavaClass clazz) {
        try {
            JavaAnnotation<?> a = clazz.getAnnotationOfType(FEIGN);
            String url = firstAnnotationString(a, "url");
            if (url != null && !url.isBlank()) {
                return "url=" + url.trim();
            }
            String path = firstAnnotationString(a, "path");
            String name = firstAnnotationString(a, "name");
            if (name != null && !name.isBlank()) {
                String p = path != null && !path.isBlank() ? ", path=" + path.trim() : "";
                return "name=" + name.trim() + p;
            }
            String value = firstAnnotationString(a, "value");
            if (value != null && !value.isBlank()) {
                return "serviceId=" + value.trim();
            }
            return clazz.getSimpleName();
        } catch (Exception e) {
            return clazz.getSimpleName();
        }
    }

    private String firstAnnotationString(JavaAnnotation<?> annotation, String property) {
        try {
            Object value = annotation.get(property).orElse(null);
            if (value instanceof String s) {
                return s;
            }
            if (value instanceof String[] arr && arr.length > 0) {
                return arr[0];
            }
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isOpenApiToolsApiInterface(JavaClass clazz) {
        if (!clazz.isInterface()) {
            return false;
        }
        String pkg = clazz.getPackageName();
        if (!pkg.contains("openapitools")) {
            return false;
        }
        String simple = clazz.getSimpleName();
        return simple.endsWith("Api") && !simple.endsWith("ApiCallback") && !simple.endsWith("ApiException");
    }

    private Optional<ExternalHttpClient> scanRetrofitApi(JavaClass clazz) {
        if (!clazz.isInterface()) {
            return Optional.empty();
        }
        boolean hasRetrofitCall = clazz.getMethods().stream().anyMatch(m -> {
            try {
                return m.getRawReturnType().getFullName().startsWith("retrofit2.Call");
            } catch (Exception e) {
                return false;
            }
        });
        if (!hasRetrofitCall) {
            return Optional.empty();
        }
        return Optional.of(new ExternalHttpClient("RETROFIT", clazz.getFullName(), "Retrofit service interface"));
    }

    private Optional<ExternalHttpClient> fieldUsage(JavaClass owner, JavaField field) {
        String type;
        try {
            type = field.getRawType().getFullName();
        } catch (Exception e) {
            return Optional.empty();
        }
        String kind = mapClientKind(type);
        if (kind == null) {
            return Optional.empty();
        }
        String detail = "field " + field.getName() + " : " + simpleType(type);
        return Optional.of(new ExternalHttpClient(kind, owner.getFullName(), detail));
    }

    private Optional<ExternalHttpClient> methodCallUsage(JavaClass owner, JavaMethod from, JavaMethodCall call) {
        String target;
        try {
            target = call.getTargetOwner().getFullName();
        } catch (Exception e) {
            return Optional.empty();
        }
        String kind = mapClientKind(target);
        if (kind == null) {
            return Optional.empty();
        }
        String detail = from.getName() + "() -> " + call.getName() + "() on " + simpleType(target);
        return Optional.of(new ExternalHttpClient(kind + "_CALL", owner.getFullName(), detail));
    }

    private String simpleType(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return dot >= 0 ? fullName.substring(dot + 1) : fullName;
    }

    /**
     * Maps fully-qualified type names to a stable client kind label.
     */
    private String mapClientKind(String typeName) {
        if (typeName == null) {
            return null;
        }
        if ("org.springframework.web.reactive.function.client.WebClient".equals(typeName)) {
            return "WEBCLIENT";
        }
        if ("org.springframework.web.client.RestTemplate".equals(typeName)) {
            return "REST_TEMPLATE";
        }
        if ("org.openapitools.client.ApiClient".equals(typeName)) {
            return "OPENAPI_API_CLIENT";
        }
        if ("okhttp3.OkHttpClient".equals(typeName)) {
            return "OKHTTP";
        }
        if ("java.net.http.HttpClient".equals(typeName)) {
            return "JAVA_HTTP_CLIENT";
        }
        if ("org.apache.hc.client5.http.classic.HttpClient".equals(typeName)) {
            return "APACHE_HTTP_CLIENT5";
        }
        if ("org.apache.hc.client5.http.async.HttpAsyncClient".equals(typeName)) {
            return "APACHE_HTTP_ASYNC5";
        }
        if ("org.apache.http.client.HttpClient".equals(typeName)) {
            return "APACHE_HTTP_CLIENT4";
        }
        if ("jakarta.ws.rs.client.Client".equals(typeName) || "javax.ws.rs.client.Client".equals(typeName)) {
            return "JAX_RS_CLIENT";
        }
        if ("retrofit2.Retrofit".equals(typeName)) {
            return "RETROFIT";
        }
        return null;
    }
}
