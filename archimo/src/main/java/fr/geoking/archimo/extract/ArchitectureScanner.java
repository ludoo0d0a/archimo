package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Dependency;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.ArchitectureInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    public List<ClassDependency> scanClassDependencies(JavaClasses classes, List<ArchitectureInfo> infos) {
        Set<String> known = new LinkedHashSet<>();
        for (ArchitectureInfo info : infos) {
            known.add(info.className());
        }

        Set<String> uniqueEdges = new LinkedHashSet<>();
        List<ClassDependency> dependencies = new ArrayList<>();

        for (JavaClass clazz : classes) {
            String origin = clazz.getFullName();
            if (!known.contains(origin)) {
                continue;
            }
            for (Dependency dep : clazz.getDirectDependenciesFromSelf()) {
                String target = dep.getTargetClass().getFullName();
                if (origin.equals(target) || !known.contains(target)) {
                    continue;
                }
                String key = origin + "->" + target;
                if (uniqueEdges.add(key)) {
                    dependencies.add(new ClassDependency(origin, target));
                }
            }
        }
        return dependencies;
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
