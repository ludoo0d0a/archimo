package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import fr.geoking.archimo.extract.model.ArchUnitRuleRef;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.DesignEdge;
import fr.geoking.archimo.extract.model.DesignFinding;
import fr.geoking.archimo.extract.model.FrameworkDesignInsights;
import fr.geoking.archimo.extract.model.JmoleculesElement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Detects jMolecules and ArchUnit usage from build files and bytecode; derives relations and heuristics.
 * Does not execute ArchUnit rules (use tests / CI for real violations).
 */
public final class FrameworkInsightsScanner {

    private static final String JMOLECULES_PKG = "org.jmolecules.";
    private static final String ARCHUNIT_ROOT = "com.tngtech.archunit";
    private static final String ARCHUNIT_PREFIX = ARCHUNIT_ROOT + ".";

    public FrameworkDesignInsights scan(
            JavaClasses mainClasses,
            JavaClasses testClasses,
            Path projectDir,
            List<ClassDependency> classDependencies
    ) {
        BuildHints hints = scanBuildHints(projectDir);
        List<JmoleculesElement> elements = scanJmoleculesElements(mainClasses);
        Set<String> elementNames = elements.stream().map(JmoleculesElement::className).collect(Collectors.toCollection(LinkedHashSet::new));
        List<DesignEdge> edges = buildJmoleculesEdges(classDependencies, elementNames, mainClasses);
        List<ArchUnitRuleRef> archRefs = scanArchUnitRules(testClasses, mainClasses);
        boolean archBytecode = bytecodeReferencesArchUnit(mainClasses) || bytecodeReferencesArchUnit(testClasses);
        List<DesignFinding> findings = new ArrayList<>();
        addBuildVsBytecodeFindings(hints, archRefs, archBytecode, findings);
        addJmoleculesFindings(elements, edges, findings);
        return new FrameworkDesignInsights(
                hints.archUnitDeclared,
                hints.jmoleculesDeclared,
                archBytecode,
                List.copyOf(elements),
                List.copyOf(edges),
                List.copyOf(archRefs),
                List.copyOf(findings)
        );
    }

    private record BuildHints(boolean archUnitDeclared, boolean jmoleculesDeclared) {}

