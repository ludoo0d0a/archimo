package fr.geoking.archimo.extract;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import fr.geoking.archimo.extract.model.ExternalHttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalHttpClientScannerTest {

    @Test
    void scan_runsWithoutErrorOnSample() {
        JavaClasses classes = new ClassFileImporter().importPackages("fr.geoking.archimo.sample.ecommerce");
        List<ExternalHttpClient> clients = new ExternalHttpClientScanner().scan(classes);
        assertThat(clients).isNotNull();
    }
}
