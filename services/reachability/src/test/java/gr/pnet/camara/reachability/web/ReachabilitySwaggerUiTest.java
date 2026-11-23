package gr.pnet.camara.reachability.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Swagger UI's Authorize offers only this API's scopes: the initializer springdoc serves, from the whole
 * application as deployed, carries camara-common's scope filter.
 */
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/test")
@AutoConfigureMockMvc
class ReachabilitySwaggerUiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void initializerFiltersTheScopesAndLoadsTheCamaraSpec() throws Exception {
        mvc.perform(get("/swagger-ui/swagger-initializer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsInOrder("SwaggerUIBundle({", "responseInterceptor: ",
                        "openid-configuration", "presets: [")))
                .andExpect(content().string(containsString("/device-reachability-status.yaml")));
    }
}