    private BuildHints scanBuildHints(Path projectDir) {
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            return new BuildHints(false, false);
        }
        boolean arch = false;
        boolean jmol = false;
        try (var walk = Files.walk(projectDir, 8)) {
            for (Path p : walk.toList()) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (!name.equals("pom.xml") && !name.equals("build.gradle") && !name.equals("build.gradle.kts")) {
                    continue;
                }
                if (Files.size(p) > 512_000) {
                    continue;
                }
                String text = Files.readString(p);
                if (text.contains("com.tngtech.archunit") || text.contains("archunit-junit")) {
                    arch = true;
                }
                if (text.contains("org.jmolecules") || text.contains("jmolecules-")) {
                    jmol = true;
                }
            }
        } catch (IOException ignored) {
            // keep partial hints
        }
        return new BuildHints(arch, jmol);
    }

    private List<JmoleculesElement> scanJmoleculesElements(JavaClasses mainClasses) {
        if (mainClasses == null) {
            return List.of();
        }
        Map<String, Set<String>> byClass = new LinkedHashMap<>();
        for (JavaClass clazz : mainClasses) {
            if (clazz.getFullName().startsWith(JMOLECULES_PKG)) {
                continue;
            }
            LinkedHashSet<String> stereotypes = new LinkedHashSet<>();
            for (JavaClass ifc : clazz.getRawInterfaces()) {
                String fn = ifc.getFullName();
                if (fn.startsWith(JMOLECULES_PKG)) {
                    stereotypes.add(stereotypeLabel(fn));
                }
            }
            clazz.getAnnotations().forEach(a -> {
                String fn = a.getRawType().getFullName();
                if (fn.startsWith(JMOLECULES_PKG)) {
                    stereotypes.add("@" + simpleName(fn));
                }
            });
            clazz.getRawSuperclass().ifPresent(sc -> {
                String fn = sc.getFullName();
                if (fn.startsWith(JMOLECULES_PKG)) {
                    stereotypes.add("extends " + simpleName(fn));
                }
            });
            if (!stereotypes.isEmpty()) {
                byClass.put(clazz.getFullName(), stereotypes);
            }
        }
        return byClass.entrySet().stream()
                .map(e -> new JmoleculesElement(e.getKey(), sortStereotypes(e.getValue())))
                .sorted(Comparator.comparing(JmoleculesElement::className))
                .toList();
    }

    private static List<String> sortStereotypes(Set<String> stereotypes) {
        return stereotypes.stream().sorted().toList();
    }

    private static String stereotypeLabel(String interfaceFullName) {
        String simple = simpleName(interfaceFullName);
        return switch (simple) {
            case "DomainEvent" -> "DomainEvent";
            case "AggregateRoot" -> "AggregateRoot";
            case "Entity" -> "Entity";
            case "ValueObject" -> "ValueObject";
            case "Identifier" -> "Identifier";
            default -> "implements " + simple;
        };
    }

    private static String simpleName(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return dot < 0 ? fullName : fullName.substring(dot + 1);
    }

    private List<DesignEdge> buildJmoleculesEdges(
            List<ClassDependency> classDependencies,
            Set<String> jmoleculesClassNames,
            JavaClasses mainClasses
    ) {
        if (jmoleculesClassNames.isEmpty()) {
            return List.of();
        }
        Set<String> deps = new LinkedHashSet<>();
        List<DesignEdge> out = new ArrayList<>();
        if (classDependencies != null) {
            for (ClassDependency d : classDependencies) {
                if (jmoleculesClassNames.contains(d.fromClass()) && jmoleculesClassNames.contains(d.toClass())) {
                    String key = d.fromClass() + "->" + d.toClass() + "|uses";
                    if (deps.add(key)) {
                        out.add(new DesignEdge(d.fromClass(), d.toClass(), "uses"));
                    }
                }
            }
        }
        if (mainClasses != null) {
            for (JavaClass clazz : mainClasses) {
                if (!jmoleculesClassNames.contains(clazz.getFullName())) {
                    continue;
                }
                for (var field : clazz.getFields()) {
                    String target = field.getRawType().getFullName();
                    if (jmoleculesClassNames.contains(target) && !target.equals(clazz.getFullName())) {
                        String key = clazz.getFullName() + "->" + target + "|field";
                        if (deps.add(key)) {
                            out.add(new DesignEdge(clazz.getFullName(), target, "field → " + field.getName()));
                        }
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private List<ArchUnitRuleRef> scanArchUnitRules(JavaClasses testClasses, JavaClasses mainClasses) {
        List<ArchUnitRuleRef> refs = new ArrayList<>();
        Stream.concat(stream(testClasses), stream(mainClasses))
                .filter(c -> !c.getFullName().startsWith(ARCHUNIT_PREFIX))
                .forEach(c -> {
                    List<String> methods = c.getMethods().stream()
                            .filter(m -> m.isAnnotatedWith("com.tngtech.archunit.junit.ArchTest"))
                            .map(JavaMethod::getName)
                            .sorted()
                            .toList();
                    List<String> fields = c.getFields().stream()
                            .filter(f -> isArchRuleField(f))
                            .map(JavaField::getName)
                            .sorted()
                            .toList();
                    boolean analyze = c.isAnnotatedWith("com.tngtech.archunit.junit.AnalyzeClasses");
                    if (!methods.isEmpty() || !fields.isEmpty() || analyze) {
                        refs.add(new ArchUnitRuleRef(c.getFullName(), methods, fields, analyze));
                    }
                });
        refs.sort(Comparator.comparing(ArchUnitRuleRef::className));
        return refs;
    }

    private static boolean isArchRuleField(JavaField f) {
        String t = f.getRawType().getFullName();
        return "com.tngtech.archunit.lang.ArchRule".equals(t);
    }

    private boolean bytecodeReferencesArchUnit(JavaClasses classes) {
        return stream(classes).anyMatch(c -> c.getDirectDependenciesFromSelf().stream()
                .anyMatch(d -> {
                    String pkg = d.getTargetClass().getPackageName();
                    return ARCHUNIT_ROOT.equals(pkg) || pkg.startsWith(ARCHUNIT_PREFIX);
                }));
    }

    private static Stream<JavaClass> stream(JavaClasses classes) {
        if (classes == null) {
            return Stream.empty();
        }
        return StreamSupport.stream(classes.spliterator(), false);
    }

    private static boolean isAggregateStereotype(String s) {
        return "AggregateRoot".equals(s) || "@AggregateRoot".equals(s) || s.contains("AggregateRoot");
    }

    private void addBuildVsBytecodeFindings(
            BuildHints hints,
            List<ArchUnitRuleRef> archRefs,
            @SuppressWarnings("unused") boolean archBytecode,
            List<DesignFinding> findings
    ) {
        if (hints.archUnitDeclared && archRefs.isEmpty()) {
            findings.add(new DesignFinding(
                    DesignFinding.SEVERITY_INFO,
                    "ARCHUNIT_NO_COMPILED_RULES",
                    "ArchUnit appears in build files but no @ArchTest rules were found in compiled classes. "
                            + "Run test-compile (e.g. mvn test-compile) so target/test-classes is available.",
                    null
            ));
        }
    }

    private void addJmoleculesFindings(List<JmoleculesElement> elements, List<DesignEdge> edges, List<DesignFinding> findings) {
        Set<String> aggregates = elements.stream()
                .filter(e -> e.stereotypes().stream().anyMatch(FrameworkInsightsScanner::isAggregateStereotype))
                .map(JmoleculesElement::className)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (aggregates.size() >= 2) {
            for (DesignEdge e : edges) {
                if ("uses".equals(e.kind()) && aggregates.contains(e.fromClass()) && aggregates.contains(e.toClass())) {
                    findings.add(new DesignFinding(
                            DesignFinding.SEVERITY_WARN,
                            "JM_AGGREGATE_DEPENDENCY",
                            "Direct dependency between two jMolecules aggregate-tagged types — consider referencing IDs or domain events instead.",
                            e.fromClass()
                    ));
                }
            }
        }
        for (JmoleculesElement el : elements) {
            if (el.stereotypes().stream().anyMatch(s -> s.contains("DomainEvent"))) {
                String pkg = el.className().toLowerCase(Locale.ROOT);
                if (pkg.contains(".infra") || pkg.contains("infrastructure")) {
                    findings.add(new DesignFinding(
                            DesignFinding.SEVERITY_WARN,
                            "JM_EVENT_LAYER",
                            "Domain event type lives under an infrastructure-style package — events are often kept in the domain API.",
                            el.className()
                    ));
                }
            }
        }
    }
}
