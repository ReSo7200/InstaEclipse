package ps.reso.instaeclipse.patcher;

/** Receives a human-readable line for each step of the patch pipeline. */
public interface PatchLogger {
    void log(String line);
}
