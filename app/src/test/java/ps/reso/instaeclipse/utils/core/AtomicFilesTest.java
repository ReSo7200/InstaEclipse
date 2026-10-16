package ps.reso.instaeclipse.utils.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class AtomicFilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void replacesContentAndLeavesNoTempFile() throws Exception {
        File target = new File(tmp.getRoot(), "store.json");
        AtomicFiles.write(target, "{\"a\":1}");
        AtomicFiles.write(target, "{\"b\":2}");
        assertEquals("{\"b\":2}", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(target.getPath() + ".tmp").exists());
    }
}
