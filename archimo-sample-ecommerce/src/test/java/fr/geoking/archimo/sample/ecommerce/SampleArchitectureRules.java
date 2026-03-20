package fr.geoking.archimo.sample.ecommerce;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Minimal ArchUnit rules so Archimo can discover compiled @ArchTest / ArchRule metadata in test-classes.
 */
@AnalyzeClasses(packagesOf = EcommerceApplication.class)
class SampleArchitectureRules {

    @ArchTest
    static final ArchRule application_class_is_public = classes()
            .that().haveSimpleName("EcommerceApplication")
            .should().bePublic();
}
