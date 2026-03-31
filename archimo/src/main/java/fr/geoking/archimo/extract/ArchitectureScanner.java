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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class ArchitectureScanner {

    public List<ArchitectureInfo> scan(JavaClasses classes) {
        return StreamSupport.stream(classes.spliterator(), false)
                .map(clazz -> {
                    String layer = identifyLayer(clazz);
                    return layer != null ? new ArchitectureInfo(clazz.getFullName(), layer, inferArchitectureType(clazz)) : null;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public List<ClassDependency> scanClassDependencies(JavaClasses classes, List<ArchitectureInfo> infos) {
        Set<String> known = infos.stream()
                .map(ArchitectureInfo::className)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return StreamSupport.stream(classes.spliterator(), false)
                .filter(clazz -> known.contains(clazz.getFullName()))
                .flatMap(clazz -> clazz.getDirectDependenciesFromSelf().stream()
                        .filter(dep -> !clazz.getFullName().equals(dep.getTargetClass().getFullName()) && known.contains(dep.getTargetClass().getFullName()))
                        .map(dep -> new ClassDependency(clazz.getFullName(), dep.getTargetClass().getFullName())))
                .distinct()
                .toList();
    }

    public boolean isEvent(JavaClass clazz, String customEventInterface) {
        if (clazz.isInterface()) {
            return false;
        }

        // jMolecules interfaces
        if (clazz.isAssignableTo("org.jmolecules.event.types.DomainEvent") ||
            clazz.isAssignableTo("org.jmolecules.ddd.types.DomainEvent")) {
            return true;
        }

        // jMolecules annotations
        if (clazz.isAnnotatedWith("org.jmolecules.event.annotation.DomainEvent") ||
            clazz.isAnnotatedWith("org.jmolecules.ddd.annotation.DomainEvent")) {
            return true;
        }

        // Custom interface
        if (customEventInterface != null && !customEventInterface.isBlank()) {
            if (clazz.isAssignableTo(customEventInterface)) {
                return true;
            }
        }

        // Heuristic: Ends with "Event" and lives in a domain/model package
        String name = clazz.getSimpleName();
        if (name.endsWith("Event") || name.endsWith("Command")) {
            String layer = identifyLayer(clazz);
            if ("domain".equals(layer) || "application".equals(layer)) {
                return true;
            }
        }

        return false;
    }

    public List<EntityRelation> scanEntityRelations(JavaClasses classes) {
        Set<String> entityTypes = StreamSupport.stream(classes.spliterator(), false)
                .filter(this::isEntity)
                .map(JavaClass::getFullName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return StreamSupport.stream(classes.spliterator(), false)
                .filter(entity -> entityTypes.contains(entity.getFullName()))
                .flatMap(entity -> entity.getFields().stream()
                        .map(field -> {
                            String relationType = relationType(field);
                            String target = field.getRawType().getFullName();
                            if (relationType != null && entityTypes.contains(target) && !target.equals(entity.getFullName())) {
                                return new EntityRelation(entity.getFullName(), target, relationType);
                            }
                            return null;
                        })
                        .filter(Objects::nonNull))
                .distinct()
                .toList();
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
