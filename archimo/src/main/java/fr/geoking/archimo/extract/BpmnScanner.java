package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.BpmnFlow;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class BpmnScanner {

    private int filesParsed = 0;

    public List<BpmnFlow> scan(Path projectDir) {
        List<BpmnFlow> flows = new ArrayList<>();
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            return flows;
        }

        try (Stream<Path> paths = Files.walk(projectDir)) {
            paths.filter(p -> p.toString().endsWith(".bpmn") || p.toString().endsWith(".bpmn20.xml"))
                 .forEach(p -> {
                     flows.addAll(parseBpmn(p));
                     filesParsed++;
                 });
        } catch (Exception e) {
            System.err.println("Error scanning BPMN files: " + e.getMessage());
        }
        return flows;
    }

    public int getFilesParsed() {
        return filesParsed;
    }

    private List<BpmnFlow> parseBpmn(Path bpmnFile) {
        List<BpmnFlow> flows = new ArrayList<>();
        try (InputStream is = Files.newInputStream(bpmnFile)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);

            String engine = detectEngine(doc);

            NodeList processes = doc.getElementsByTagNameNS("*", "process");
            for (int i = 0; i < processes.getLength(); i++) {
                Element process = (Element) processes.item(i);
                String processId = process.getAttribute("id");

                NodeList serviceTasks = process.getElementsByTagNameNS("*", "serviceTask");
                for (int j = 0; j < serviceTasks.getLength(); j++) {
                    Element task = (Element) serviceTasks.item(j);
                    String name = task.getAttribute("name");
                    if (name.isEmpty()) name = task.getAttribute("id");

                    // Try to find delegate in any attribute or nested element (simplified)
                    String delegate = findDelegate(task);

                    flows.add(new BpmnFlow(
                        engine,
                        processId,
                        name,
                        delegate,
                        List.of()
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing BPMN file " + bpmnFile + ": " + e.getMessage());
        }
        return flows;
    }

    private String findDelegate(Element task) {
        String[] attrs = {"flowable:delegateExpression", "flowable:class", "camunda:delegateExpression", "camunda:class", "activiti:delegateExpression", "activiti:class", "delegateExpression", "class"};
        for (String attr : attrs) {
            String val = task.getAttribute(attr);
            if (val != null && !val.isEmpty()) return val;
        }
        return "unknown";
    }

    private String detectEngine(Document doc) {
        Element root = doc.getDocumentElement();
        String ns = root.getNamespaceURI();
        if (ns != null) {
            if (ns.contains("flowable")) return "flowable";
            if (ns.contains("camunda")) return "camunda";
            if (ns.contains("activiti")) return "activiti";
        }
        // Check attributes for namespaces
        for (int i = 0; i < root.getAttributes().getLength(); i++) {
            String attrName = root.getAttributes().item(i).getNodeName();
            if (attrName.contains("flowable")) return "flowable";
            if (attrName.contains("camunda")) return "camunda";
            if (attrName.contains("activiti")) return "activiti";
        }
        return "unknown";
    }
}
