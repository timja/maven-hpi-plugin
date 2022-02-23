package test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.io.IOUtils;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;

public class WebAppResourceReloadTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void smokes() throws Exception {
        assertEquals("Initial version",
                     r.createWebClient().goTo("plugin/live-reload-of-webapp-resources/hello.txt", "text/plain")
                             .getWebResponse()
                             .getContentAsString()
                             .trim()
        );

        String updatedContent = "Updated file";
        Path resourceFile = Paths.get("src", "main", "webapp", "hello.txt");

        Files.write(resourceFile, updatedContent.getBytes(StandardCharsets.UTF_8.name()));

        assertEquals(updatedContent,
                r.createWebClient().goTo("plugin/live-reload-of-webapp-resources/hello.txt", "text/plain")
                        .getWebResponse()
                        .getContentAsString()
                        .trim()
        );
    }

}
