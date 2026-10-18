package com.itajay.superassistant.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic text-file replacement.
 *
 * <p>Writing a report or analysis with {@code TRUNCATE_EXISTING} destroys the old
 * copy before the new one exists: a crash, a full disk, or a killed process leaves a
 * truncated file that later stages treat as a completed artifact. Writing to a
 * sibling temp file and renaming avoids that window.</p>
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void writeString(Path target, String content) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".write-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
