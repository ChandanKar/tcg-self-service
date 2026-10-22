package com.tcgdigital.vmcontrol.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zone-agnostic, proven (U19-T07). Zone names are an account's own labels (eu-west-1a here is not
 * eu-west-1a in another account) and a region's zones differ in number and letters, so the code must
 * never derive a region by cutting a zone name, nor name a zone or zone ID itself. This fails the
 * build when application code (Java and the browser scripts) does either; examples in API docs and
 * comments are allowed.
 */
class NoZoneAssumptionsTest {

    private static final Path JAVA = Path.of("src/main/java");
    private static final Path JS = Path.of("src/main/resources/static/js");

    /** "…zone….substring(0, ….length() - 1)" or "…zone….slice(0, -1)": a region cut out of a zone name. */
    private static final Pattern CUT = Pattern.compile(
            "(?i)(zone|\\baz)\\w*(\\(\\))?\\s*\\.\\s*(substring\\(\\s*0\\s*,[^;]*length\\(\\)\\s*-\\s*1|slice\\(\\s*0\\s*,\\s*-1\\s*\\))");
    /** A zone name or zone ID written in the code: "us-east-1a", "euw1-az3". */
    private static final Pattern ZONE_LITERAL = Pattern.compile("[\"'`][a-z]{2}(-[a-z]+)+-[0-9][a-z][\"'`]");
    private static final Pattern ZONE_ID_LITERAL = Pattern.compile("[\"'`][a-z]{3,5}[0-9]-az[0-9][\"'`]");

    private static List<Path> sources(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(suffix)).filter(p -> !p.toString().contains("vendor")).toList();
        }
    }

    private static boolean ignorable(String line) {
        String t = line.trim();
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.contains("@Schema") || t.contains("example =");
    }

    private static List<String> offences(Pattern pattern) throws IOException {
        List<String> out = new ArrayList<>();
        List<Path> files = new ArrayList<>(sources(JAVA, ".java"));
        files.addAll(sources(JS, ".js"));
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!ignorable(line) && pattern.matcher(line).find()) {
                    out.add(file + ":" + (i + 1) + ": " + line.trim());
                }
            }
        }
        return out;
    }

    @Test
    void noRegionIsCutOutOfAZoneName() throws IOException {
        assertThat(offences(CUT)).as("derive the region from the VM's own region, never from its zone name").isEmpty();
    }

    @Test
    void noZoneNameOrZoneIdIsWrittenInTheCode() throws IOException {
        assertThat(offences(ZONE_LITERAL)).as("zones come from AWS, per account").isEmpty();
        assertThat(offences(ZONE_ID_LITERAL)).as("zone IDs come from AWS").isEmpty();
    }

    @Test
    void theGuardsCatchWhatTheyAreFor() {
        assertThat(CUT.matcher("String region = zone.substring(0, zone.length() - 1);").find()).isTrue();
        assertThat(CUT.matcher("const region = az.slice(0, -1);").find()).isTrue();
        assertThat(CUT.matcher("String r = vm.getAvailabilityZone().substring(0, vm.getAvailabilityZone().length() - 1);").find()).isTrue();
        assertThat(CUT.matcher("String region = vm.getRegion();").find()).isFalse();
        assertThat(ZONE_LITERAL.matcher("placement(\"us-east-1a\")").find()).isTrue();
        assertThat(ZONE_LITERAL.matcher("region(\"us-east-1\")").find()).isFalse();
        assertThat(ZONE_ID_LITERAL.matcher("zoneId = 'euw1-az3'").find()).isTrue();
    }
}
