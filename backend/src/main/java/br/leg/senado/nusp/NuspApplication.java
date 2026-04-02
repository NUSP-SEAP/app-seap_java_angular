package br.leg.senado.nusp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class NuspApplication {
    public static void main(String[] args) {
        SpringApplication.run(NuspApplication.class, args);
    }
}
