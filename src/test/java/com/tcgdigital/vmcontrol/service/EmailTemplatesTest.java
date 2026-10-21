package com.tcgdigital.vmcontrol.service;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void theOnboardedEmailListsTheAccessAndHowToSignIn() {
        String html = EmailTemplates.onboardedEmail("Neha <b>Gupta</b>", "You have been added to VM Control as a user.",
                List.of("Operator on Payments Dev until 20 Nov 2026", "View only on CRM UAT, web group, no end date"),
                "choose Sign in with Microsoft and use your work account, neha@corp.example.", "https://vmcontrol.example");

        assertThat(html).contains("You can now use VM Control");
        assertThat(html).contains("Hello Neha &lt;b&gt;Gupta&lt;/b&gt;,");
        assertThat(html).contains("<li>Operator on Payments Dev until 20 Nov 2026</li>");
        assertThat(html).contains("<li>View only on CRM UAT, web group, no end date</li>");
        assertThat(html).contains("<b>How to sign in:</b> choose Sign in with Microsoft and use your work account, neha@corp.example.");
        assertThat(html).contains("<a href=\"https://vmcontrol.example\">Open VM Control</a>");
    }

    @Test
    void theOnboardedEmailWithoutAccessOrALinkSaysSo() {
        String html = EmailTemplates.onboardedEmail("Ravi", "Your access in VM Control has been updated.", List.of(),
                "your administrator will tell you how to sign in.", "javascript:alert(1)");

        assertThat(html).contains("You have no environments yet");
        assertThat(html).doesNotContain("<a ");
        assertThat(EmailTemplates.onboardedEmail("Ravi", "x", List.of(), "y", "")).doesNotContain("<a ");
    }
}
