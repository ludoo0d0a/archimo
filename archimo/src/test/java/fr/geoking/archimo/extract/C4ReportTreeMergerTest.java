package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.report.C4Element;
import fr.geoking.archimo.extract.model.report.C4ElementKind;
import fr.geoking.archimo.extract.model.report.C4Group;
import fr.geoking.archimo.extract.model.report.C4LevelSection;
import fr.geoking.archimo.extract.model.report.C4OutboundLink;
import fr.geoking.archimo.extract.model.report.C4ReportTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class C4ReportTreeMergerTest {

    @Test
    void merge_manifestExternalSystemSurvivesAndScannedFillsRest() {
        C4Element ext = new C4Element(
                "ext_acme",
                C4ElementKind.EXTERNAL_SYSTEM,
                "Acme Payments",
                "SaaS",
                Map.of(),
                List.of());
        C4Group g1 = new C4Group("l1-context", "L1", 0, new ArrayList<>(List.of(ext)));
        C4LevelSection l1 = new C4LevelSection(1, "L1", List.of(g1));
        C4ReportTree manifest = new C4ReportTree("FromManifest", null, List.of(l1), List.of());

        C4Element user = new C4Element("user", C4ElementKind.PERSON, "User", "x", Map.of(),
                List.of(new C4OutboundLink("app", "Uses", "HTTPS")));
        C4Element app = new C4Element("app", C4ElementKind.SOFTWARE_SYSTEM, "App", "Spring", Map.of(), List.of());
        C4Group sg = new C4Group("l1-context", "L1", 0, List.of(user, app));
        C4ReportTree scanned = new C4ReportTree("App", "com.example.Main", List.of(new C4LevelSection(1, "L1", List.of(sg))), List.of());

        List<String> warnings = new ArrayList<>();
        C4ReportTree merged = C4ReportTreeMerger.merge(manifest, scanned, warnings::add);

        assertThat(merged.applicationMainClassFqcn()).isEqualTo("com.example.Main");
        assertThat(merged.applicationShortName()).isEqualTo("App");
        C4LevelSection sec = merged.levelSections().stream().filter(s -> s.level() == 1).findFirst().orElseThrow();
        List<C4Element> els = sec.groups().get(0).elements();
        assertThat(els.stream().map(C4Element::id).toList()).contains("ext_acme", "user", "app");
        assertThat(warnings).isNotEmpty();
    }

    @Test
    void merge_nullManifest_returnsScanned() {
        C4ReportTree scanned = new C4ReportTree("A", null, List.of(), List.of());
        assertThat(C4ReportTreeMerger.merge(null, scanned, w -> {})).isSameAs(scanned);
    }
}
