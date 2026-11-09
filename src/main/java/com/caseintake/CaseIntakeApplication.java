package com.caseintake;

import com.caseintake.cli.CommandLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CaseIntakeApplication {

    public static void main(String[] args) {
        CommandLauncher.launch(CaseIntakeApplication.class, args);
    }
}
