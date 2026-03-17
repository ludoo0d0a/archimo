package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import fr.geoking.archimo.extract.model.ArchitectureInfo;

import java.util.ArrayList;
import java.util.List;

public class ArchitectureScanner {

    public List<ArchitectureInfo> scan(JavaClasses classes) {
        List<ArchitectureInfo> infos = new ArrayList<>();
        for (JavaClass clazz : classes) {
            String layer = identifyLayer(clazz);
            if (layer != null) {
                infos.add(new ArchitectureInfo(
                    clazz.getFullName(),
                    layer,
                    inferArchitectureType(clazz)
                ));
            }
        }
        return infos;
    }

    private String identifyLayer(JavaClass clazz) {
        if (clazz.isAnnotatedWith("org.springframework.stereotype.Controller") ||
            clazz.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")) {
            return "controller";
        }
        if (clazz.isAnnotatedWith("org.springframework.stereotype.Service")) {
            return "service";
        }
        if (clazz.isAnnotatedWith("org.springframework.stereotype.Repository") ||
            clazz.isAnnotatedWith("org.springframework.data.repository.Repository") ||
            clazz.getFullName().endsWith("Repository")) {
            return "repository";
        }
        String pkg = clazz.getPackageName();
        if (pkg.contains(".domain") || pkg.contains(".model")) {
            return "domain";
        }
        if (pkg.contains(".application")) {
            return "application";
        }
        if (pkg.contains(".infrastructure")) {
            return "infrastructure";
        }
        return null;
    }

    private String inferArchitectureType(JavaClass clazz) {
        String pkg = clazz.getPackageName();
        if (pkg.contains(".domain") || pkg.contains(".application") || pkg.contains(".infrastructure")) {
            return "hexagonal";
        }
        return "mvc";
    }
}
