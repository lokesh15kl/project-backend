package com.example.full.project;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FullProjectApplication {

    public static void main(String[] args) {
        // Keep TLS conservative for Atlas connectivity on restrictive networks.
        System.setProperty("jdk.tls.client.protocols", "TLSv1.2");
        System.setProperty("https.protocols", "TLSv1.2");
        System.setProperty("jdk.tls.client.enableStatusRequestExtension", "false");
        System.setProperty("com.sun.net.ssl.checkRevocation", "false");
        System.setProperty("com.sun.security.enableCRLDP", "false");
        System.setProperty("ocsp.enable", "false");
        SpringApplication.run(FullProjectApplication.class, args);
    }
}
