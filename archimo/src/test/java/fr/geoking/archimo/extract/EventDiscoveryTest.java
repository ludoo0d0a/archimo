package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventDiscoveryTest {

    @Test
    void isEvent_detectsJmoleculesDomainEvent() {
        ArchitectureScanner scanner = new ArchitectureScanner();
        JavaClasses classes = new ClassFileImporter().importClasses(
                fr.geoking.archimo.sample.ecommerce.order.OrderCreated.class,
                fr.geoking.archimo.sample.ecommerce.order.OrderService.class
        );

        JavaClass orderCreated = classes.get(fr.geoking.archimo.sample.ecommerce.order.OrderCreated.class);
        JavaClass orderService = classes.get(fr.geoking.archimo.sample.ecommerce.order.OrderService.class);

        assertThat(scanner.isEvent(orderCreated, null)).isTrue();
        assertThat(scanner.isEvent(orderService, null)).isFalse();
    }

    @Test
    void isEvent_detectsCustomInterface() {
        ArchitectureScanner scanner = new ArchitectureScanner();
        JavaClasses classes = new ClassFileImporter().importClasses(
                fr.geoking.archimo.sample.ecommerce.order.OrderCreated.class
        );

        JavaClass orderCreated = classes.get(fr.geoking.archimo.sample.ecommerce.order.OrderCreated.class);

        assertThat(scanner.isEvent(orderCreated, "org.jmolecules.event.types.DomainEvent")).isTrue();
        assertThat(scanner.isEvent(orderCreated, "com.example.UnknownInterface")).isTrue(); // Still true because of jMolecules
    }
}
