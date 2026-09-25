package org.stone.maven.plugin;

import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@MojoTest
class ResumableDownloadMojoTest {

    private static final String POM = "src/test/resources/unit/resumble-download-test/pom.xml";
    @Test
    @InjectMojo(goal = "download", pom = POM )
    void testMojoConfiguration(ResumableDownloadMojo mojo){
        assertNotNull(mojo, "mojo实例不应为null");
    }
}

