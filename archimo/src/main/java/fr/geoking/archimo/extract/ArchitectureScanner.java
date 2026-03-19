package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaField;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.EntityRelation;

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

    public List<EntityRelation> scanEntityRelations(JavaClasses classes) {
        Set<String> entityTypes = new LinkedHashSet<>();
        for (JavaClass clazz : classes) {
            if (isEntity(clazz)) {
                entityTypes.add(clazz.getFullName());
            }
        }

        List<EntityRelation> relations = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (JavaClass entity : classes) {
            if (!entityTypes.contains(entity.getFullName())) {
                continue;
            }
            for (JavaField field : entity.getFields()) {
                String relationType = relationType(field);
                if (relationType == null) {
                    continue;
                }
                String target = field.getRawType().getFullName();
                if (!entityTypes.contains(target) || target.equals(entity.getFullName())) {
                    continue;
                }
                String key = entity.getFullName() + "->" + target + ":" + relationType;
                if (unique.add(key)) {
                    relations.add(new EntityRelation(entity.getFullName(), target, relationType));
                }
            }
        }
        return relations;
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

    private boolean isEntity(JavaClass clazz) {
        return clazz.isAnnotatedWith("jakarta.persistence.Entity")
                || clazz.isAnnotatedWith("javax.persistence.Entity");
    }

    private String relationType(JavaField field) {
        if (field.isAnnotatedWith("jakarta.persistence.OneToMany") || field.isAnnotatedWith("javax.persistence.OneToMany")) {
            return "one-to-many";
        }
        if (field.isAnnotatedWith("jakarta.persistence.ManyToOne") || field.isAnnotatedWith("javax.persistence.ManyToOne")) {
            return "many-to-one";
        }
        if (field.isAnnotatedWith("jakarta.persistence.OneToOne") || field.isAnnotatedWith("javax.persistence.OneToOne")) {
            return "one-to-one";
        }
        if (field.isAnnotatedWith("jakarta.persistence.ManyToMany") || field.isAnnotatedWith("javax.persistence.ManyToMany")) {
            return "many-to-many";
        }
        return null;
    }
}
