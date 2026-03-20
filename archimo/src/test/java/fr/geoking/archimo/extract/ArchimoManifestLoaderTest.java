package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.report.C4ReportTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ArchimoManifestLoaderTest {

    @TempDir
    Path projectRoot;

    @Test
    void loadIfPresent_readsPartialJson() throws Exception {
        String json = """
                {
                  "levelSections": [
                    {
                      "level": 1,
                      "title": "Context",
                      "groups": [
                        {
                          "groupId": "custom",
                          "title": "Partners",
                          "sortOrder": 0,
                          "elements": [
                            {
                              "id": "partner_x",
                              "kind": "EXTERNAL_SYSTEM",
                              "label": "Partner API",
                              "technology": "HTTPS",
                              "attributes": {},
                              "links": []
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
        Files.writeString(projectRoot.resolve("archimo.mf"), json);

        C4ReportTree t = ArchimoManifestLoader.loadIfPresent(projectRoot).orElseThrow();
        assertThat(t.levelSections()).hasSize(1);
        assertThat(t.levelSections().get(0).groups().get(0).elements().get(0).id()).isEqualTo("partner_x");
    }
}
