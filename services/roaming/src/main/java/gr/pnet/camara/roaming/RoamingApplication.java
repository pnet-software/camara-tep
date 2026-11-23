package gr.pnet.camara.roaming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Scans gr.pnet.camara so the shared camara-common and tep-client beans are picked up,
// including in @WebMvcTest slices (exception handler, correlator filter)
@SpringBootApplication(scanBasePackages = "gr.pnet.camara")
public class RoamingApplication {

	public static void main(String[] args) {
		SpringApplication.run(RoamingApplication.class, args);
	}

}
