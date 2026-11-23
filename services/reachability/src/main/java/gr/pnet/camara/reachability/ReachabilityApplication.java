package gr.pnet.camara.reachability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Scans gr.pnet.camara so the shared camara-common and tep-client beans are picked up,
// including in @WebMvcTest slices (exception handler, correlator filter)
@SpringBootApplication(scanBasePackages = "gr.pnet.camara")
public class ReachabilityApplication {

	public static void main(String[] args) {
		SpringApplication.run(ReachabilityApplication.class, args);
	}

}
