package gr.pnet.camara.qualityondemand;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Auth on, as deployed. The issuer is only contacted on the first request carrying a token, so it needn't exist.
// Redis is only connected to on first use, and the expiry run, which would use it at once, is off.
@SpringBootTest(properties = {
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/test",
		"camara.qod.expiry-enabled=false" })
class QualityOnDemandApplicationTests {

	@Test
	void contextLoads() {
	}

}
