package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import fr.geoking.archimo.extract.model.MessagingFlow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingScannerTest {

    @Test
    void scan_runsWithoutErrorOnSample() {
        JavaClasses classes = new ClassFileImporter().importPackages("fr.geoking.archimo.sample.ecommerce");
        MessagingScanner scanner = new MessagingScanner();
        List<MessagingFlow> flows = scanner.scan(classes);

        assertThat(flows).isNotNull();
    }
}
