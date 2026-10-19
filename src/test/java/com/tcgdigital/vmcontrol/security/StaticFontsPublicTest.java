package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** U03-T01: the self-hosted IBM Plex fonts load on the sign-in page, before anyone has signed in. */
class StaticFontsPublicTest extends SecuredWebTestBase {

    @Test
    void theInterfaceFontIsPublic() throws Exception {
        String type = mockMvc.perform(get("/webfonts/ibm-plex-sans-latin-400-normal.woff2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentType();

        assertThat(type).contains("font");
    }

    @Test
    void theDataFontIsPublic() throws Exception {
        mockMvc.perform(get("/webfonts/ibm-plex-mono-latin-400-normal.woff2")).andExpect(status().isOk());
    }
}
