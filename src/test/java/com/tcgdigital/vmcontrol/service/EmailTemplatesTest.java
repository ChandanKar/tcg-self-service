package com.tcgdigital.vmcontrol.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Event email bodies are plain text, escaped, with newlines as line breaks (E11-T09, M13). */
class EmailTemplatesTest {

    @Test
    void aBodyCannotInjectALink() {
        String html = EmailTemplates.eventEmail("Stop notice", "<a href=\"http://evil\">Click</a>");

        assertThat(html).doesNotContain("<a ");
        assertThat(html).contains("&lt;a href=&quot;http://evil&quot;&gt;Click&lt;/a&gt;");
    }

    @Test
    void quotesAreEscapedAndNewlinesBecomeLineBreaks() {
        String html = EmailTemplates.eventEmail("It's <b>title</b>", "Line one\nIt's line two");

        assertThat(html).contains("It&#39;s &lt;b&gt;title&lt;/b&gt;");
        assertThat(html).contains("Line one<br>It&#39;s line two");
    }
}
