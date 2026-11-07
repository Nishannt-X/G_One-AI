package com.example.g_one;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Checks of the home page and demo stage that need no server: the real stage-core.js and app.js run under Node (skipped,
 * visibly, if Node is missing), and the markup is checked for the accessibility and honesty rules we promised.
 */
class SiteClientTest {
    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Path JS = Path.of("src/test/js");

    @Test
    void stageCoreAcceptsOnlySeatMessagesAndDerivesTheRailFromThem() throws Exception {
        node("stage-core-check.js", STATIC.resolve("stage-core.js"));
    }

    @Test
    void appRunsAsAStageSeatWithItsOwnStorageAndReportsMetadataOnly() throws Exception {
        node("embed-check.js", STATIC.resolve("app.js"), STATIC.resolve("stage-core.js"));
    }

    @Test
    void homePageHasLandmarksLabelsAndTitledFrames() throws Exception {
        String html = Files.readString(STATIC.resolve("index.html"));
        assertThat(html).contains("<html lang=\"en\">", "name=\"viewport\"", "class=\"skip\" href=\"#main\"", "<main id=\"main\">",
                "<nav aria-label=\"Sections\">", "<footer");
        assertThat(count(html, "<h1")).as("exactly one h1").isEqualTo(1);
        // Every form control has a visible label; live regions announce changes.
        assertThat(html).contains("<label>Farmer speaks <select id=\"farmerLang\"", "<label>Buyer speaks <select id=\"buyerLang\"",
                "role=\"alert\"", "role=\"log\" aria-live=\"polite\"");
        // Decorative art is hidden from assistive tech.
        assertThat(html).contains("class=\"hero-art\" aria-hidden=\"true\"");
        // The page script creates the frames; it must give each a title.
        assertThat(Files.readString(STATIC.resolve("stage.js"))).contains("frame.title =");
    }

    @Test
    void everyElementIdAScriptLooksUpExistsInItsPage() throws Exception {
        // A script that looks up a missing id fails silently at runtime (the stage once never started because of this).
        String home = Files.readString(STATIC.resolve("index.html"));
        String stage = Files.readString(STATIC.resolve("stage.js"));
        java.util.Set<String> ids = new java.util.TreeSet<>();
        var m = Pattern.compile("\\$\\('([A-Za-z][A-Za-z0-9-]*)'\\)").matcher(stage);
        while (m.find()) ids.add(m.group(1));
        for (String seat : List.of("farmer", "buyer")) ids.addAll(List.of("phone-" + seat, "idle-" + seat, "seat-" + seat));
        assertThat(ids).contains("stageForm", "startBtn", "rail", "lamps");
        for (String id : ids) assertThat(home).as("index.html has id " + id).contains("id=\"" + id + "\"");

        String app = Files.readString(STATIC.resolve("app.html"));
        var a = Pattern.compile("\\$\\('([A-Za-z][A-Za-z0-9-]*)'\\)").matcher(Files.readString(STATIC.resolve("app.js")));
        while (a.find()) assertThat(app).as("app.html has id " + a.group(1)).contains("id=\"" + a.group(1) + "\"");
    }

    @Test
    void pagesUseNoInlineScriptOrHandlersSoTheStrictCspHolds() throws Exception {
        for (String page : List.of("index.html", "app.html")) {
            String html = Files.readString(STATIC.resolve(page));
            assertThat(html).as(page).doesNotContainPattern("<script(?![^>]*\\ssrc=)").doesNotContainPattern("\\son[a-z]+=\"")
                    .doesNotContain("style=\"").doesNotContain("javascript:");
        }
    }

    @Test
    void siteCopyAvoidsClaimsTheDemoCannotBack() throws Exception {
        // FARMER-CALL-07 "Never say" list, checked against everything a visitor reads on the home page and in the stage.
        String text = (Files.readString(STATIC.resolve("index.html")) + Files.readString(STATIC.resolve("stage.js"))).toLowerCase();
        for (String banned : List.of("intercept", "zero-trust", "zero trust", "shield", "smart contract", "legally binding", "verified identity",
                "locked deal", "secure agreement", "fair price", "best price", "live rate", "today's price", "safe conversation",
                "protects the farmer", "works on any phone")) {
            assertThat(text).as("banned wording: " + banned).doesNotContain(banned);
        }
        // The caveats we promised to say out loud are present.
        String page = Files.readString(STATIC.resolve("index.html")).replaceAll("\\s+", " ");
        assertThat(page).contains("not a phone call", "Nothing is pre-recorded", "not today's rate", "nonbinding", "Ideas only",
                "Use synthetic speech", "One device, two seats");
        assertThat(page).containsPattern(Pattern.compile("not\\s+instant"));
    }

    private static int count(String s, String needle) {
        return s.split(Pattern.quote(needle), -1).length - 1;
    }

    private static void node(String script, Path... args) throws Exception {
        assumeTrue(run(List.of("node", "--version")) == 0, "node is not installed");
        List<String> cmd = new java.util.ArrayList<>(List.of("node", JS.resolve(script).toAbsolutePath().toString()));
        for (Path a : args) cmd.add(a.toAbsolutePath().toString());
        assertThat(run(cmd)).as("node " + script).isZero();
    }

    private static int run(List<String> cmd) throws Exception {
        try {
            return new ProcessBuilder(cmd).inheritIO().start().waitFor();
        } catch (java.io.IOException e) {
            return -1;
        }
    }
}
