package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.BpmnFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void scan_parsesBpmnFile() throws IOException {
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
                  <process id="orderProcess" name="Order Process">
                    <serviceTask id="task1" name="Reserve Stock" flowable:delegateExpression="${reserveStockDelegate}" />
                  </process>
                </definitions>
                """;
        Files.writeString(tempDir.resolve("order.bpmn"), bpmn);

        BpmnScanner scanner = new BpmnScanner();
        List<BpmnFlow> flows = scanner.scan(tempDir);

        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).processId()).isEqualTo("orderProcess");
        assertThat(flows.get(0).stepName()).isEqualTo("Reserve Stock");
        assertThat(flows.get(0).delegateBean()).isEqualTo("${reserveStockDelegate}");
        assertThat(flows.get(0).engine()).isEqualTo("flowable");
    }
}
