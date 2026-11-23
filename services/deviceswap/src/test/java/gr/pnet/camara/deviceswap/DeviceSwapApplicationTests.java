package gr.pnet.camara.deviceswap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Auth on, as deployed. The issuer is only contacted on the first request carrying a token, so it needn't exist.
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/test")
class DeviceSwapApplicationTests {

	@Test
	void contextLoads() {
	}

}
