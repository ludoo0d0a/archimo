package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import fr.geoking.archimo.extract.model.ArchitectureInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureScannerTest {

    @Test
    void scan_identifiesSpringComponents() {
        JavaClasses classes = new ClassFileImporter().importPackages("fr.geoking.archimo.sample.ecommerce");
        ArchitectureScanner scanner = new ArchitectureScanner();
        List<ArchitectureInfo> infos = scanner.scan(classes);

        assertThat(infos).anyMatch(i -> i.layer().equals("service") && i.className().contains("OrderService"));
    }
}
